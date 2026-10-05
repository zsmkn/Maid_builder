package com.maidbuilder.client;

import com.maidbuilder.network.Payloads;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.File;
import java.util.List;
import java.util.Locale;

/** Lists the .litematic and .nbt files in the player's schematics folder; picking one starts positioning it. */
public class SchematicSelectScreen extends Screen {
    private FileList list;
    private EditBox search;
    private Button selectButton;
    private List<String> files = List.of();
    private Component status = Component.empty();

    public SchematicSelectScreen() {
        super(Component.translatable("screen.maidbuilder.select.title"));
    }

    @Override
    protected void init() {
        ClientSchematics.clear();
        files = ClientSchematics.list();
        search = new EditBox(font, width / 2 - 100, 22, 200, 18, Component.translatable("screen.maidbuilder.select.search"));
        search.setResponder(text -> refill());
        addRenderableWidget(search);

        list = new FileList(minecraft, width, height - 90, 46, 18);
        addRenderableWidget(list);
        refill();

        int y = height - 36;
        selectButton = addRenderableWidget(Button.builder(Component.translatable("screen.maidbuilder.select.choose"), b -> choose())
                .bounds(width / 2 - 154, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.maidbuilder.select.open_folder"), b -> openFolder())
                .bounds(width / 2 - 50, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(width / 2 + 54, y, 100, 20).build());
        setInitialFocus(search);
    }

    private void refill() {
        String filter = search.getValue().toLowerCase(Locale.ROOT);
        list.replace(files.stream().filter(f -> f.toLowerCase(Locale.ROOT).contains(filter)).toList());
    }

    private void openFolder() {
        File dir = ClientSchematics.folder().toFile();
        if (!dir.exists()) dir.mkdirs();
        Util.getPlatform().openFile(dir);
    }

    private void choose() {
        FileList.Entry entry = list.getSelected();
        if (entry == null) return;
        String file = entry.file;
        status = Component.translatable("screen.maidbuilder.select.loading");
        selectButton.active = false;
        ClientSchematics.load(file).whenComplete((loaded, error) -> Minecraft.getInstance().execute(() -> {
            if (loaded == null) {
                status = Component.translatable("screen.maidbuilder.select.error", file);
                selectButton.active = true;
                return;
            }
            PacketDistributor.sendToServer(new Payloads.SelectSchematic(file, loaded.sha1(), initialOrigin()));
            onClose();
        }));
    }

    /** Where the player looks (on the clicked face), or their feet. */
    private BlockPos initialOrigin() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            return hit.getBlockPos().relative(hit.getDirection());
        }
        return mc.player != null ? mc.player.blockPosition() : BlockPos.ZERO;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
        if (files.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("screen.maidbuilder.select.empty",
                    ClientSchematics.folder().toString()), width / 2, height / 2, 0xAAAAAA);
        }
        graphics.drawCenteredString(font, status, width / 2, height - 50, 0xFFFF55);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private class FileList extends ObjectSelectionList<FileList.Entry> {
        FileList(Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, itemHeight);
        }

        void replace(List<String> names) {
            clearEntries();
            for (String name : names) addEntry(new Entry(name));
        }

        @Override
        public int getRowWidth() {
            return 300;
        }

        class Entry extends ObjectSelectionList.Entry<Entry> {
            final String file;
            private long lastClick;

            Entry(String file) {
                this.file = file;
            }

            @Override
            public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                               int mouseX, int mouseY, boolean hovering, float partialTick) {
                graphics.drawString(font, file, left + 4, top + 4, 0xFFFFFF);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                FileList.this.setSelected(this);
                long now = Util.getMillis();
                if (now - lastClick < 250) choose();
                lastClick = now;
                return true;
            }

            @Override
            public Component getNarration() {
                return Component.literal(file);
            }
        }
    }
}
