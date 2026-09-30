package com.trop.keypanel.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import static com.trop.keypanel.client.GuiCompat.blitTexture;
import static com.trop.keypanel.client.GuiCompat.flush;
import static com.trop.keypanel.client.GuiCompat.outline;

public class KeyPanelEditScreen extends Screen {

    private static final int W = 268;
    private static final int H = 158;
    private static final int PAD = 16;
    private static final net.minecraft.resources.ResourceLocation TEXTURE =
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("key_panel", "textures/gui/key_panel.png");
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
        super(Component.translatable("key_panel.edit.screen"));
        this.parent = parent;
        this.data = data;
    }

    @Override
    protected void init() {
        this.x0 = (this.width - W) / 2;
        this.y0 = (this.height - H) / 2;

        this.itemBox = new EditBox(this.font, x0 + 74, y0 + 24, 118, 16, Component.translatable("key_panel.edit.item_id"));
        this.itemBox.setMaxLength(96);
        this.itemBox.setValue(data.getItemId() == null ? "" : data.getItemId());
        // 回写统一 trim：原来「保存」会 trim、「返回」不 trim，文本带首尾空格时
        // 两个入口存下来的值不一样，item_id 解析失败卡片就没图标。
        this.itemBox.setResponder(text -> data.setItemId(text.trim()));
        this.addRenderableWidget(this.itemBox);

        this.nameBox = new EditBox(this.font, x0 + 74, y0 + 46, 178, 16, Component.translatable("key_panel.edit.name"));
        this.nameBox.setMaxLength(32);
        this.nameBox.setValue(data.getCustomName() == null ? "" : data.getCustomName());
        this.nameBox.setResponder(text -> data.setCustomName(text.trim()));
        this.addRenderableWidget(this.nameBox);

        this.keyBox = new EditBox(this.font, x0 + 74, y0 + 68, 118, 16, Component.translatable("key_panel.edit.key_id"));
        this.keyBox.setMaxLength(64);
        this.keyBox.setValue(data.getKeyBinding() == null ? "" : data.getKeyBinding());
        this.keyBox.setResponder(text -> data.setKeyBinding(text.trim()));
        this.addRenderableWidget(this.keyBox);

        this.cmdBox = new EditBox(this.font, x0 + 74, y0 + 90, 178, 16, Component.translatable("key_panel.edit.command"));
        this.cmdBox.setMaxLength(128);
        this.cmdBox.setValue(data.getCommand());
        this.cmdBox.setResponder(text -> {
            data.setCommand(text.trim());
            refreshSuggestions(text);
        });
        this.addRenderableWidget(this.cmdBox);

        refreshSuggestions(this.cmdBox.getValue());

        this.addRenderableWidget(new Button(x0 + 196, y0 + 23, 68, 18, Component.translatable("key_panel.edit.pick_item"),
                b -> this.minecraft.setScreen(new ItemPickerScreen(this, data))));
        this.addRenderableWidget(new Button(x0 + 196, y0 + 67, 68, 18, Component.translatable("key_panel.edit.pick_key"),
                b -> this.minecraft.setScreen(new KeyPickerScreen(this, data))));
        int btnW = 76;
        int btnGap = 10;
        int btnTotal = btnW * 3 + btnGap * 2;
        int btnX = x0 + (W - btnTotal) / 2;
        this.addRenderableWidget(new Button(btnX, y0 + 132, btnW, 18, Component.translatable("key_panel.edit.clear"),
                b -> clearSlot()));
        this.addRenderableWidget(new Button(btnX + btnW + btnGap, y0 + 132, btnW, 18, Component.translatable("key_panel.edit.save"),
                b -> save()));
        this.addRenderableWidget(new Button(btnX + (btnW + btnGap) * 2, y0 + 132, btnW, 18, Component.translatable("key_panel.picker.back"),
                b -> backToPanel()));
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
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
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

        blitTexture(poseStack, TEXTURE, x0 - PAD, y0 - PAD, BG_W, BG_H, BG_U, BG_V, BG_W, BG_H, TEX_W, TEX_H);
        GuiCompat.drawString(poseStack, this.font,
                Component.translatable("key_panel.edit.title", data.getIndex() + 1).getString(),
                x0 + 2, y0 + 2, 0xFFBFFFFF);
        GuiCompat.drawString(poseStack, this.font, Component.translatable("key_panel.edit.item_id").getString(),
                x0 + 4, y0 + 28, 0xFFC6D6D6);
        GuiCompat.drawString(poseStack, this.font, Component.translatable("key_panel.edit.name").getString(),
                x0 + 4, y0 + 50, 0xFFC6D6D6);
        GuiCompat.drawString(poseStack, this.font, Component.translatable("key_panel.edit.key").getString(),
                x0 + 4, y0 + 72, 0xFFC6D6D6);
        GuiCompat.drawString(poseStack, this.font, Component.translatable("key_panel.edit.command").getString(),
                x0 + 4, y0 + 94, 0xFFC6D6D6);
        GuiCompat.drawString(poseStack, this.font, Component.translatable("key_panel.edit.example").getString(),
                x0 + 2, y0 + 112, 0xFF6FA8A8);
        super.render(poseStack, mouseX, mouseY, partialTick);
        flush();
        this.suggestionHover = -1;
        if (!this.suggestions.isEmpty() && this.cmdBox.isFocused()) {
            poseStack.pushPose();
            poseStack.translate(0.0D, 0.0D, 400.0D);
            int last = Math.min(this.suggestions.size(), this.suggestionScroll + SUGGEST_VISIBLE);
            for (int i = this.suggestionScroll; i < last; i++) {
                int[] r = suggestionRect(i);
                boolean hover = mouseX >= r[0] && mouseX < r[2] && mouseY >= r[1] && mouseY < r[3];
                if (hover) {
                    this.suggestionHover = i;
                }
                boolean picked = i == this.suggestionIndex;
                fill(poseStack, r[0], r[1], r[2], r[3], (hover || picked) ? 0xFF17484E : 0xE0060A0B);
                outline(poseStack, r[0], r[1], r[2] - r[0], r[3] - r[1],
                        (hover || picked) ? 0xFF45F0F0 : 0x662FD9D9);
                // 必须显式限定 GuiCompat.：Screen 从 GuiComponent 继承来的同名 drawString
                // 会遮蔽静态导入，画出来是带阴影的，和本模组其它界面的文字不一致。
                GuiCompat.drawString(poseStack, this.font, this.suggestions.get(i), r[0] + 4, r[1] + 2,
                        (hover || picked) ? 0xFFBFFFFF : 0xFF9FC4C4);
            }
            if (this.suggestions.size() > SUGGEST_VISIBLE) {
                int trackX = x0 + W + 170;
                int trackY = y0 + 16;
                int trackH = SUGGEST_VISIBLE * 12;
                fill(poseStack, trackX, trackY, trackX + 4, trackY + trackH, 0x6612262A);
                int barH = Math.max(10, trackH * SUGGEST_VISIBLE / this.suggestions.size());
                int barY = trackY + (trackH - barH) * this.suggestionScroll / Math.max(1, maxSuggestionScroll());
                fill(poseStack, trackX, barY, trackX + 4, barY + barH, 0xFF45F0F0);
            }
            poseStack.popPose();
        }
    }

    @Override
    public void renderBackground(PoseStack poseStack) {
        // 本模组界面自己画背景：不叠加原版的暗化层，否则它会盖在面板之上，
        // 把切角四角压成方的（渲染顺序问题）。
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
            this.setFocused((net.minecraft.client.gui.components.events.GuiEventListener) null);
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
            // suggestionIndex 初始是 -1（还没选过）：直接 (idx - 1 + n) % n 会算成 n-2，
            // 于是 Shift+Tab 第一次按下会跳过最后一项。先归一到 0，再统一用 floorMod 回绕。
            int cur = this.suggestionIndex < 0 ? 0 : this.suggestionIndex;
            this.suggestionIndex = back
                    ? Math.floorMod(cur - 1, n)
                    : Math.floorMod(cur + 1, n);
            if (this.suggestionIndex < this.suggestionScroll) {
                this.suggestionScroll = this.suggestionIndex;
            } else if (this.suggestionIndex >= this.suggestionScroll + SUGGEST_VISIBLE) {
                this.suggestionScroll = this.suggestionIndex - SUGGEST_VISIBLE + 1;
            }
            String pick = this.suggestions.get(this.suggestionIndex);
            String current = this.cmdBox.getValue();
            int space = current.lastIndexOf(' ');
            String prefix = space >= 0 ? current.substring(0, space + 1) : "";
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
