package com.trop.keypanel.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class KeyPanelEditScreen extends Screen {

    private static final int W = 268;
    private static final int H = 158;
    private static final int PAD = 16;
    private static final net.minecraft.resources.ResourceLocation TEXTURE =
            new net.minecraft.resources.ResourceLocation("key_panel", "textures/gui/key_panel.png");
    private static final int TEX_W = 1024;
    private static final int TEX_H = 1024;
    private static final int BG_U = 700;
    private static final int BG_V = 200;
    private static final int BG_W = 300;
    private static final int BG_H = 190;

    private final KeyPanelScreen parent;
    private final SlotData data;

    private EditBox itemBox;
    private EditBox nameBox;
    private EditBox keyBox;
    private EditBox cmdBox;
    private final java.util.List<String> suggestions = new java.util.ArrayList<>();
    private int suggestionHover = -1;
    private int suggestionIndex = -1;
    private int suggestionScroll;
    private static final int SUGGEST_VISIBLE = 14;
    private int x0;
    private int y0;

    public KeyPanelEditScreen(KeyPanelScreen parent, SlotData data) {
        super(Component.literal("编辑格子"));
        this.parent = parent;
        this.data = data;
    }

    @Override
    protected void init() {
        this.x0 = (this.width - W) / 2;
        this.y0 = (this.height - H) / 2;

        this.itemBox = new EditBox(this.font, x0 + 74, y0 + 24, 118, 16, Component.literal("物品ID"));
        this.itemBox.setMaxLength(96);
        this.itemBox.setValue(data.getItemId() == null ? "" : data.getItemId());
        this.itemBox.setResponder(text -> data.setItemId(text));
        this.addRenderableWidget(this.itemBox);

        this.nameBox = new EditBox(this.font, x0 + 74, y0 + 46, 178, 16, Component.literal("名称"));
        this.nameBox.setMaxLength(32);
        this.nameBox.setValue(data.getCustomName() == null ? "" : data.getCustomName());
        this.nameBox.setResponder(text -> data.setCustomName(text));
        this.addRenderableWidget(this.nameBox);

        this.keyBox = new EditBox(this.font, x0 + 74, y0 + 68, 118, 16, Component.literal("逻辑按键ID"));
        this.keyBox.setMaxLength(64);
        this.keyBox.setValue(data.getKeyBinding() == null ? "" : data.getKeyBinding());
        this.keyBox.setResponder(text -> data.setKeyBinding(text));
        this.addRenderableWidget(this.keyBox);

        this.cmdBox = new EditBox(this.font, x0 + 74, y0 + 90, 178, 16, Component.literal("快捷命令"));
        this.cmdBox.setMaxLength(128);
        this.cmdBox.setValue(data.getCommand());
        this.cmdBox.setResponder(text -> {
            data.setCommand(text);
            refreshSuggestions(text);
        });
        this.addRenderableWidget(this.cmdBox);

        refreshSuggestions(this.cmdBox.getValue());

        this.addRenderableWidget(Button.builder(Component.literal("选择图标"), b ->
                        this.minecraft.setScreen(new ItemPickerScreen(this, data)))
                .bounds(x0 + 196, y0 + 23, 68, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("选择键位"), b ->
                        this.minecraft.setScreen(new KeyPickerScreen(this, data)))
                .bounds(x0 + 196, y0 + 67, 68, 18).build());
        int btnW = 76;
        int btnGap = 10;
        int btnTotal = btnW * 3 + btnGap * 2;
        int btnX = x0 + (W - btnTotal) / 2;
        this.addRenderableWidget(Button.builder(Component.literal("清空格子"), b -> clearSlot())
                .bounds(btnX, y0 + 132, btnW, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("保存"), b -> save())
                .bounds(btnX + btnW + btnGap, y0 + 132, btnW, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("返回"), b -> backToPanel())
                .bounds(btnX + (btnW + btnGap) * 2, y0 + 132, btnW, 18).build());
    }

    private void refreshSuggestions(String text) {
        this.suggestions.clear();
        if (this.minecraft == null || this.minecraft.player == null || text == null || text.isEmpty()) {
            return;
        }
        try {
            var conn = this.minecraft.player.connection;
            var dispatcher = conn.getCommands();
            String cmd = text.startsWith("/") ? text.substring(1) : text;
            var parsed = dispatcher.parse(cmd, conn.getSuggestionsProvider());
            var future = dispatcher.getCompletionSuggestions(parsed, cmd.length());
            future.thenAccept(list -> this.minecraft.execute(() -> {
                this.suggestions.clear();
                list.getList().forEach(sg -> this.suggestions.add(sg.getText()));
                this.suggestionIndex = -1;
                this.suggestionScroll = 0;
            }));
        } catch (Throwable ignored) {
        }
    }

    private int[] suggestionRect(int i) {
        int sx = x0 + W + 12;
        int sy = y0 + 16 + (i - suggestionScroll) * 12;
        return new int[]{sx, sy, sx + 156, sy + 12};
    }

    private int maxSuggestionScroll() {
        return Math.max(0, suggestions.size() - SUGGEST_VISIBLE);
    }

    private void clearSlot() {
        // 只清空该格子的配置，不修改该功能在「按键设置」中的键位。
        data.setItemId("");
        data.setCustomName("");
        data.setKeyBinding("");
        data.setCommand("");
        this.itemBox.setValue("");
        this.nameBox.setValue("");
        this.keyBox.setValue("");
        this.cmdBox.setValue("");
        if (this.parent != null) {
            this.parent.saveSlots();
        }
    }

    private void save() {
        data.setItemId(this.itemBox.getValue().trim());
        data.setCustomName(this.nameBox.getValue().trim());
        data.setKeyBinding(this.keyBox.getValue().trim());
        data.setCommand(this.cmdBox.getValue().trim());
        if (this.parent != null) {
            this.parent.saveSlots();
            this.minecraft.setScreen(this.parent);
        } else {
            this.onClose();
        }
    }

    private void backToPanel() {
        if (this.parent != null) {
            this.parent.saveSlots();
            this.minecraft.setScreen(this.parent);
        } else {
            this.onClose();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (!this.itemBox.isFocused() && !this.itemBox.getValue().equals(data.getItemId())) {
            this.itemBox.setValue(data.getItemId() == null ? "" : data.getItemId());
        }
        if (!this.keyBox.isFocused() && !this.keyBox.getValue().equals(data.getKeyBinding())) {
            this.keyBox.setValue(data.getKeyBinding() == null ? "" : data.getKeyBinding());
        }
        if (!this.nameBox.isFocused() && !this.nameBox.getValue().equals(data.getCustomName())) {
            this.nameBox.setValue(data.getCustomName() == null ? "" : data.getCustomName());
        }
        if (!this.cmdBox.isFocused() && !this.cmdBox.getValue().equals(data.getCommand())) {
            this.cmdBox.setValue(data.getCommand());
        }

        this.renderBackground(g);
        g.blit(TEXTURE, x0 - PAD, y0 - PAD, BG_W, BG_H, BG_U, BG_V, BG_W, BG_H, TEX_W, TEX_H);
        g.drawString(this.font, "编辑格子 #" + (data.getIndex() + 1), x0 + 2, y0 + 2, 0xFFBFFFFF, false);
        g.drawString(this.font, "物品ID", x0 + 4, y0 + 28, 0xFFC6D6D6, false);
        g.drawString(this.font, "名称", x0 + 4, y0 + 50, 0xFFC6D6D6, false);
        g.drawString(this.font, "逻辑按键", x0 + 4, y0 + 72, 0xFFC6D6D6, false);
        g.drawString(this.font, "快捷命令", x0 + 4, y0 + 94, 0xFFC6D6D6, false);
        g.drawString(this.font, "例：minecraft:chest / key.inventory / home（命令优先）", x0 + 2, y0 + 112, 0xFF6FA8A8, false);
        super.render(g, mouseX, mouseY, partialTick);
        g.flush();
        this.suggestionHover = -1;
        if (!this.suggestions.isEmpty() && this.cmdBox.isFocused()) {
            g.pose().pushPose();
            g.pose().translate(0.0F, 0.0F, 400.0F);
            int last = Math.min(this.suggestions.size(), this.suggestionScroll + SUGGEST_VISIBLE);
            for (int i = this.suggestionScroll; i < last; i++) {
                int[] r = suggestionRect(i);
                boolean hover = mouseX >= r[0] && mouseX < r[2] && mouseY >= r[1] && mouseY < r[3];
                if (hover) {
                    this.suggestionHover = i;
                }
                boolean picked = i == this.suggestionIndex;
                g.fill(r[0], r[1], r[2], r[3], (hover || picked) ? 0xFF17484E : 0xE0060A0B);
                g.renderOutline(r[0], r[1], r[2] - r[0], r[3] - r[1],
                        (hover || picked) ? 0xFF45F0F0 : 0x662FD9D9);
                g.drawString(this.font, this.suggestions.get(i), r[0] + 4, r[1] + 2,
                        (hover || picked) ? 0xFFBFFFFF : 0xFF9FC4C4, false);
            }
            if (this.suggestions.size() > SUGGEST_VISIBLE) {
                int trackX = x0 + W + 170;
                int trackY = y0 + 16;
                int trackH = SUGGEST_VISIBLE * 12;
                g.fill(trackX, trackY, trackX + 4, trackY + trackH, 0x6612262A);
                int barH = Math.max(10, trackH * SUGGEST_VISIBLE / this.suggestions.size());
                int barY = trackY + (trackH - barH) * this.suggestionScroll / Math.max(1, maxSuggestionScroll());
                g.fill(trackX, barY, trackX + 4, barY + barH, 0xFF45F0F0);
            }
            g.pose().popPose();
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && this.suggestionHover >= 0 && this.suggestionHover < this.suggestions.size()) {
            String pick = this.suggestions.get(this.suggestionHover);
            String cur = this.cmdBox.getValue();
            int space = cur.lastIndexOf(' ');
            this.cmdBox.setValue((space >= 0 ? cur.substring(0, space + 1) : "") + pick);
            refreshSuggestions(this.cmdBox.getValue());
            return true;
        }
        if (button == 0 && !this.cmdBox.isMouseOver(mouseX, mouseY)) {
            this.cmdBox.setFocused(false);
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!this.suggestions.isEmpty() && this.suggestions.size() > SUGGEST_VISIBLE) {
            this.suggestionScroll = Math.max(0,
                    Math.min(maxSuggestionScroll(), this.suggestionScroll - (int) Math.signum(delta)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {
            backToPanel();
            return true;
        }
        if (keyCode == 258 && !this.suggestions.isEmpty()) {
            boolean back = (modifiers & org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT) != 0;
            int n = this.suggestions.size();
            this.suggestionIndex = back
                    ? (this.suggestionIndex - 1 + n) % n
                    : (this.suggestionIndex + 1) % n;
            if (this.suggestionIndex < this.suggestionScroll) {
                this.suggestionScroll = this.suggestionIndex;
            } else if (this.suggestionIndex >= this.suggestionScroll + SUGGEST_VISIBLE) {
                this.suggestionScroll = this.suggestionIndex - SUGGEST_VISIBLE + 1;
            }
            String pick = this.suggestions.get(this.suggestionIndex);
            String cur = this.cmdBox.getValue();
            int space = cur.lastIndexOf(' ');
            String prefix = space >= 0 ? cur.substring(0, space + 1) : "";
            this.cmdBox.setValue(prefix + pick);
            return true;
        }
        if (keyCode == 257 || keyCode == 335) {
            save();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
