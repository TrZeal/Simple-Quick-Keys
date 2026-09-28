package com.trop.keypanel.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.resources.ResourceLocation;

/**
 * 面板的底 + 框，各界面共用：
 * <ul>
 *   <li>设了背景图：整块铺满，四角逐行沿斜线裁，斜角外不涂</li>
 *   <li>没设背景图：一块半透明底色（和附属卡片同档 0xB0）</li>
 *   <li>最后统一压上切角边框</li>
 * </ul>
 * 1.19.2 没有 {@code GuiGraphics}，绘制全部经 {@link GuiCompat} 走
 * {@code PoseStack} + {@code GuiComponent}。
 */
final class PanelFrame {

    static final ResourceLocation TEX =
            ResourceLocation.fromNamespaceAndPath("key_panel", "textures/gui/key_panel_v2.png");
    private static final int TEX_W = 512;
    private static final int TEX_H = 512;
    static final int PAD = 14;
    private static final int CHAMFER = 6;

    private PanelFrame() {
    }

    static void drawBackground(PoseStack poseStack, int x, int y, int w, int h, int fallbackColor) {
        if (PanelStyle.hasImage()) {
            float imgW = PanelStyle.imageWidth();
            float imgH = PanelStyle.imageHeight();
            float want = (float) w / (float) h;
            float have = imgW / imgH;
            float u0 = 0.0F;
            float v0 = 0.0F;
            float uw = imgW;
            float vh = imgH;
            if (have > want) {
                uw = imgH * want;
                u0 = (imgW - uw) / 2.0F;
            } else {
                vh = imgW / want;
                v0 = (imgH - vh) / 2.0F;
            }
            float[] win = {u0, v0, uw, vh};
            int c = PAD;
            int iw = w - 2 * c;
            int ih = h - 2 * c;
            part(poseStack, x, y, w, h, x + c, y, iw, c, win);
            part(poseStack, x, y, w, h, x + c, y + h - c, iw, c, win);
            part(poseStack, x, y, w, h, x, y + c, c, ih, win);
            part(poseStack, x, y, w, h, x + w - c, y + c, c, ih, win);
            part(poseStack, x, y, w, h, x + c, y + c, iw, ih, win);
            for (int j = 0; j < c; j++) {
                int cut = Math.max(0, CHAMFER - j);
                part(poseStack, x, y, w, h, x + cut, y + j, c - cut, 1, win);
                part(poseStack, x, y, w, h, x + w - c, y + j, c - cut, 1, win);
                part(poseStack, x, y, w, h, x + cut, y + h - 1 - j, c - cut, 1, win);
                part(poseStack, x, y, w, h, x + w - c, y + h - 1 - j, c - cut, 1, win);
            }
        } else {
            fillChamfered(poseStack, x, y, w, h, CHAMFER, fallbackColor);
        }
        draw(poseStack, x, y, w, h);
    }

    /**
     * 按切角形状填充：五块铺满切角以内，斜角那几行只补外侧小段。
     * 每块之间严格不重叠 —— 半透明色重复叠会变深，上下就会出现色带。
     *
     * @param cham 切角大小（面板 6、格子 4），同时就是四边色带的厚度
     */
    static void fillChamfered(PoseStack poseStack, int x, int y, int w, int h, int cham, int color) {
        GuiCompat.fill(poseStack, x + cham, y, x + w - cham, y + cham, color);
        GuiCompat.fill(poseStack, x + cham, y + h - cham, x + w - cham, y + h, color);
        GuiCompat.fill(poseStack, x, y + cham, x + cham, y + h - cham, color);
        GuiCompat.fill(poseStack, x + w - cham, y + cham, x + w, y + h - cham, color);
        GuiCompat.fill(poseStack, x + cham, y + cham, x + w - cham, y + h - cham, color);
        for (int j = 0; j < cham; j++) {
            int cut = cham - j;
            GuiCompat.fill(poseStack, x + cut, y + j, x + cham, y + j + 1, color);
            GuiCompat.fill(poseStack, x + w - cham, y + j, x + w - cut, y + j + 1, color);
            GuiCompat.fill(poseStack, x + cut, y + h - 1 - j, x + cham, y + h - j, color);
            GuiCompat.fill(poseStack, x + w - cham, y + h - 1 - j, x + w - cut, y + h - j, color);
        }
    }

    /** 贴背景图的一块：UV 按整块面板的裁剪窗口换算，块之间不会错位。 */
    private static void part(PoseStack poseStack, int px, int py, int pw, int ph,
                             int dx, int dy, int dw, int dh, float[] win) {
        float su = win[0] + win[2] * (float) (dx - px) / (float) pw;
        float sv = win[1] + win[3] * (float) (dy - py) / (float) ph;
        int tw = Math.max(1, Math.round(win[2] * (float) dw / (float) pw));
        int th = Math.max(1, Math.round(win[3] * (float) dh / (float) ph));
        GuiCompat.blitTexture(poseStack, PanelStyle.imageId(), dx, dy, dw, dh, su, sv, tw, th,
                PanelStyle.imageWidth(), PanelStyle.imageHeight());
    }

    /** 切角边框：九宫格贴图（角 14、边 16），斜角外侧透明。 */
    static void draw(PoseStack poseStack, int x, int y, int w, int h) {
        int c = PAD;
        int iw = w - 2 * c;
        int ih = h - 2 * c;
        GuiCompat.blitTexture(poseStack, TEX, x, y, c, c, 0, 170, c, c, TEX_W, TEX_H);
        GuiCompat.blitTexture(poseStack, TEX, x + w - c, y, c, c, 30, 170, c, c, TEX_W, TEX_H);
        GuiCompat.blitTexture(poseStack, TEX, x, y + h - c, c, c, 0, 200, c, c, TEX_W, TEX_H);
        GuiCompat.blitTexture(poseStack, TEX, x + w - c, y + h - c, c, c, 30, 200, c, c, TEX_W, TEX_H);
        GuiCompat.blitTexture(poseStack, TEX, x + c, y, iw, c, 14, 170, 16, c, TEX_W, TEX_H);
        GuiCompat.blitTexture(poseStack, TEX, x + c, y + h - c, iw, c, 14, 200, 16, c, TEX_W, TEX_H);
        GuiCompat.blitTexture(poseStack, TEX, x, y + c, c, ih, 0, 184, c, 16, TEX_W, TEX_H);
        GuiCompat.blitTexture(poseStack, TEX, x + w - c, y + c, c, ih, 30, 184, c, 16, TEX_W, TEX_H);
    }
}
