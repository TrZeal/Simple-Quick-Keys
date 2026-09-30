package com.trop.keypanel.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.trop.keypanel.KeyPanelMod;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = KeyPanelMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
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

    private KeyBindings() {
    }

    /**
     * 返回第 index 个格子对应的虚拟键。
     * <p>
     * <b>这里刻意只造 {@link InputConstants.Key}，不造占位 {@code KeyMapping}。</b>
     * 1.19.2 的 {@code KeyMapping.click(Key)} 是 {@code MAP.get(key)}（javap 实测：
     * {@code KeyMappingLookup.get} 只返回同键位桶里的<b>第一个</b>映射），
     * 而 {@code KeyMapping} 的构造器会把自己放进静态 {@code ALL} 表；
     * 占位 {@code KeyMapping} 与目标映射同键位时可能排在前面，把点击整个吞掉
     * → {@code consumeClick()} 型的目标点了没反应。
     * <p>
     * 虚拟键只作为「临时改绑的目标键码」使用，因此不需要（也不应该有）任何
     * {@code KeyMapping} 实例：改绑后该键位上有且只有目标映射一个，计数必然落在它身上。
     */
    public static InputConstants.Key virtualKey(int index) {
        return InputConstants.Type.KEYSYM.getOrCreate(VIRTUAL_KEY_BASE + Math.floorMod(index, VIRTUAL_COUNT));
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_PANEL);
    }
}
