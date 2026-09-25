package com.trop.keypanel.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.trop.keypanel.KeyPanelMod;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ConfigManager {

    public static final int PAGE_SIZE = 27;
    public static final int PAGES = 3;
    public static final int SLOT_COUNT = PAGE_SIZE * PAGES;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final Path CONFIG_DIR = FMLPaths.CONFIGDIR.get().resolve(KeyPanelMod.MODID);
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("slots.json");

    private ConfigManager() {
    }

    public static Path getConfigFile() {
        return CONFIG_FILE;
    }

    public static List<SlotData> load() {
        try {
            if (!Files.exists(CONFIG_FILE)) {
                List<SlotData> defaults = defaults();
                save(defaults);
                return defaults;
            }
            try (Reader reader = Files.newBufferedReader(CONFIG_FILE, StandardCharsets.UTF_8)) {
                SlotData.File file = GSON.fromJson(reader, SlotData.File.class);
                List<SlotData> slots = file == null ? null : file.slots;
                return normalize(slots);
            }
        } catch (IOException | RuntimeException e) {
            KeyPanelMod.LOGGER.warn("[key_panel] 读取配置失败，使用默认配置：{}", e.toString());
            return defaults();
        }
    }

    public static void save(List<SlotData> slots) {
        try {
            Files.createDirectories(CONFIG_DIR);
            SlotData.File file = new SlotData.File();
            file.slots = new ArrayList<>(slots);
            try (Writer writer = Files.newBufferedWriter(CONFIG_FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(file, writer);
            }
        } catch (IOException e) {
            KeyPanelMod.LOGGER.warn("[key_panel] 保存配置失败：{}", e.toString());
        }
    }

    private static List<SlotData> normalize(List<SlotData> raw) {
        List<SlotData> out = new ArrayList<>(SLOT_COUNT);
        for (int i = 0; i < SLOT_COUNT; i++) {
            out.add(new SlotData(i, "", "", ""));
        }
        if (raw != null) {
            for (SlotData slot : raw) {
                if (slot == null) {
                    continue;
                }
                int i = slot.getIndex();
                if (i >= 0 && i < SLOT_COUNT) {
                    out.set(i, slot);
                }
            }
        }
        return out;
    }

    public static List<SlotData> defaults() {
        return normalize(null);
    }
}
