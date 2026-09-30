package com.trop.keypanel.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.trop.keypanel.KeyPanelMod;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = KeyPanelMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class KeyPanelClientEvents {

    private static final java.util.Map<KeyMapping, Integer> PENDING_RELEASE = new java.util.HashMap<>();

    /** 发键那一刻的点击计数基线，等待原版下一次 handleKeybinds 处理完再复核。 */
    private static final java.util.Map<KeyMapping, int[]> PENDING_COUNT_CHECK = new java.util.HashMap<>();
    private static final int COUNT_CHECK_TICKS = 2;

    private KeyPanelClientEvents() {
    }

    public static void pressTemporarily(KeyMapping mapping) {
        mapping.setDown(true);
        PENDING_RELEASE.put(mapping, 6);
    }

    /**
     * 记下「发键前」的点击计数，下一 tick 复核。
     * <p>
     * 1.19.2 的点击计数不在发事件的当帧增加：{@code KeyboardHandler.keyPress} 只是记下按键状态，
     * 真正调 {@code KeyMapping.click(Key)} 的是下一次 client tick 里的 {@code Minecraft.handleKeybinds}。
     * 所以判定必须延后，否则每次都判成「没增加」而重复补计数。
     */
    public static void scheduleCountCheck(KeyMapping mapping, int before) {
        if (mapping == null || before < 0) {
            return;
        }
        PENDING_COUNT_CHECK.put(mapping, new int[]{before, COUNT_CHECK_TICKS});
    }

    public static void clearPendingCountCheck() {
        PENDING_COUNT_CHECK.clear();
    }

    /**
     * 复核点击计数：只有确实没涨时才补 1，避免重复计数。
     * <p>
     * 不涨的两种情况都要兜底：虚拟键因某种原因没走到 {@code KeyMapping.click}；
     * 或者目标功能在加载器里默认是「未绑定」（{@code isUnbound()}），原版入口直接跳过。
     */
    private static void checkClickCount(KeyMapping mapping, int before) {
        int now = KeyCompat.readClickCount(mapping);
        if (now < 0) {
            return;
        }
        if (now == before) {
            if (KeyCompat.bumpClickCount(mapping)) {
                KeyPanelMod.LOGGER.info("[key_panel] 原版入口未给 {} 计数（{}），已补 1 次",
                        mapping.getName(), now);
            }
        } else if (now != before + 1) {
            KeyPanelMod.LOGGER.warn("[key_panel] {} 的点击计数从 {} 变成 {}，疑似重复计数", mapping.getName(), before, now);
        }
    }

    /**
     * 模拟一次按键：把 key 交给原版按键入口处理。
     * <p>
     * 键盘键走 {@code KeyboardHandler.keyPress}，由原版完成点击计数、按下状态、按键匹配
     * 与当前界面的按键分发，加载器也会在该路径上派发 {@code InputEvent.Key}，
     * 因此原版功能、模组功能与仅监听原始按键事件的模组都能收到。
     * 鼠标键走公开的 {@code KeyMapping} API。
     */
    public static void fireKeyEvent(InputConstants.Key key, int scanCode, int action) {
        if (key == null || key == InputConstants.UNKNOWN) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        try {
            if (key.getType() == InputConstants.Type.MOUSE) {
                if (action == GLFW.GLFW_PRESS) {
                    KeyMapping.click(key);
                }
                KeyMapping.set(key, action != GLFW.GLFW_RELEASE);
                return;
            }
            mc.keyboardHandler.keyPress(mc.getWindow().getWindow(), key.getValue(), scanCode, action, 0);
        } catch (Throwable t) {
            // 兜底：若原版按键入口不可用，仍把事件派发到模组事件总线。
            try {
                InputEvent.Key event = new InputEvent.Key(key.getValue(), scanCode, action, 0);
                net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(event);
            } catch (Throwable ignored) {
            }
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        tickPendingRelease();
        tickCountCheck();
    }

    private static void tickPendingRelease() {
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

    private static void tickCountCheck() {
        if (PENDING_COUNT_CHECK.isEmpty()) {
            return;
        }
        java.util.Iterator<java.util.Map.Entry<KeyMapping, int[]>> it = PENDING_COUNT_CHECK.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<KeyMapping, int[]> e = it.next();
            int[] state = e.getValue();
            int left = state[1] - 1;
            if (left <= 0) {
                checkClickCount(e.getKey(), state[0]);
                it.remove();
            } else {
                state[1] = left;
            }
        }
    }

    /**
     * 面板热键：键码要匹配，<b>修饰键也要匹配</b>。
     * <p>
     * 原来只用 {@code matches(key, scanCode)}，1.19.2 的这个方法（javap 实测）只看键码，
     * 于是把热键设成 Shift+G 之后，裸按 G 也会把面板叫出来。
     * {@code KeyModifier.isActive} 与 {@code KeyMappingLookup.get} 的判定同源，
     * 因此这里与原版「可触发」的语义一致：Ctrl+G 不会再误触发裸 G 的热键。
     */
    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        if (event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }
        if (!KeyBindings.OPEN_PANEL.matches(event.getKey(), event.getScanCode())) {
            return;
        }
        if (!KeyCompat.modifierActive(KeyBindings.OPEN_PANEL)) {
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
