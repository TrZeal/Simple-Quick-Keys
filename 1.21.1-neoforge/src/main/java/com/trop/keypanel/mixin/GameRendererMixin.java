package com.trop.keypanel.mixin;

import com.trop.keypanel.client.ItemPickerScreen;
import com.trop.keypanel.client.KeyPanelEditScreen;
import com.trop.keypanel.client.KeyPanelScreen;
import com.trop.keypanel.client.KeyPickerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "processBlurEffect(F)V", at = @At("HEAD"), cancellable = true)
    private void keypanel$skipBlurForOwnScreens(float partialTick, CallbackInfo ci) {
        Screen screen = Minecraft.getInstance().screen;
        if (screen instanceof KeyPanelScreen
                || screen instanceof KeyPanelEditScreen
                || screen instanceof ItemPickerScreen
                || screen instanceof KeyPickerScreen) {
            ci.cancel();
        }
    }
}
