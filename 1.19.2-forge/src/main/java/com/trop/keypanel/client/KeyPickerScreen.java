package com.trop.keypanel.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.trop.keypanel.client.GuiCompat.drawHint;
import static com.trop.keypanel.client.GuiCompat.outline;

public class KeyPickerScreen extends Screen {

    private static final int ROWS = 14;
    private static final int ROW_H = 16;
    private static final int LIST_W = 300;

    private final Screen parent;
    private final SlotData data;

    private final List<KeyMapping> all = new ArrayList<>();
    private final List<KeyMapping> filtered = new ArrayList<>();

    private EditBox search;
    private int scroll;
    private int panelX;
    private int panelY;
    private int hovered = -1;
    private boolean draggingBar;

    public KeyPickerScreen(Screen parent, SlotData data) {
        super(Component.literal("选择逻辑按键"));
        this.parent = parent;
        this.data = data;
    }

    @Override
    protected void init() {
        for (KeyMapping mapping : Minecraft.getInstance().options.keyMappings) {
            if (mapping.getName() != null && !mapping.getName().isEmpty()) {
                all.add(mapping);
            }
        }
        this.panelX = (this.width - (LIST_W + 24)) / 2;
        this.panelY = (this.height - (ROWS * ROW_H + 76)) / 2;

        this.search = new EditBox(this.font, panelX + 12, panelY + 26, LIST_W, 16, Component.literal("搜索"));
        this.search.setMaxLength(48);
        this.search.setResponder(s -> {
            this.scroll = 0;
            applyFilter(s);
        });
        this.addRenderableWidget(this.search);
        this.setFocused(this.search);
        applyFilter("");

        this.addRenderableWidget(new Button(panelX + 12 + LIST_W / 2 - 30, panelY + ROWS * ROW_H + 44, 60, 18,
                Component.literal("返回"), b -> back()));
    }

    private void applyFilter(String query) {
        this.filtered.clear();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        for (KeyMapping mapping : all) {
            String name = Component.translatable(mapping.getName()).getString().toLowerCase(Locale.ROOT);
            String key = mapping.getTranslatedKeyMessage().getString().toLowerCase(Locale.ROOT);
            if (q.isEmpty() || name.contains(q) || key.contains(q)
                    || mapping.getName().toLowerCase(Locale.ROOT).contains(q)) {
                filtered.add(mapping);
            }
        }
    }

    private int listTop() {
        return panelY + 48;
    }

    private int barX() {
        return panelX + 12 + LIST_W + 3;
    }

    private int barHeight() {
        int trackH = ROWS * ROW_H;
        return Math.max(12, trackH * ROWS / Math.max(1, filtered.size()));
    }

    private void dragTo(double mouseY) {
        int trackH = ROWS * ROW_H;
        int barH = barHeight();
        double t = (mouseY - listTop() - barH / 2.0D) / Math.max(1.0D, trackH - barH);
        this.scroll = Math.max(0, Math.min(maxScroll(), (int) Math.round(t * maxScroll())));
    }

    private int maxScroll() {
        return Math.max(0, filtered.size() - ROWS);
    }

    private void back() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(poseStack);
        int totalW = LIST_W + 24;
        int totalH = ROWS * ROW_H + 76;
        fill(poseStack, panelX, panelY, panelX + totalW, panelY + totalH, 0xF0060A0B);
        outline(poseStack, panelX, panelY, totalW, totalH, 0xFF2FD9D9);
        drawString(poseStack, this.font, "点击一个功能，填入正在编辑的格子", panelX + 12, panelY + 10, 0xFFBFFFFF);

        this.hovered = -1;
        for (int i = 0; i < ROWS; i++) {
            int idx = scroll + i;
            if (idx >= filtered.size()) {
                break;
            }
            KeyMapping mapping = filtered.get(idx);
            int y = panelY + 48 + i * ROW_H;
            boolean hover = mouseX >= panelX + 12 && mouseX < panelX + 12 + LIST_W
                    && mouseY >= y && mouseY < y + ROW_H;
            if (hover) {
                this.hovered = idx;
                fill(poseStack, panelX + 12, y, panelX + 12 + LIST_W, y + ROW_H, 0xFF17484E);
                outline(poseStack, panelX + 12, y, LIST_W, ROW_H, 0xFF45F0F0);
            } else if ((i & 1) == 1) {
                fill(poseStack, panelX + 12, y, panelX + 12 + LIST_W, y + ROW_H, 0x33000000);
            }
            drawString(poseStack, this.font, Component.translatable(mapping.getName()).getString(),
                    panelX + 18, y + 4, 0xFFC6D6D6);
            String keyName = mapping.getTranslatedKeyMessage().getString();
            drawString(poseStack, this.font, keyName, panelX + 12 + LIST_W - this.font.width(keyName) - 6,
                    y + 4, 0xFF8FF7F7);
        }
        if (filtered.size() > ROWS) {
            int trackH = ROWS * ROW_H;
            int barH = barHeight();
            int barY = panelY + 48 + (trackH - barH) * scroll / Math.max(1, maxScroll());
            fill(poseStack, panelX + 12 + LIST_W + 3, panelY + 48, panelX + 12 + LIST_W + 6, panelY + 48 + trackH, 0xFF12262A);
            fill(poseStack, panelX + 12 + LIST_W + 3, barY, panelX + 12 + LIST_W + 6, barY + barH, 0xFF45F0F0);
        }
        super.render(poseStack, mouseX, mouseY, partialTick);
        drawHint(poseStack, this.font, this.search, "搜索功能（名称或按键）…");
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0
                && mouseX >= barX() - 2 && mouseX < barX() + 9
                && mouseY >= listTop() && mouseY < listTop() + ROWS * ROW_H) {
            this.draggingBar = true;
            dragTo(mouseY);
            return true;
        }
        if (button == 0 && hovered >= 0 && hovered < filtered.size()) {
            data.setKeyBinding(filtered.get(hovered).getName());
            back();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingBar) {
            dragTo(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.draggingBar = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        this.scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(delta)));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {
            back();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
