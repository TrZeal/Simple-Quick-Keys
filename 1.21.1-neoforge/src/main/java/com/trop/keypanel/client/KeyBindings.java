package com.trop.keypanel.client;

import com.trop.keypanel.KeyPanelMod;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = KeyPanelMod.MODID, value = Dist.CLIENT)
public final class KeyBindings {

    public static final String CATEGORY = "key.categories.key_panel";

    public static final KeyMapping OPEN_PANEL =
            new KeyMapping("key.key_panel.open", GLFW.GLFW_KEY_G, CATEGORY);

    public static final int VIRTUAL_COUNT = 81;

    /**
     * 虚拟键键码起点：每个格子对应一个独立虚拟键，共 {@code VIRTUAL_COUNT} 个（163 ~ 243）。
     * <p>
     * 163 ~ 255 是 GLFW 未定义、实体键盘无法产生的键码区间，且位于合法范围
     * （{@code GLFW_KEY_SPACE} ~ {@code GLFW_KEY_LAST}）之内，
     * 因此既能保证每格互不干扰，也不会与玩家的真实按键冲突。
     */
    public static final int VIRTUAL_KEY_BASE = 163;

    private static final KeyMapping[] VIRTUAL = new KeyMapping[VIRTUAL_COUNT];

    static {
        for (int i = 0; i < VIRTUAL_COUNT; i++) {
            VIRTUAL[i] = new KeyMapping("#virtual." + (i + 1), VIRTUAL_KEY_BASE + i, CATEGORY);
        }
    }

    /** 返回第 index 个格子对应的虚拟键，其键码为 {@code VIRTUAL_KEY_BASE + index}。 */
    public static KeyMapping virtual(int index) {
        return VIRTUAL[Math.floorMod(index, VIRTUAL_COUNT)];
    }

    private KeyBindings() {
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_PANEL);
    }
}
