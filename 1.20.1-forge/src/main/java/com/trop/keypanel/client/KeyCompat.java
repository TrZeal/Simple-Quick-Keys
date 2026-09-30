package com.trop.keypanel.client;

import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.settings.KeyModifier;

final class KeyCompat {

    /**
     * 只在日志里对照「模拟按键前后 clickCount 有没有变」用的反射字段。
     * <p>
     * 两项都要留：开发环境是官方名 {@code clickCount}，生产（reobf）后是 SRG 名
     * {@code f_90818_}（1.20.1 / 1.19.2 实测同名）。少一项就会在正式游戏里静默读不到。
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
                if (f.getType() != int.class) {
                    continue;
                }
                f.setAccessible(true);
                cachedField = f;
                break;
            } catch (Throwable ignored) {
            }
        }
        return cachedField;
    }

    /** 读 clickCount（仅供日志对照）；读不到返回 -1。 */
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

    /** 读目标功能当前的修饰键（Shift / Ctrl / Alt / 无），读不到按「无」处理。 */
    static KeyModifier getKeyModifier(KeyMapping mapping) {
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

    /** 改绑：修饰键与键码一起写（走 Forge 官方 API，会同步 KeyMappingLookup 查找表）。 */
    static void setKeyModifierAndCode(KeyMapping mapping, KeyModifier modifier,
                                      com.mojang.blaze3d.platform.InputConstants.Key key) {
        if (mapping == null) {
            return;
        }
        try {
            mapping.setKeyModifierAndCode(modifier, key);
        } catch (Throwable t) {
            mapping.setKey(key);
        }
    }
}
