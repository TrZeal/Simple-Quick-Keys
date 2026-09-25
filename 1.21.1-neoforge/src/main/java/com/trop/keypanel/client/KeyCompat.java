package com.trop.keypanel.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.extensions.IKeyMappingExtension;
import net.neoforged.neoforge.client.settings.KeyModifier;

final class KeyCompat {

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
}
