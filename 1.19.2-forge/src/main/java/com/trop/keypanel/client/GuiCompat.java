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

final class GuiCompat {

    private GuiCompat() {
    }

    static void fill(PoseStack poseStack, int x1, int y1, int x2, int y2, int color) {
        GuiComponent.fill(poseStack, x1, y1, x2, y2, color);
    }

    static void blitTexture(PoseStack poseStack, ResourceLocation texture, int x, int y, int width, int height,
                            int uOffset, int vOffset, int uWidth, int vHeight, int texWidth, int texHeight) {
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.setShaderTexture(0, texture);
        GuiComponent.blit(poseStack, x, y, width, height, (float) uOffset, (float) vOffset,
                uWidth, vHeight, texWidth, texHeight);
    }

    static void outline(PoseStack poseStack, int x, int y, int width, int height, int color) {
        fill(poseStack, x, y, x + width, y + 1, color);
        fill(poseStack, x, y + height - 1, x + width, y + height, color);
        fill(poseStack, x, y + 1, x + 1, y + height - 1, color);
        fill(poseStack, x + width - 1, y + 1, x + width, y + height - 1, color);
    }

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
        if (box.getValue().isEmpty() && !box.isFocused()) {
            font.draw(poseStack, hint, (float) (box.x + 4),
                    (float) (box.y + (box.getHeight() - 8) / 2), 0xFF707070);
        }
    }
}
