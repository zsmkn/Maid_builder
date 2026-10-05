package com.maidbuilder.client.preview;

import com.maidbuilder.client.MaidBuilderClientConfig;
import com.maidbuilder.common.BlockPlacer;
import com.maidbuilder.common.Convert;
import com.maidbuilder.common.StateResolver;
import com.maidbuilder.common.job.BuildJob;
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
 *   <li>Correct blocks are not drawn, missing ones as ghosts, wrong ones as red boxes.</li>
 * </ul>
 * Everything here runs on the render thread except {@link #create}, which may run in the background.
 */
public final class GhostPreview implements AutoCloseable {
    public static final byte UNKNOWN = 0, MATCH = 1, MISSING = 2, WRONG = 3;
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
        final List<BlockPos> outlines = new ArrayList<>();
        final List<BlockPos> placeholders = new ArrayList<>();
        int match, missing, wrong;

        Section(BlockPos origin, List<BlockPos> pos, List<BlockState> state, List<Integer> step) {
            this.origin = origin;
            this.box = new AABB(origin.getX(), origin.getY(), origin.getZ(), origin.getX() + 16, origin.getY() + 16, origin.getZ() + 16);
            this.pos = pos.toArray(BlockPos[]::new);
            this.state = state.toArray(BlockState[]::new);
            this.status = new byte[this.pos.length];
            this.step = step.stream().mapToInt(Integer::intValue).toArray();
        }
    }

    public final WandPlacement placement;
    private final List<Section> sections;
    private final BlockPos min, max;
    private final int total;
    /** Items each placed block consumes, indexed by {@link Section#step}. */
    private final List<List<BuildJob.Requirement>> requirements;
    private int scanCursor;
    /** Ghost opacity the section meshes were built with; a changed setting rebuilds them. */
    private float bakedAlpha = -1;

    private GhostPreview(WandPlacement placement, List<Section> sections, List<List<BuildJob.Requirement>> requirements,
                         BlockPos min, BlockPos max, int total) {
        this.placement = placement;
        this.sections = sections;
        this.requirements = requirements;
        this.min = min;
        this.max = max;
        this.total = total;
    }

    /** Plans the placed schematic into sections. Safe to call off the render thread. */
    public static GhostPreview create(Schematic schematic, WandPlacement wand) {
        Placement placement = wand.toCore();
        BuildPlan plan = BuildPlanner.plan(schematic, placement, new BuildPlanner.Options(true));
        StateResolver resolver = new StateResolver(schematic.minecraftDataVersion());
        Map<Long, List<BlockPos>> positions = new HashMap<>();
        Map<Long, List<BlockState>> states = new HashMap<>();
        Map<Long, List<Integer>> steps = new HashMap<>();
        List<List<BuildJob.Requirement>> requirements = new ArrayList<>();
        int count = 0;
        for (BuildStep step : plan.steps()) {
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
        List<Section> sections = new ArrayList<>(positions.size());
        for (Map.Entry<Long, List<BlockPos>> e : positions.entrySet()) {
            SectionPos sp = SectionPos.of(e.getKey());
            sections.add(new Section(sp.origin(), e.getValue(), states.get(e.getKey()), steps.get(e.getKey())));
        }
        BlockPos min = Convert.toBlockPos(placement.toWorld(schematic.minCorner()));
        BlockPos max = Convert.toBlockPos(placement.toWorld(schematic.maxCorner()));
        return new GhostPreview(wand, sections, requirements, BlockPos.min(min, max), BlockPos.max(min, max), count);
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
            budget -= s.pos.length;
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
        s.match = match;
        s.missing = missing;
        s.wrong = wrong;
        int signature = Arrays.hashCode(s.status);
        if (signature != s.signature) {
            s.signature = signature;
            s.dirty = true;
            s.outlines.clear();
            for (int i = 0; i < s.pos.length; i++) if (s.status[i] == WRONG) s.outlines.add(s.pos[i]);
        }
    }

    public int total() {
        return total;
    }

    public int count(byte status) {
        int n = 0;
        for (Section s : sections) n += status == MATCH ? s.match : status == MISSING ? s.missing : status == WRONG ? s.wrong : 0;
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
        if (alpha != bakedAlpha) {
            bakedAlpha = alpha;
            for (Section s : sections) s.dirty = true;
        }
        double distance = MaidBuilderClientConfig.PREVIEW_RENDER_DISTANCE.get();
        int rebuilds = 0;
        List<Section> visible = new ArrayList<>();
        for (Section s : sections) {
            if (s.box.getCenter().distanceToSqr(cam) > distance * distance) continue;
            if (!frustum.isVisible(s.box)) continue;
            if (s.dirty && rebuilds < MAX_REBUILDS_PER_FRAME) {
                rebuild(s, level, alpha);
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

    private static void rebuild(Section s, ClientLevel level, float alpha) {
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
            VertexConsumer consumer = new AlphaVertexConsumer(builder, alpha);
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
        if (MaidBuilderClientConfig.SHOW_BOUNDING_BOX.get()) {
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
            for (BlockPos pos : s.placeholders) {
                if (drawn++ > MAX_OUTLINES) break;
                LevelRenderer.renderLineBox(poseStack, lines, new AABB(pos).deflate(0.1), 0.4f, 0.8f, 1f, 0.9f);
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
