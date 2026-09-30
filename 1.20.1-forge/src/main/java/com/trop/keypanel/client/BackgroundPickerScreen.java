package com.trop.keypanel.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.awt.Desktop;
import java.util.ArrayList;
import java.util.List;

/**
 * 选背景图：把 config/key_panel/backgrounds/ 里的图片铺成一格一格的缩略图，
 * 点一格即用（再点当前那格取消）。面板外观与本模组其它界面一致。
 * <p>
 * 1.20.1 实测差异：{@code Screen#renderBackground} 只有 1 参版本
 * {@code renderBackground(GuiGraphics)}，且 {@code mouseScrolled} 仍是 3 参
 * {@code (double,double,double)}（4 参是 1.20.2+）。
 */
public class BackgroundPickerScreen extends Screen {

    private static final int COLS = 4;
    private static final int ROWS = 3;
    private static final int CELL_W = 100;
    private static final int CELL_H = 56;
    private static final int GAP_X = 10;
    private static final int GAP_Y = 16;
    private static final int PAD = 16;

    private final Screen parent;
    private final List<String> files = new ArrayList<>();

    private String status = "";
    private int scroll;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int hovered = -1;

    public BackgroundPickerScreen(Screen parent) {
        super(Component.translatable("key_panel.bg.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        rescan();
        panelW = COLS * CELL_W + (COLS - 1) * GAP_X + PAD * 2;
        panelH = 28 + ROWS * (CELL_H + GAP_Y) + 34;
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        int by = panelY + panelH - 26;
        this.addRenderableWidget(Button.builder(Component.translatable("key_panel.bg.open_dir"), b -> openFolder())
                .bounds(panelX + PAD, by, 92, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("key_panel.bg.refresh"), b -> rescan())
                .bounds(panelX + PAD + 98, by, 60, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("key_panel.bg.back"), b -> back())
                .bounds(panelX + PAD + 164, by, 60, 18).build());
        for (String name : files) {
            if (!name.isEmpty()) {
                PanelStyle.thumbId(name);      // 进界面就把缩略图读好，别等第一帧
            }
        }
    }

    /** 重新扫描 backgrounds 目录（放进新图后不用退出游戏）。 */
    private void rescan() {
        files.clear();
        files.add("");
        PanelStyle.clearThumbFailures();   // 目录里可能换了新图，给之前读不动的文件一次重试机会
        files.addAll(PanelStyle.listImages());
        scroll = Math.max(0, Math.min(maxScroll(), scroll));
    }

    /**
     * 关闭界面时释放缩略图贴图：不释放的话每张缩略图会一直占着显存直到退出游戏。
     * 下次进来 init() 会重新读一遍（命中失败缓存的坏图不会再重试）。
     */
    @Override
    public void removed() {
        super.removed();
        PanelStyle.releaseThumbs();
    }

    private int maxScroll() {
        int rows = (files.size() + COLS - 1) / COLS;
        return Math.max(0, rows - ROWS);
    }

    private void back() {
        Minecraft.getInstance().setScreen(parent);
    }

    /** 用系统文件管理器打开背景图目录：先试 AWT，失败再按系统走 explorer / open / xdg-open。 */
    private void openFolder() {
        java.nio.file.Path dir = PanelStyle.imageDir();
        try {
            java.nio.file.Files.createDirectories(dir);
        } catch (Exception e) {
            com.trop.keypanel.KeyPanelMod.LOGGER.warn("[key_panel] 创建背景图目录失败：{}", e.toString());
        }
        String path = dir.toAbsolutePath().toString();
        if (tryDesktop(dir) || trySystemOpener(path)) {
            status = Component.translatable("key_panel.bg.opened", path).getString();
            return;
        }
        try {
            Minecraft.getInstance().keyboardHandler.setClipboard(path);
        } catch (Throwable ignored) {
        }
        status = Component.translatable("key_panel.bg.open_failed", path).getString();
        com.trop.keypanel.KeyPanelMod.LOGGER.warn("[key_panel] 无法自动打开目录，已复制路径：{}", path);
    }

    private boolean tryDesktop(java.nio.file.Path dir) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(dir.toFile());
                return true;
            }
        } catch (Throwable t) {
            com.trop.keypanel.KeyPanelMod.LOGGER.warn("[key_panel] AWT 打开目录失败：{}", t.toString());
        }
        return false;
    }

    private boolean trySystemOpener(String path) {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String[] cmd = os.contains("win") ? new String[]{"explorer.exe", path}
                : os.contains("mac") ? new String[]{"open", path}
                : new String[]{"xdg-open", path};
        try {
            new ProcessBuilder(cmd).start();
            return true;
        } catch (Throwable t) {
            com.trop.keypanel.KeyPanelMod.LOGGER.warn("[key_panel] 调用系统打开命令失败：{}", t.toString());
            return false;
        }
    }

    private void drawThumb(GuiGraphics g, String name, int x, int y, int w, int h) {
        ResourceLocation id = PanelStyle.thumbId(name);
        g.fill(x, y, x + w, y + h, 0x80000000);
        if (id == null) {
            String t = Component.translatable("key_panel.bg.unreadable").getString();
            g.drawString(this.font, t, x + (w - this.font.width(t)) / 2, y + h / 2 - 4, 0xFF9FB6B6, false);
            return;
        }
        int[] s = PanelStyle.thumbSize(name);
        float want = (float) w / (float) h;
        float have = (float) s[0] / (float) s[1];
        float u0 = 0.0F;
        float v0 = 0.0F;
        float uw = s[0];
        float vh = s[1];
        if (have > want) {
            uw = s[1] * want;
            u0 = (s[0] - uw) / 2.0F;
        } else {
            vh = s[0] / want;
            v0 = (s[1] - vh) / 2.0F;
        }
        g.blit(id, x, y, w, h, u0, v0, (int) uw, (int) vh, s[0], s[1]);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        this.renderBackground(g);
        PanelFrame.drawBackground(g, panelX, panelY, panelW, panelH, 0xB00A0E0F);
        g.drawString(this.font, Component.translatable("key_panel.bg.hint").getString(),
                panelX + PAD, panelY + 10, 0xFFBFFFFF, false);

        this.hovered = -1;
        int total = files.size();
        for (int i = 0; i < ROWS * COLS; i++) {
            int idx = scroll * COLS + i;
            if (idx >= total) {
                break;
            }
            int col = i % COLS;
            int row = i / COLS;
            int x = panelX + PAD + col * (CELL_W + GAP_X);
            int y = panelY + 28 + row * (CELL_H + GAP_Y);
            String name = files.get(idx);
            boolean hover = mouseX >= x && mouseX < x + CELL_W && mouseY >= y && mouseY < y + CELL_H;
            if (hover) {
                this.hovered = idx;
            }
            boolean current = name.equals(PanelStyle.imageName());
            if (name.isEmpty()) {
                g.fill(x, y, x + CELL_W, y + CELL_H, 0xC00C1416);
                String t = Component.translatable("key_panel.bg.none_short").getString();
                g.drawString(this.font, t, x + (CELL_W - this.font.width(t)) / 2, y + CELL_H / 2 - 4, 0xFF9FB6B6, false);
            } else {
                drawThumb(g, name, x, y, CELL_W, CELL_H);
            }
            g.renderOutline(x, y, CELL_W, CELL_H, current ? 0xFF45F0F0 : (hover ? 0xFF8FF7F7 : 0x552FD9D9));
            String label = name.isEmpty() ? Component.translatable("key_panel.bg.none").getString() : name;
            label = this.font.plainSubstrByWidth((current ? "● " : "") + label, CELL_W);
            g.drawString(this.font, label, x, y + CELL_H + 3, current ? 0xFF45F0F0 : 0xFFC6D6D6, false);
        }

        String bottom = status.isEmpty()
                ? Component.translatable("key_panel.bg.dir", PanelStyle.imageDir().toString()).getString()
                : status;
        g.drawString(this.font, this.font.plainSubstrByWidth(bottom, panelW - PAD * 2),
                panelX + PAD, panelY + panelH - 40, status.isEmpty() ? 0xFF6FA8A8 : 0xFF8FF7F7, false);

        super.render(g, mouseX, mouseY, partialTick);
    }

    /**
     * 本模组界面自己画背景：1.20.1 的原版实现会铺一层纵向暗化渐变
     * （{@code fillGradient}），会把身后的世界压暗，与 1.1 的观感不一致。
     */
    @Override
    public void renderBackground(GuiGraphics g) {
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && hovered >= 0 && hovered < files.size()) {
            String name = files.get(hovered);
            PanelStyle.setImage(name.equals(PanelStyle.imageName()) ? "" : name);
            back();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        this.scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(scrollY)));
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
