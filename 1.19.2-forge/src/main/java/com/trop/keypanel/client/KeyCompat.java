package com.trop.keypanel.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.trop.keypanel.KeyPanelMod;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.settings.KeyModifier;

/**
 * 1.19.2 的按键兼容层。
 * <p>
 * 修饰键一律走 Forge 官方入口 {@code IForgeKeyMapping}：
 * 读 {@code getKeyModifier()}、写 {@code setKeyModifierAndCode(modifier, key)}。
 * 后者会同时更新静态 {@code MAP} 查找表（先 remove 再 put），
 * 直接写字段做不到这一点。
 */
final class KeyCompat {

    /**
     * 1.19.2 里 {@code clickCount} 是私有的，没有公开读取入口，
     * 只能反射：开发环境是 {@code clickCount}，生产运行是 SRG 名 {@code f_90818_}。
     * 取不到时返回 -1，调用方据此跳过「计数是否增加」的判定。
     */
    private static final String[] CLICK_COUNT_FIELDS = {"clickCount", "f_90818_"};

    private static java.lang.reflect.Field cachedField;
    private static boolean resolved;

    private KeyCompat() {
    }

    private static java.lang.reflect.Field field() {
        if (resolved) {
            return cachedField;
        }
        resolved = true;
        for (String name : CLICK_COUNT_FIELDS) {
            try {
                java.lang.reflect.Field f = KeyMapping.class.getDeclaredField(name);
                f.setAccessible(true);
                cachedField = f;
                break;
            } catch (Throwable ignored) {
            }
        }
        return cachedField;
    }

    static int readClickCount(KeyMapping mapping) {
        java.lang.reflect.Field f = field();
        if (f == null || mapping == null) {
            return -1;
        }
        try {
            return f.getInt(mapping);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 兜底补一次点击计数。调用点只有一处：发键后隔两 tick 复核，发现原版入口
     * <b>确实没给这个映射加计数</b>（{@code readClickCount} 与发键前判等）时才补，
     * 因此不会重复计数。
     */
    static boolean bumpClickCount(KeyMapping mapping) {
        java.lang.reflect.Field f = field();
        if (f == null || mapping == null) {
            return false;
        }
        try {
            f.setInt(mapping, f.getInt(mapping) + 1);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 目标功能当前的修饰键。原来的键位是「Ctrl+G」这类组合键时，
     * 还原必须把修饰键一起写回去，否则玩家的绑定会被永久改成裸键。
     */
    static KeyModifier getModifier(KeyMapping mapping) {
        if (mapping == null) {
            return KeyModifier.NONE;
        }
        try {
            KeyModifier modifier = mapping.getKeyModifier();
            return modifier == null ? KeyModifier.NONE : modifier;
        } catch (Throwable t) {
            return KeyModifier.NONE;
        }
    }

    /** 键 + 修饰键一起改绑；异常兜底，失败返回 false，与 1.20.1 / 1.21.1 的行为保持一致。 */
    static boolean setKeyModifierAndCode(KeyMapping mapping, KeyModifier modifier, InputConstants.Key key) {
        if (mapping == null || key == null) {
            return false;
        }
        try {
            mapping.setKeyModifierAndCode(modifier == null ? KeyModifier.NONE : modifier, key);
            return true;
        } catch (Throwable t) {
            KeyPanelMod.LOGGER.warn("[key_panel] 改绑按键失败：{}", t.toString());
            return false;
        }
    }

    /**
     * 当前是否真的按着这个映射要求的修饰键。
     * 与 {@code KeyMappingLookup.get} 的判定同源（都走 {@code KeyModifier.isActive}），
     * 这样「面板热键需要 Shift+G」时，裸按 G 不会再误触发。
     */
    static boolean modifierActive(KeyMapping mapping) {
        if (mapping == null) {
            return false;
        }
        try {
            return mapping.getKeyModifier().isActive(mapping.getKeyConflictContext());
        } catch (Throwable t) {
            return false;
        }
    }
}
