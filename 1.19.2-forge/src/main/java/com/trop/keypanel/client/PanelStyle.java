package com.trop.keypanel.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.trop.keypanel.KeyPanelMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.ByteArrayInputStream;
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
 * 1.19.2 差异：FML 路径类在 {@code net.minecraftforge.fml.loading}。
 */
public final class PanelStyle {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path DIR = FMLPaths.CONFIGDIR.get().resolve(KeyPanelMod.MODID);
    private static final Path FILE = DIR.resolve("background.json");
    private static final Path IMAGE_DIR = DIR.resolve("backgrounds");

    /** 解码后的最长边上限：原图直接上传显存又大又费，统一降到这个尺寸再注册。 */
    private static final int MAX_EDGE = 512;

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

    /** 改颜色并落盘（用于「点一下就要存」的入口，如点击色块复位）。 */
    public static void setColor(int rgb) {
        color = rgb;
        save();
    }

    /**
     * 只改内存里的颜色，不落盘。拖滑块时每帧都会调这里，
     * 原来直接走 {@link #setColor} 会以约 60 次/秒的频率重写 background.json；
     * 拖完（松手）由 {@link #save()} 统一落盘一次。
     */
    public static void setColorInMemory(int rgb) {
        color = rgb;
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
            // 只打文件名，不打本机绝对路径
            KeyPanelMod.LOGGER.warn("[key_panel] 背景图片不存在：{}", image);
            releaseImage();
            return;
        }
        try {
            var nativeImage = readImage(file);
            releaseImage();
            ResourceLocation id = textureId("backgrounds", image);
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(nativeImage));
            loadedId = id;
            loadedName = image;
            loadedW = nativeImage.getWidth();
            loadedH = nativeImage.getHeight();
            KeyPanelMod.LOGGER.info("[key_panel] 背景图片已加载：{}（{}x{}）", image, loadedW, loadedH);
        } catch (IOException | RuntimeException e) {
            KeyPanelMod.LOGGER.warn("[key_panel] 背景图片加载失败：{}（{}）", image, e.toString());
            releaseImage();
        }
    }

    /**
     * 读图片并降采样：PNG 先交给原版读，失败或本来就是 jpg / jpeg / 其它格式时，
     * 再走 AWT 解码后转成 NativeImage（原版只认 PNG，直接丢 jpg 会报 Bad PNG Signature）。
     * <p>
     * 两条路径最终都保证最长边不超过 {@link #MAX_EDGE}，避免把原始分辨率直接传上显存。
     * 文件只在这里读入一次（{@code Files.readAllBytes}），AWT 那条分支复用同一份字节，
     * 不再另外 {@code Files.newInputStream} —— 那个流原来没人关，Windows 下会锁住图片。
     */
    private static com.mojang.blaze3d.platform.NativeImage readImage(Path file) throws IOException {
        String name = file.getFileName().toString();
        String low = name.toLowerCase(Locale.ROOT);
        byte[] bytes = Files.readAllBytes(file);
        if (low.endsWith(".png")) {
            try (InputStream in = new ByteArrayInputStream(bytes)) {
                var nativeImage = com.mojang.blaze3d.platform.NativeImage.read(in);
                if (nativeImage.getWidth() <= MAX_EDGE && nativeImage.getHeight() <= MAX_EDGE) {
                    return nativeImage;
                }
                // PNG 太大：转成 BufferedImage 后统一走降采样分支
                java.awt.image.BufferedImage img = toBufferedImage(nativeImage);
                nativeImage.close();
                return toNativeImage(img);
            } catch (IOException | RuntimeException e) {
                KeyPanelMod.LOGGER.warn("[key_panel] PNG 直读失败，改用通用解码：{}", e.toString());
            }
        }
        java.awt.image.BufferedImage img;
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            img = javax.imageio.ImageIO.read(in);
        }
        if (img == null) {
            throw new IOException(Component.translatable("key_panel.bg.decode_failed").getString());
        }
        return toNativeImage(img);
    }

    private static java.awt.image.BufferedImage toBufferedImage(com.mojang.blaze3d.platform.NativeImage nativeImage) {
        int w = nativeImage.getWidth();
        int h = nativeImage.getHeight();
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int abgr = nativeImage.getPixelRGBA(x, y);
                int a = (abgr >>> 24) & 0xFF;
                int b = (abgr >>> 16) & 0xFF;
                int g = (abgr >>> 8) & 0xFF;
                int r = abgr & 0xFF;
                img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return img;
    }

    /** 降采样到最长边 {@link #MAX_EDGE}（本来就是小图就原样转），再转 NativeImage。 */
    private static com.mojang.blaze3d.platform.NativeImage toNativeImage(java.awt.image.BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int max = Math.max(w, h);
        int dw = w;
        int dh = h;
        if (max > MAX_EDGE) {
            double scale = (double) MAX_EDGE / (double) max;
            dw = Math.max(1, (int) Math.round(w * scale));
            dh = Math.max(1, (int) Math.round(h * scale));
        }
        var out = new com.mojang.blaze3d.platform.NativeImage(
                com.mojang.blaze3d.platform.NativeImage.Format.RGBA, dw, dh, false);
        for (int y = 0; y < dh; y++) {
            int sy = Math.min(h - 1, (int) ((long) y * h / dh));
            for (int x = 0; x < dw; x++) {
                int sx = Math.min(w - 1, (int) ((long) x * w / dw));
                int argb = src.getRGB(sx, sy);
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

    /**
     * 纹理 ID：用「前缀 + 文件名」这种稳定且互不冲突的串（非法字符替换掉），
     * 不再用 {@code Integer.toHexString(name.hashCode())} —— 那有碰撞风险，
     * 两张不同的图撞上同一个 ID 就会互相顶掉贴图。
     */
    private static ResourceLocation textureId(String prefix, String name) {
        return ResourceLocation.fromNamespaceAndPath(KeyPanelMod.MODID, prefix + "/" + sanitize(name));
    }

    private static String sanitize(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.';
            sb.append(ok ? c : '_');
        }
        return sb.toString();
    }

    private static final java.util.Map<String, ResourceLocation> THUMBS = new java.util.HashMap<>();
    private static final java.util.Map<String, int[]> THUMB_SIZE = new java.util.HashMap<>();
    /** 解码失败的图：记下来就不再重试，否则每帧都要重读一次文件并刷日志。 */
    private static final java.util.Set<String> THUMB_FAILED = new java.util.HashSet<>();

    /** 背景选择界面用的缩略图贴图（按文件名缓存，解码后已降采样）。失败的不再重试。 */
    public static ResourceLocation thumbId(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        ResourceLocation cached = THUMBS.get(name);
        if (cached != null) {
            return cached;
        }
        if (THUMB_FAILED.contains(name)) {
            return null;
        }
        Path file = IMAGE_DIR.resolve(name);
        if (!Files.isRegularFile(file)) {
            THUMB_FAILED.add(name);
            return null;
        }
        com.mojang.blaze3d.platform.NativeImage img = null;
        try {
            img = readImage(file);
            int w = Math.max(1, img.getWidth());
            int h = Math.max(1, img.getHeight());
            ResourceLocation id = textureId("thumbs", name);
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(img));
            img = null;   // DynamicTexture 接管所有权，这里不能再关
            THUMBS.put(name, id);
            THUMB_SIZE.put(name, new int[]{w, h});
            return id;
        } catch (IOException | RuntimeException e) {
            THUMB_FAILED.add(name);
            KeyPanelMod.LOGGER.warn("[key_panel] 缩略图读取失败：{}（{}）", name, e.toString());
            return null;
        } finally {
            if (img != null) {
                img.close();
            }
        }
    }

    public static int[] thumbSize(String name) {
        int[] s = THUMB_SIZE.get(name);
        return s == null ? new int[]{1, 1} : s;
    }

    /**
     * 释放缩略图纹理（关闭背景选择界面时调用）与失败缓存，
     * 否则这些解码出来的贴图会一直占着显存。
     */
    public static void releaseThumbs() {
        var manager = Minecraft.getInstance().getTextureManager();
        for (ResourceLocation id : THUMBS.values()) {
            manager.release(id);
        }
        THUMBS.clear();
        THUMB_SIZE.clear();
        THUMB_FAILED.clear();
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
