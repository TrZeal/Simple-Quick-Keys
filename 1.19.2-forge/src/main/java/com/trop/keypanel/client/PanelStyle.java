package com.trop.keypanel.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.trop.keypanel.KeyPanelMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 面板整体外观设置：背景颜色与自定义背景图片。
 * <p>
 * 配置文件 {@code config/key_panel/background.json}，图片放在
 * {@code config/key_panel/backgrounds/}，支持 png / jpg。
 * 颜色 {@code -1} 表示使用默认外观，{@code image} 为空表示不使用图片。
 * <p>
 * 1.19.2 差异：{@code ResourceLocation} 用构造器（没有
 * {@code fromNamespaceAndPath}），FML 路径类在 {@code net.minecraftforge.fml.loading}。
 */
public final class PanelStyle {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path DIR = FMLPaths.CONFIGDIR.get().resolve(KeyPanelMod.MODID);
    private static final Path FILE = DIR.resolve("background.json");
    private static final Path IMAGE_DIR = DIR.resolve("backgrounds");

    private static int color = -1;
    private static String image = "";

    private static ResourceLocation loadedId;
    private static String loadedName = "";
    private static int loadedW;
    private static int loadedH;

    private PanelStyle() {
    }

    public static Path imageDir() {
        return IMAGE_DIR;
    }

    public static int color() {
        return color;
    }

    public static String imageName() {
        return image;
    }

    public static boolean hasImage() {
        return loadedId != null;
    }

    public static ResourceLocation imageId() {
        return loadedId;
    }

    public static int imageWidth() {
        return Math.max(1, loadedW);
    }

    public static int imageHeight() {
        return Math.max(1, loadedH);
    }

    public static void setColor(int rgb) {
        color = rgb;
        save();
    }

    public static void setImage(String name) {
        image = name == null ? "" : name;
        save();
        loadImage();
    }

    /** 读取配置并预备图片（在打开面板前调用）。 */
    public static void load() {
        try {
            Files.createDirectories(IMAGE_DIR);
            if (Files.exists(FILE)) {
                try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
                    Data d = GSON.fromJson(r, Data.class);
                    if (d != null) {
                        color = d.color;
                        image = d.image == null ? "" : d.image;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            KeyPanelMod.LOGGER.warn("[key_panel] 读取背景配置失败：{}", e.toString());
        }
        loadImage();
    }

    public static void save() {
        try {
            Files.createDirectories(DIR);
            Data d = new Data();
            d.color = color;
            d.image = image;
            try (Writer w = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(d, w);
            }
        } catch (IOException e) {
            KeyPanelMod.LOGGER.warn("[key_panel] 保存背景配置失败：{}", e.toString());
        }
    }

    /** 扫描 {@code backgrounds/} 下可用的图片文件名。 */
    public static List<String> listImages() {
        List<String> out = new ArrayList<>();
        try {
            Files.createDirectories(IMAGE_DIR);
            try (var stream = Files.list(IMAGE_DIR)) {
                stream.filter(Files::isRegularFile).forEach(p -> {
                    String n = p.getFileName().toString();
                    String low = n.toLowerCase(Locale.ROOT);
                    if (low.endsWith(".png") || low.endsWith(".jpg") || low.endsWith(".jpeg")) {
                        out.add(n);
                    }
                });
            }
        } catch (IOException e) {
            KeyPanelMod.LOGGER.warn("[key_panel] 扫描背景图片失败：{}", e.toString());
        }
        out.sort(String::compareToIgnoreCase);
        return out;
    }

    private static void loadImage() {
        if (image.isEmpty()) {
            releaseImage();
            return;
        }
        if (image.equals(loadedName) && loadedId != null) {
            return;
        }
        Path file = IMAGE_DIR.resolve(image);
        if (!Files.isRegularFile(file)) {
            KeyPanelMod.LOGGER.warn("[key_panel] 背景图片不存在：{}", file);
            releaseImage();
            return;
        }
        try (InputStream in = Files.newInputStream(file)) {
            var nativeImage = readImage(file, in);
            releaseImage();
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(KeyPanelMod.MODID,
                    "backgrounds/" + Integer.toHexString(image.hashCode()));
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(nativeImage));
            loadedId = id;
            loadedName = image;
            loadedW = nativeImage.getWidth();
            loadedH = nativeImage.getHeight();
            KeyPanelMod.LOGGER.info("[key_panel] 背景图片已加载：{}（{}x{}）", image, loadedW, loadedH);
        } catch (IOException | RuntimeException e) {
            KeyPanelMod.LOGGER.warn("[key_panel] 背景图片加载失败：{}", e.toString());
            releaseImage();
        }
    }

    /**
     * 读图片：PNG 先交给原版读，失败或本来就是 jpg / jpeg / 其它格式时，
     * 再走 AWT 解码后转成 NativeImage（原版只认 PNG，直接丢 jpg 会报 Bad PNG Signature）。
     */
    private static com.mojang.blaze3d.platform.NativeImage readImage(Path file, InputStream in) throws IOException {
        String low = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (low.endsWith(".png")) {
            try {
                return com.mojang.blaze3d.platform.NativeImage.read(in);
            } catch (IOException | RuntimeException e) {
                KeyPanelMod.LOGGER.warn("[key_panel] PNG 直读失败，改用通用解码：{}", e.toString());
            }
        }
        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(Files.newInputStream(file));
        if (img == null) {
            throw new IOException(Component.translatable("key_panel.bg.decode_failed").getString());
        }
        int w = img.getWidth();
        int h = img.getHeight();
        var out = new com.mojang.blaze3d.platform.NativeImage(
                com.mojang.blaze3d.platform.NativeImage.Format.RGBA, w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int bl = argb & 0xFF;
                // NativeImage 在小端机上按 ABGR 打包，和 1.21.1 的写法一致
                out.setPixelRGBA(x, y, (a << 24) | (bl << 16) | (g << 8) | r);
            }
        }
        return out;
    }

    private static final java.util.Map<String, ResourceLocation> THUMBS = new java.util.HashMap<>();
    private static final java.util.Map<String, int[]> THUMB_SIZE = new java.util.HashMap<>();

    /** 背景选择界面用的缩略图贴图（按文件名缓存）。 */
    public static ResourceLocation thumbId(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        ResourceLocation cached = THUMBS.get(name);
        if (cached != null) {
            return cached;
        }
        Path file = IMAGE_DIR.resolve(name);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(file)) {
            var img = readImage(file, in);
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(KeyPanelMod.MODID,
                    "thumbs/" + Integer.toHexString(name.hashCode()));
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(img));
            THUMBS.put(name, id);
            THUMB_SIZE.put(name, new int[]{img.getWidth(), img.getHeight()});
            return id;
        } catch (IOException | RuntimeException e) {
            KeyPanelMod.LOGGER.warn("[key_panel] 缩略图读取失败：{}", name);
            return null;
        }
    }

    public static int[] thumbSize(String name) {
        int[] s = THUMB_SIZE.get(name);
        return s == null ? new int[]{1, 1} : s;
    }

    private static void releaseImage() {
        if (loadedId != null) {
            Minecraft.getInstance().getTextureManager().release(loadedId);
        }
        loadedId = null;
        loadedName = "";
        loadedW = 0;
        loadedH = 0;
    }

    private static final class Data {
        int color = -1;
        String image = "";
    }
}
