package com.maidbuilder.client.preview;

import com.maidbuilder.client.MaidBuilderClientConfig;
import com.maidbuilder.common.BlockBreaker;
import com.maidbuilder.common.BlockPlacer;
import com.maidbuilder.common.Convert;
import com.maidbuilder.common.StateResolver;
import com.maidbuilder.common.job.BuildJob;
import com.maidbuilder.core.plan.BuildPhase;
import com.maidbuilder.core.plan.BuildPlan;
import com.maidbuilder.core.plan.BuildPlanner;
import com.maidbuilder.core.plan.BuildStep;
import com.maidbuilder.core.schematic.Schematic;
import com.maidbuilder.core.transform.Placement;
import com.maidbuilder.item.WandPlacement;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Translucent preview of a placed schematic.
 * <ul>
 *   <li>Targets are grouped into 16^3 sections, each with its own baked {@link VertexBuffer}.</li>
 *   <li>The world is compared with the targets incrementally (a fixed block budget per tick,
 *       round robin over sections); a section's mesh is rebuilt only when its result changed.</li>
 *   <li>Correct blocks are not drawn, missing ones as ghosts, wrong ones as red boxes, and blocks
 *       standing in the schematic's air that the maids would clear (when the job clears air) as orange boxes.</li>
 * </ul>
 * Everything here runs on the render thread except {@link #create}, which may run in the background.
 */
public final class GhostPreview implements AutoCloseable {
    public static final byte UNKNOWN = 0, MATCH = 1, MISSING = 2, WRONG = 3;
    /** Only for {@link #count}: blocks in the cleared part of the schematic's air that the maids would break. */
    public static final byte TO_CLEAR = 4;
    private static final int MAX_REBUILDS_PER_FRAME = 2;
    private static final int MAX_OUTLINES = 4096;

    private static final class Section {
        final BlockPos origin;
        final AABB box;
        final BlockPos[] pos;
        final BlockState[] state;
        final byte[] status;
        /** Index into {@link #requirements} for a block's first part, -1 for the other half of a door or bed. */
        final int[] step;
        int signature = Integer.MIN_VALUE;
        boolean dirty;
        VertexBuffer buffer;
        /** Cells of the schematic's air the job clears, and whether each holds a block the maids would break. */
        final BlockPos[] clearPos;
        final boolean[] clearBlocked;
        final List<BlockPos> outlines = new ArrayList<>();
        final List<BlockPos> clearOutlines = new ArrayList<>();
        final List<BlockPos> placeholders = new ArrayList<>();
        int match, missing, wrong, toClear;

        Section(BlockPos origin, List<BlockPos> pos, List<BlockState> state, List<Integer> step, List<BlockPos> clearPos) {
            this.origin = origin;
            this.box = new AABB(origin.getX(), origin.getY(), origin.getZ(), origin.getX() + 16, origin.getY() + 16, origin.getZ() + 16);
            this.pos = pos.toArray(BlockPos[]::new);
            this.state = state.toArray(BlockState[]::new);
            this.status = new byte[this.pos.length];
            this.step = step.stream().mapToInt(Integer::intValue).toArray();
            this.clearPos = clearPos.toArray(BlockPos[]::new);
            this.clearBlocked = new boolean[this.clearPos.length];
        }
    }

    public final WandPlacement placement;
    /** How far the schematic's air is cleared (0: not at all), as the plan was made with. */
    public final int clearRadius;
    private final List<Section> sections;
    private final BlockPos min, max;
    private final int total;
    /** Items each placed block consumes, indexed by {@link Section#step}. */
    private final List<List<BuildJob.Requirement>> requirements;
    private int scanCursor;
    /** Ghost opacity the section meshes were built with; a changed setting rebuilds them. */
    private float bakedAlpha = -1;
    /** Build mode: the template cannot go here; ghosts are tinted red. */
    private boolean invalid;
    private boolean bakedInvalid;

    private GhostPreview(WandPlacement placement, int clearRadius, List<Section> sections, List<List<BuildJob.Requirement>> requirements,
                         BlockPos min, BlockPos max, int total) {
        this.placement = placement;
        this.clearRadius = clearRadius;
        this.sections = sections;
        this.requirements = requirements;
        this.min = min;
        this.max = max;
        this.total = total;
    }

    /** Plans the placed schematic into sections, without clearing. Safe to call off the render thread. */
    public static GhostPreview create(Schematic schematic, WandPlacement wand) {
        return create(schematic, wand, 0);
    }

    /**
     * Plans the placed schematic into sections; {@code clearRadius} (0: none) is how far the job clears
     * the schematic's air, as in {@link BuildPlanner.Options}. Safe to call off the render thread.
     */
    public static GhostPreview create(Schematic schematic, WandPlacement wand, int clearRadius) {
        Placement placement = wand.toCore();
        BuildPlan plan = BuildPlanner.plan(schematic, placement, new BuildPlanner.Options(true, clearRadius));
        StateResolver resolver = new StateResolver(schematic.minecraftDataVersion());
        Map<Long, List<BlockPos>> positions = new HashMap<>();
        Map<Long, List<BlockState>> states = new HashMap<>();
        Map<Long, List<Integer>> steps = new HashMap<>();
        Map<Long, List<BlockPos>> clear = new HashMap<>();
        List<List<BuildJob.Requirement>> requirements = new ArrayList<>();
        int count = 0;
        for (BuildStep step : plan.steps()) {
            if (step.phase() == BuildPhase.CLEAR) {
                BlockPos pos = Convert.toBlockPos(step.worldPos());
                clear.computeIfAbsent(SectionPos.asLong(pos), k -> new ArrayList<>()).add(pos);
                continue;
            }
            BlockState state = resolver.resolve(step.schematicState(), placement);
            if (state.isAir()) continue;
            int stepIndex = requirements.size();
            requirements.add(BuildJob.requirementsFor(step.schematicState(), state));
            boolean first = true;
            for (BlockPlacer.Part part : BlockPlacer.partsOf(Convert.toBlockPos(step.worldPos()), state)) {
                long key = SectionPos.asLong(part.pos());
                positions.computeIfAbsent(key, k -> new ArrayList<>()).add(part.pos());
                states.computeIfAbsent(key, k -> new ArrayList<>()).add(part.state());
                steps.computeIfAbsent(key, k -> new ArrayList<>()).add(first ? stepIndex : -1);
                first = false;
                count++;
            }
        }
        java.util.Set<Long> keys = new java.util.LinkedHashSet<>(positions.keySet());
        keys.addAll(clear.keySet());
        List<Section> sections = new ArrayList<>(keys.size());
        for (long key : keys) {
            SectionPos sp = SectionPos.of(key);
            sections.add(new Section(sp.origin(), positions.getOrDefault(key, List.of()), states.getOrDefault(key, List.of()),
                    steps.getOrDefault(key, List.of()), clear.getOrDefault(key, List.of())));
        }
        BlockPos min = Convert.toBlockPos(placement.toWorld(schematic.minCorner()));
        BlockPos max = Convert.toBlockPos(placement.toWorld(schematic.maxCorner()));
        return new GhostPreview(wand, clearRadius, sections, requirements, BlockPos.min(min, max), BlockPos.max(min, max), count);
    }

    // ---- world comparison ----

    /** Compares up to {@code budget} target blocks with the world. */
    public void tick(ClientLevel level, int budget) {
        if (sections.isEmpty()) return;
        int visited = 0;
        while (budget > 0 && visited < sections.size()) {
            Section s = sections.get(scanCursor);
            scanCursor = (scanCursor + 1) % sections.size();
            visited++;
            budget -= s.pos.length + s.clearPos.length;
            scan(level, s);
        }
    }

    private static void scan(ClientLevel level, Section s) {
        int match = 0, missing = 0, wrong = 0;
        for (int i = 0; i < s.pos.length; i++) {
            byte st;
            if (!level.isLoaded(s.pos[i])) {
                st = UNKNOWN;
            } else {
                BlockState world = level.getBlockState(s.pos[i]);
                if (BlockPlacer.matches(world, s.state[i])) st = MATCH;
                else if (world.canBeReplaced()) st = MISSING;
                else st = WRONG;
            }
            s.status[i] = st;
            if (st == MATCH) match++;
            else if (st == MISSING) missing++;
            else if (st == WRONG) wrong++;
        }
        int toClear = 0;
        for (int i = 0; i < s.clearPos.length; i++) {
            BlockPos pos = s.clearPos[i];
            s.clearBlocked[i] = level.isLoaded(pos) && BlockBreaker.breakableKind(level, pos, level.getBlockState(pos));
            if (s.clearBlocked[i]) toClear++;
        }
        s.match = match;
        s.missing = missing;
        s.wrong = wrong;
        s.toClear = toClear;
        int signature = 31 * Arrays.hashCode(s.status) + Arrays.hashCode(s.clearBlocked);
        if (signature != s.signature) {
            s.signature = signature;
            s.dirty = true;
            s.outlines.clear();
            for (int i = 0; i < s.pos.length; i++) if (s.status[i] == WRONG) s.outlines.add(s.pos[i]);
            s.clearOutlines.clear();
            for (int i = 0; i < s.clearPos.length; i++) if (s.clearBlocked[i]) s.clearOutlines.add(s.clearPos[i]);
        }
    }

    public int total() {
        return total;
    }

    public void setInvalid(boolean invalid) {
        this.invalid = invalid;
    }

    public int count(byte status) {
        int n = 0;
        for (Section s : sections) {
            n += status == MATCH ? s.match : status == MISSING ? s.missing : status == WRONG ? s.wrong : status == TO_CLEAR ? s.toClear : 0;
        }
        return n;
    }

    /**
     * Items needed for the whole structure ({@code [1]}) and for the blocks the world does not
     * have yet ({@code [0]}; blocks in unloaded or not yet compared chunks count as missing).
     */
    public Map<Item, int[]> materials() {
        Map<Item, int[]> result = new LinkedHashMap<>();
        for (Section s : sections) {
            for (int i = 0; i < s.pos.length; i++) {
                if (s.step[i] < 0) continue;
                boolean built = s.status[i] == MATCH;
                for (BuildJob.Requirement r : requirements.get(s.step[i])) {
                    int[] counts = result.computeIfAbsent(r.item(), k -> new int[2]);
                    counts[1] += r.count();
                    if (!built) counts[0] += r.count();
                }
            }
        }
        return result;
    }

    // ---- rendering ----

    public void render(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        Frustum frustum = event.getFrustum();

        float alpha = MaidBuilderClientConfig.GHOST_OPACITY.get().floatValue();
        if (alpha != bakedAlpha || invalid != bakedInvalid) {
            bakedAlpha = alpha;
            bakedInvalid = invalid;
            for (Section s : sections) s.dirty = true;
        }
        double distance = MaidBuilderClientConfig.PREVIEW_RENDER_DISTANCE.get();
        int rebuilds = 0;
        List<Section> visible = new ArrayList<>();
        for (Section s : sections) {
            if (s.box.getCenter().distanceToSqr(cam) > distance * distance) continue;
            if (!frustum.isVisible(s.box)) continue;
            if (s.dirty && rebuilds < MAX_REBUILDS_PER_FRAME) {
                rebuild(s, level, alpha, invalid);
                rebuilds++;
            }
            visible.add(s);
        }
        drawGhosts(visible, event.getModelViewMatrix(), event.getProjectionMatrix(), cam);
        // After the level pass the camera rotation is no longer on the global model-view stack,
        // which the line shader reads; put it back for the outlines.
        boolean afterLevel = event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL;
        Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
        if (afterLevel) {
            modelViewStack.pushMatrix();
            modelViewStack.mul(event.getModelViewMatrix());
            RenderSystem.applyModelViewMatrix();
        }
        drawOutlines(visible, cam);
        if (afterLevel) {
            modelViewStack.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    private static void rebuild(Section s, ClientLevel level, float alpha, boolean invalid) {
        s.dirty = false;
        s.placeholders.clear();
        Minecraft mc = Minecraft.getInstance();
        BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
        ModelBlockRenderer modelRenderer = dispatcher.getModelRenderer();
        BlockColors colors = mc.getBlockColors();
        RandomSource random = RandomSource.create();
        PoseStack poseStack = new PoseStack();
        int missing = 0;
        for (byte b : s.status) if (b == MISSING) missing++;

        try (ByteBufferBuilder bytes = new ByteBufferBuilder(Math.max(4096, missing * 24 * DefaultVertexFormat.BLOCK.getVertexSize()))) {
            BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
            VertexConsumer consumer = invalid ? new AlphaVertexConsumer(builder, alpha, 1f, 0.3f, 0.3f) : new AlphaVertexConsumer(builder, alpha);
            for (int i = 0; i < s.pos.length; i++) {
                if (s.status[i] != MISSING) continue;
                BlockState state = s.state[i];
                BlockPos pos = s.pos[i];
                if (state.getRenderShape() != RenderShape.MODEL) {
                    s.placeholders.add(pos); // chests, beds, fluids...: drawn as an outline
                    continue;
                }
                poseStack.pushPose();
                poseStack.translate(pos.getX() - s.origin.getX(), pos.getY() - s.origin.getY(), pos.getZ() - s.origin.getZ());
                BakedModel model = dispatcher.getBlockModel(state);
                int color = colors.getColor(state, level, pos, 0);
                float r = color == -1 ? 1f : (color >> 16 & 0xFF) / 255f;
                float g = color == -1 ? 1f : (color >> 8 & 0xFF) / 255f;
                float bl = color == -1 ? 1f : (color & 0xFF) / 255f;
                random.setSeed(42L);
                for (RenderType type : model.getRenderTypes(state, random, ModelData.EMPTY)) {
                    modelRenderer.renderModel(poseStack.last(), consumer, state, model, r, g, bl,
                            LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, type);
                }
                poseStack.popPose();
            }
            MeshData mesh = builder.build();
            if (mesh == null) {
                closeBuffer(s);
                return;
            }
            if (s.buffer == null) s.buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            s.buffer.bind();
            s.buffer.upload(mesh);
            VertexBuffer.unbind();
        }
    }

    private static void drawGhosts(List<Section> visible, Matrix4f modelView, Matrix4f projection, Vec3 cam) {
        RenderType type = RenderType.translucent();
        type.setupRenderState();
        RenderSystem.depthMask(false);
        ShaderInstance shader = GameRenderer.getRendertypeTranslucentShader();
        if (shader != null) {
            for (Section s : visible) {
                if (s.buffer == null) continue;
                if (shader.CHUNK_OFFSET != null) {
                    shader.CHUNK_OFFSET.set((float) (s.origin.getX() - cam.x), (float) (s.origin.getY() - cam.y), (float) (s.origin.getZ() - cam.z));
                }
                s.buffer.bind();
                s.buffer.drawWithShader(modelView, projection, shader);
            }
            VertexBuffer.unbind();
            if (shader.CHUNK_OFFSET != null) shader.CHUNK_OFFSET.set(0f, 0f, 0f);
        }
        RenderSystem.depthMask(true);
        type.clearRenderState();
    }

    private void drawOutlines(List<Section> visible, Vec3 cam) {
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        PoseStack poseStack = new PoseStack();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        if (invalid) {
            LevelRenderer.renderLineBox(poseStack, lines, new AABB(Vec3.atLowerCornerOf(min), Vec3.atLowerCornerOf(max).add(1, 1, 1)),
                    1f, 0.25f, 0.25f, 1f);
        } else if (MaidBuilderClientConfig.SHOW_BOUNDING_BOX.get()) {
            LevelRenderer.renderLineBox(poseStack, lines, new AABB(Vec3.atLowerCornerOf(min), Vec3.atLowerCornerOf(max).add(1, 1, 1)),
                    1f, 1f, 1f, 0.6f);
        }
        boolean showWrong = MaidBuilderClientConfig.SHOW_WRONG_BLOCKS.get();
        int drawn = 0;
        for (Section s : visible) {
            for (BlockPos pos : showWrong ? s.outlines : List.<BlockPos>of()) {
                if (drawn++ > MAX_OUTLINES) break;
                LevelRenderer.renderLineBox(poseStack, lines, new AABB(pos).inflate(0.005), 1f, 0.2f, 0.2f, 1f);
            }
            for (BlockPos pos : showWrong ? s.clearOutlines : List.<BlockPos>of()) {
                if (drawn++ > MAX_OUTLINES) break;
                LevelRenderer.renderLineBox(poseStack, lines, new AABB(pos).inflate(0.005), 1f, 0.6f, 0.1f, 1f);
            }
            for (BlockPos pos : s.placeholders) {
                if (drawn++ > MAX_OUTLINES) break;
                if (invalid) LevelRenderer.renderLineBox(poseStack, lines, new AABB(pos).deflate(0.1), 1f, 0.3f, 0.3f, 0.9f);
                else LevelRenderer.renderLineBox(poseStack, lines, new AABB(pos).deflate(0.1), 0.4f, 0.8f, 1f, 0.9f);
            }
        }
        buffers.endBatch(RenderType.lines());
    }

    private static void closeBuffer(Section s) {
        if (s.buffer != null) {
            s.buffer.close();
            s.buffer = null;
        }
    }

    @Override
    public void close() {
        for (Section s : sections) closeBuffer(s);
    }
}
