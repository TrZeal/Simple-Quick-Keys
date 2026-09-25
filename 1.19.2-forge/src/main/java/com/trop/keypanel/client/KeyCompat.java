package com.trop.keypanel.client;

import net.minecraft.client.KeyMapping;

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
                f.setAccessible(true);
                f.getInt(new Object());
            } catch (Throwable ignored) {
            }
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
}
