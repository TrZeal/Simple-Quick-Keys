package com.trop.keypanel.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * 1.19.2 没有 {@code GuiGraphics}，所有绘制都走 {@code PoseStack} + {@code GuiComponent}
 * 静态方法。这里把各界面用到的绘制原语集中起来，方法名刻意与 {@code GuiGraphics} 不同，
 * 避免被 {@code Screen} 从 {@code GuiComponent} 继承来的同名静态方法遮蔽（JLS 6.5.7.1）。
 */
final class GuiCompat {

    private GuiCompat() {
    }

    static void fill(PoseStack poseStack, int x1, int y1, int x2, int y2, int color) {
        GuiComponent.fill(poseStack, x1, y1, x2, y2, color);
    }

    static void blitTexture(PoseStack poseStack, ResourceLocation texture, int x, int y, int width, int height,
                            int uOffset, int vOffset, int uWidth, int vHeight, int texWidth, int texHeight) {
        blitTexture(poseStack, texture, x, y, width, height, (float) uOffset, (float) vOffset,
                uWidth, vHeight, texWidth, texHeight);
    }

    /**
     * 贴图（含半透明贴图）绘制：UV 用 float，供自定义背景图按窗口裁剪时取小数 UV。
     * <p>
     * 1.19.2 的 {@code GuiComponent.fill}/{@code fillGradient} 结尾会
     * {@code enableTexture() + disableBlend()}（javap -c 实测），也就是「每画一次色块，
     * 混合就被关掉」。所以这里不能只设着色器与贴图，还必须每次把混合函数设回标准值，
     * 否则贴图的 alpha 会被当成不透明 —— 半透明卡片/壁纸会画成一块实色。
     * <p>
     * 着色器、着色器颜色与贴图都在绘制前设置，并保持 1/1/1/1，不把染色留给后面的绘制。
     */
    static void blitTexture(PoseStack poseStack, ResourceLocation texture, int x, int y, int width, int height,
                            float uOffset, float vOffset, int uWidth, int vHeight, int texWidth, int texHeight) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.setShaderTexture(0, texture);
        GuiComponent.blit(poseStack, x, y, width, height, uOffset, vOffset, uWidth, vHeight, texWidth, texHeight);
    }

    static void outline(PoseStack poseStack, int x, int y, int width, int height, int color) {
        fill(poseStack, x, y, x + width, y + 1, color);
        fill(poseStack, x, y + height - 1, x + width, y + height, color);
        fill(poseStack, x, y + 1, x + 1, y + height - 1, color);
        fill(poseStack, x + width - 1, y + 1, x + width, y + height - 1, color);
    }

    /** 无阴影文字：对应 1.20+ 的 {@code drawString(font, text, x, y, color, false)}。 */
    static void drawString(PoseStack poseStack, Font font, String text, int x, int y, int color) {
        font.draw(poseStack, text, (float) x, (float) y, color);
    }

    static void renderItem(ItemRenderer itemRenderer, ItemStack stack, int x, int y) {
        itemRenderer.renderGuiItem(stack, x, y);
    }

    static void renderItemScaled(ItemRenderer itemRenderer, ItemStack stack, float x, float y, float scale) {
        PoseStack modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        modelView.translate((double) x, (double) y, 0.0D);
        modelView.scale(scale, scale, 1.0F);
        RenderSystem.applyModelViewMatrix();
        itemRenderer.renderGuiItem(stack, 0, 0);
        modelView.popPose();
        RenderSystem.applyModelViewMatrix();
    }

    static void flush() {
        Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
    }

    static void drawHint(PoseStack poseStack, Font font, EditBox box, String hint) {
        // 1.19.2 的 EditBox 没有 setHint，占位提示自绘
        if (box.getValue().isEmpty() && !box.isFocused()) {
            font.draw(poseStack, hint, (float) (box.x + 4),
                    (float) (box.y + (box.getHeight() - 8) / 2), 0xFF707070);
        }
    }
}
