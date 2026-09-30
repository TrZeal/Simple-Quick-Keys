package com.trop.keypanel.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.extensions.IKeyMappingExtension;
import net.neoforged.neoforge.client.settings.KeyModifier;

final class KeyCompat {

    // 1.21.1 运行期使用官方名，字段就是 clickCount；SRG 的 f_90818_ 在本平台无意义，已删
    private static final String[] CLICK_COUNT_FIELDS = {"clickCount"};

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

    static InputConstants.Key keyOf(KeyMapping mapping) {
        if (mapping == null) {
            return InputConstants.UNKNOWN;
        }
        try {
            return ((IKeyMappingExtension) mapping).getKey();
        } catch (Throwable t) {
            return InputConstants.UNKNOWN;
        }
    }

    /**
     * 读取按键当前绑定的修饰键（B1）。
     * 还原虚拟键时只有把修饰键一起还原，玩家带 Shift / Ctrl / Alt 的绑定才不会被改成裸键。
     */
    static KeyModifier modifierOf(KeyMapping mapping) {
        if (mapping == null) {
            return KeyModifier.NONE;
        }
        try {
            KeyModifier modifier = ((IKeyMappingExtension) mapping).getKeyModifier();
            return modifier == null ? KeyModifier.NONE : modifier;
        } catch (Throwable t) {
            return KeyModifier.NONE;
        }
    }

    static void setKeyModifierAndCode(KeyMapping mapping, KeyModifier modifier, InputConstants.Key key) {
        if (mapping == null) {
            return;
        }
        try {
            ((IKeyMappingExtension) mapping).setKeyModifierAndCode(modifier, key);
        } catch (Throwable t) {
            mapping.setKey(key);
        }
    }

    /** 判定按键（含修饰键）是否正好匹配（B5）：1.21.1 走 NeoForge 的 isActiveAndMatches。 */
    static boolean isActiveAndMatches(KeyMapping mapping, InputConstants.Key key) {
        if (mapping == null || key == null || key == InputConstants.UNKNOWN) {
            return false;
        }
        try {
            return ((IKeyMappingExtension) mapping).isActiveAndMatches(key);
        } catch (Throwable t) {
            KeyModifier modifier = modifierOf(mapping);
            return (modifier == KeyModifier.NONE || KeyModifier.getActiveModifiers().contains(modifier))
                    && mapping.matches(key.getValue(), 0);
        }
    }
}
