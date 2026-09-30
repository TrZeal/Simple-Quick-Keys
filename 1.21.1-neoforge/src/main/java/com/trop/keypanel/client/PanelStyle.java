package com.trop.keypanel.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.trop.keypanel.KeyPanelMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 面板整体外观设置：背景颜色与自定义背景图片。
 * <p>
 * 配置文件 {@code config/key_panel/background.json}，图片放在
 * {@code config/key_panel/backgrounds/}，支持 png / jpg。
 * 颜色 {@code -1} 表示使用默认外观，{@code image} 为空表示不使用图片。
 */
public final class PanelStyle {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path DIR = FMLPaths.CONFIGDIR.get().resolve(KeyPanelMod.MODID);
    private static final Path FILE = DIR.resolve("background.json");
    private static final Path IMAGE_DIR = DIR.resolve("backgrounds");

    /** 缩略图最长边：解码后先降采样再上传显存，避免整张原图进显存（C2）。 */
    private static final int THUMB_MAX_EDGE = 512;

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

    /**
     * 只改内存里的颜色，不落盘（C1）。
     * <p>
     * 拖颜色滑块时每帧都会调用它，落盘交给调用方在松手时显式调用 {@link #save()}。
     */
    public static void setColor(int rgb) {
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
            KeyPanelMod.LOGGER.warn("[key_panel] 背景图片不存在：{}", image);   // C4：只打文件名，不打本机绝对路径
            releaseImage();
            return;
        }
        try (InputStream in = Files.newInputStream(file)) {
            var nativeImage = readImage(file, in);
            releaseImage();
            ResourceLocation id = textureId("backgrounds", image, "_bg");
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(nativeImage));
            loadedId = id;
            loadedName = image;
            loadedW = nativeImage.getWidth();
            loadedH = nativeImage.getHeight();
            // 这张图现在作为整幅背景使用，它的缩略图不再单独留一份（C2②：同一张图只有一份纹理）
            releaseThumb(image);
            KeyPanelMod.LOGGER.info("[key_panel] 背景图片已加载：{}（{}x{}）", image, loadedW, loadedH);
        } catch (IOException | RuntimeException e) {
            KeyPanelMod.LOGGER.warn("[key_panel] 背景图片加载失败：{}（{}）", image, e.toString());
            releaseImage();
        }
    }

    /**
     * 读整幅背景图：PNG 先交给原版读，失败或本来就是 jpg / jpeg / 其它格式时，
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
        return toNativeImage(decodeWithAwt(file));
    }

    /**
     * AWT 通用解码（jpg / jpeg / 坏 PNG 兜底）。
     * 流用 try-with-resources 关掉（C3：漏关在 Windows 下会锁住图片文件，删不掉也换不了）。
     */
    private static java.awt.image.BufferedImage decodeWithAwt(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(in);
            if (img == null) {
                throw new IOException(Component.translatable("key_panel.bg.decode_failed").getString());
            }
            return img;
        }
    }

    /** 缩略图：解码后先降采样到最长边 {@link #THUMB_MAX_EDGE}，再交给显存（C2）。 */
    private static com.mojang.blaze3d.platform.NativeImage readThumbnail(Path file) throws IOException {
        return toNativeImage(downscale(decodeWithAwt(file), THUMB_MAX_EDGE));
    }

    /** 等比降采样（块平均），只缩小不放大；已经够小就原样返回。 */
    private static java.awt.image.BufferedImage downscale(java.awt.image.BufferedImage src, int maxEdge) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        int longest = Math.max(sw, sh);
        if (longest <= maxEdge) {
            return src;
        }
        int dw = Math.max(1, (int) Math.round(sw * (double) maxEdge / longest));
        int dh = Math.max(1, (int) Math.round(sh * (double) maxEdge / longest));
        java.awt.image.BufferedImage dst =
                new java.awt.image.BufferedImage(dw, dh, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[sw * ((sh + dh - 1) / dh + 1)];
        for (int y = 0; y < dh; y++) {
            int y0 = (int) ((long) y * sh / dh);
            int y1 = (int) Math.max(y0 + 1L, (long) (y + 1) * sh / dh);
            int rows = y1 - y0;
            src.getRGB(0, y0, sw, rows, row, 0, sw);
            for (int x = 0; x < dw; x++) {
                int x0 = (int) ((long) x * sw / dw);
                int x1 = (int) Math.max(x0 + 1L, (long) (x + 1) * sw / dw);
                long sa = 0L;
                long sr = 0L;
                long sg = 0L;
                long sb = 0L;
                long n = 0L;
                for (int yy = 0; yy < rows; yy++) {
                    int off = yy * sw;
                    for (int xx = x0; xx < x1; xx++) {
                        int v = row[off + xx];
                        sa += (v >>> 24) & 0xFF;
                        sr += (v >> 16) & 0xFF;
                        sg += (v >> 8) & 0xFF;
                        sb += v & 0xFF;
                        n++;
                    }
                }
                dst.setRGB(x, y, ((int) (sa / n) << 24) | ((int) (sr / n) << 16)
                        | ((int) (sg / n) << 8) | (int) (sb / n));
            }
        }
        return dst;
    }

    /** AWT 图 → NativeImage（RGBA 字节序与原实现保持一致）；逐行取像素，不为整图再开一份 int[]。 */
    private static com.mojang.blaze3d.platform.NativeImage toNativeImage(java.awt.image.BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        var out = new com.mojang.blaze3d.platform.NativeImage(
                com.mojang.blaze3d.platform.NativeImage.Format.RGBA, w, h, false);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int v = row[x];
                int a = (v >>> 24) & 0xFF;
                int r = (v >> 16) & 0xFF;
                int g = (v >> 8) & 0xFF;
                int b = v & 0xFF;
                out.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }
        return out;
    }

    private static final Map<String, ResourceLocation> THUMBS = new HashMap<>();
    private static final Map<String, int[]> THUMB_SIZE = new HashMap<>();
    /** 解码失败的图记在这里，不再每帧重试、不再刷日志（C2）。 */
    private static final Set<String> FAILED_THUMBS = new HashSet<>();

    /** 背景选择界面用的缩略图贴图（按文件名缓存；最长边已降采样）。 */
    public static ResourceLocation thumbId(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        // 正在当背景用的那张图直接复用已注册的纹理，不再额外上传一份缩略图（C2②）
        if (name.equals(loadedName) && loadedId != null) {
            return loadedId;
        }
        if (FAILED_THUMBS.contains(name)) {
            return null;
        }
        ResourceLocation cached = THUMBS.get(name);
        if (cached != null) {
            return cached;
        }
        Path file = IMAGE_DIR.resolve(name);
        if (!Files.isRegularFile(file)) {
            FAILED_THUMBS.add(name);
            return null;
        }
        try {
            var img = readThumbnail(file);
            ResourceLocation id = textureId("thumbs", name, "_thumb");
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(img));
            THUMBS.put(name, id);
            THUMB_SIZE.put(name, new int[]{img.getWidth(), img.getHeight()});
            return id;
        } catch (IOException | RuntimeException e) {
            FAILED_THUMBS.add(name);
            KeyPanelMod.LOGGER.warn("[key_panel] 缩略图读取失败：{}（{}）", name, e.toString());
            return null;
        }
    }

    public static int[] thumbSize(String name) {
        if (name != null && name.equals(loadedName) && loadedId != null) {
            return new int[]{Math.max(1, loadedW), Math.max(1, loadedH)};
        }
        int[] s = THUMB_SIZE.get(name);
        return s == null ? new int[]{1, 1} : s;
    }

    /** 释放单张缩略图纹理（它同时作为背景使用时另有一份，不受影响）。 */
    private static void releaseThumb(String name) {
        ResourceLocation id = THUMBS.remove(name);
        THUMB_SIZE.remove(name);
        if (id != null && !id.equals(loadedId)) {
            Minecraft.getInstance().getTextureManager().release(id);
        }
    }

    /** 释放全部缩略图纹理（C2：离开背景选择界面时调用，避免显存只增不减）。 */
    public static void releaseThumbs() {
        for (ResourceLocation id : THUMBS.values()) {
            if (!id.equals(loadedId)) {
                Minecraft.getInstance().getTextureManager().release(id);
            }
        }
        THUMBS.clear();
        THUMB_SIZE.clear();
    }

    /** 重新扫描图片目录（点「刷新」）时清掉失败记录，给用户一次重试机会。 */
    public static void clearFailedThumbs() {
        FAILED_THUMBS.clear();
    }

    /**
     * 稳定的纹理 ID：文件名净化 + FNV-1a 64 位散列。
     * 不用 {@code String.hashCode()}（32 位、有碰撞风险），散列固定，重启游戏也不变。
     */
    private static ResourceLocation textureId(String kind, String fileName, String suffix) {
        String low = fileName.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(low.length() + 32);
        for (int i = 0; i < low.length(); i++) {
            char c = low.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            sb.append(ok ? c : '_');
        }
        sb.append(suffix).append('-').append(Long.toUnsignedString(fnv1a64(fileName), 16));
        return ResourceLocation.fromNamespaceAndPath(KeyPanelMod.MODID, kind + "/" + sb);
    }

    private static long fnv1a64(String s) {
        long h = 0xCBF29CE484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001B3L;
        }
        return h;
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
