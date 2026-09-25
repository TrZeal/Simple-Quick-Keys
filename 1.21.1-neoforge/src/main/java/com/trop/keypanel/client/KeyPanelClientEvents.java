package com.trop.keypanel.client;

import com.trop.keypanel.KeyPanelMod;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = KeyPanelMod.MODID, value = Dist.CLIENT)
public final class KeyPanelClientEvents {

    private static final java.util.Map<KeyMapping, Integer> PENDING_RELEASE = new java.util.HashMap<>();

    private KeyPanelClientEvents() {
    }

    public static void pressTemporarily(KeyMapping mapping) {
        mapping.setDown(true);
        PENDING_RELEASE.put(mapping, 6);
    }

    /**
     * 模拟一次按键：把 key 交给原版按键入口处理。
     * <p>
     * 键盘键走 {@code KeyboardHandler.keyPress}，由原版完成点击计数、按下状态、按键匹配
     * 与当前界面的按键分发，NeoForge 也会在该路径上派发 {@code InputEvent.Key}，
     * 因此原版功能、模组功能与仅监听原始按键事件的模组都能收到。
     * 鼠标键走公开的 {@code KeyMapping} API。
     */
    public static void fireKeyEvent(com.mojang.blaze3d.platform.InputConstants.Key key, int scanCode, int action) {
        if (key == null || key == com.mojang.blaze3d.platform.InputConstants.UNKNOWN) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        try {
            if (key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.MOUSE) {
                if (action == org.lwjgl.glfw.GLFW.GLFW_PRESS) {
                    KeyMapping.click(key);
                }
                KeyMapping.set(key, action != org.lwjgl.glfw.GLFW.GLFW_RELEASE);
                return;
            }
            mc.keyboardHandler.keyPress(mc.getWindow().getWindow(), key.getValue(), scanCode, action, 0);
        } catch (Throwable t) {
            // 兜底：若原版按键入口不可用，仍把事件派发到模组事件总线。
            try {
                NeoForge.EVENT_BUS.post(new InputEvent.Key(key.getValue(), scanCode, action, 0));
            } catch (Throwable ignored) {
            }
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (PENDING_RELEASE.isEmpty()) {
            return;
        }
        java.util.Iterator<java.util.Map.Entry<KeyMapping, Integer>> it = PENDING_RELEASE.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<KeyMapping, Integer> e = it.next();
            int left = e.getValue() - 1;
            if (left <= 0) {
                e.getKey().setDown(false);
                it.remove();
            } else {
                e.getKey().setDown(true);
                e.setValue(left);
            }
        }
    }

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        if (event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }
        if (!KeyBindings.OPEN_PANEL.matches(event.getKey(), event.getScanCode())) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof KeyPanelScreen) {
            mc.screen.onClose();
        } else if (mc.screen == null) {
            mc.setScreen(new KeyPanelScreen());
        }
    }
}
