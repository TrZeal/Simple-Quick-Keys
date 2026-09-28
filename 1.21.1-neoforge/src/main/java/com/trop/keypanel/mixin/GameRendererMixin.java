package com.trop.keypanel.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 只对本模组的界面跳过原版「菜单背景模糊」。
 *
 * <p>原版会在界面打开时对身后的世界做模糊后处理，入口是
 * {@code GameRenderer#processBlurEffect(float)}。这里在方法开头拦截，
 * 只要是本模组的界面就取消调用，身后世界保持清晰；其它界面（背包 / 设置…）
 * 维持原版行为不变。
 *
 * <p>判定用包名而不是逐个 instanceof：本模组界面全部在
 * {@code com.trop.keypanel} 下，新增界面不用再回来补名单。
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "processBlurEffect(F)V", at = @At("HEAD"), cancellable = true)
    private void keypanel$skipBlurForOwnScreens(float partialTick, CallbackInfo ci) {
        Screen screen = Minecraft.getInstance().screen;
        if (screen != null && screen.getClass().getName().startsWith("com.trop.keypanel.")) {
            ci.cancel();
        }
    }
}
