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
 * 1.20.1 实测差异：{@code FMLPaths} 仍在 {@code net.minecraftforge.fml.loading} 下；
 * {@code ResourceLocation.fromNamespaceAndPath} 已存在，统一改用它（旧的 2 参构造器已标 removal）。
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

    /**
     * 只改内存里的颜色，<b>不落盘</b>。
     * 拖 RGB 滑块时每帧都会调到这里，如果每帧 save() 就是 60 次/秒写 background.json；
     * 落盘统一由「松手」路径（{@code KeyPanelScreen#mouseReleased} / 每帧检测到松开）负责。
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
            // 日志只打文件名：绝对路径会暴露本机用户名与目录结构
            KeyPanelMod.LOGGER.warn("[key_panel] 背景图片不存在：{}", fileName(file));
            releaseImage();
            return;
        }
        // 这张图现在有原图贴图了，缩略图那份可以放掉（同一文件不再同时占两份显存）
        ResourceLocation thumb = THUMB_IDS.remove(image);
        if (thumb != null) {
            THUMB_SIZE.remove(image);
            Minecraft.getInstance().getTextureManager().release(thumb);
        }
        try {
            var nativeImage = readImageFull(file);
            releaseImage();
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(KeyPanelMod.MODID,
                    "backgrounds/" + image);
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

    /** 只取文件名，用来打日志（不打本机绝对路径）。 */
    private static String fileName(Path file) {
        Path name = file.getFileName();
        return name == null ? "?" : name.toString();
    }

    /**
     * 读原图（面板背景要用它自己的分辨率算 UV 裁切，不能改尺寸）：PNG 交给原版读，
     * 失败或本来就是 jpg / jpeg / 其它格式时再走 AWT 解码后转成 NativeImage。
     * <p>
     * 流一律 try-with-resources：{@code ImageIO.read} 留下未关闭的流会在 Windows 上锁住图片文件。
     * <p>
     * 1.20.1 实测签名与 1.21.1 相同：{@code NativeImage.read(InputStream)}、
     * {@code new NativeImage(Format.RGBA, w, h, false)}、{@code setPixelRGBA(x, y, abgr)}、
     * {@code DynamicTexture(NativeImage)}、{@code TextureManager.register/release}。
     */
    private static com.mojang.blaze3d.platform.NativeImage readImageFull(Path file) throws IOException {
        String low = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (low.endsWith(".png")) {
            try (InputStream in = Files.newInputStream(file)) {
                return com.mojang.blaze3d.platform.NativeImage.read(in);
            } catch (IOException | RuntimeException e) {
                KeyPanelMod.LOGGER.warn("[key_panel] PNG 直读失败，改用通用解码：{}", e.toString());
            }
        }
        java.awt.image.BufferedImage img = decode(file);
        int w = img.getWidth();
        int h = img.getHeight();
        var out = new com.mojang.blaze3d.platform.NativeImage(
                com.mojang.blaze3d.platform.NativeImage.Format.RGBA, w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setPixelRGBA(x, y, argbToAbgr(img.getRGB(x, y)));
            }
        }
        return out;
    }

    /**
     * AWT 解码：优先让 ImageIO 在解码阶段直接按比例抽样（大图不必先在堆里铺一份满尺寸位图），
     * 解码器不支持抽样参数时退回整图解码。流同样 try-with-resources。
     */
    private static java.awt.image.BufferedImage decode(Path file) throws IOException {
        try {
            var iis = javax.imageio.ImageIO.createImageInputStream(file.toFile());
            if (iis != null) {
                try (iis) {
                    var readers = javax.imageio.ImageIO.getImageReaders(iis);
                    if (readers.hasNext()) {
                        var reader = readers.next();
                        try {
                            reader.setInput(iis, true, true);
                            int w = reader.getWidth(0);
                            int h = reader.getHeight(0);
                            if (w > 0 && h > 0) {
                                int step = Math.max(1, Math.max((w + THUMB_MAX - 1) / THUMB_MAX,
                                        (h + THUMB_MAX - 1) / THUMB_MAX));
                                if (step > 1) {
                                    var param = reader.getDefaultReadParam();
                                    param.setSourceSubsampling(step, step, 0, 0);
                                    java.awt.image.BufferedImage sub = reader.read(0, param);
                                    if (sub != null) {
                                        return sub;
                                    }
                                }
                            }
                        } finally {
                            reader.dispose();
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            KeyPanelMod.LOGGER.warn("[key_panel] 抽样解码不可用，改用整图解码：{}（{}）", fileName(file), e.toString());
        }
        try (InputStream raw = Files.newInputStream(file)) {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(raw);
            if (img == null) {
                throw new IOException(Component.translatable("key_panel.bg.decode_failed").getString());
            }
            return img;
        }
    }

    /** ARGB → NativeImage 的 ABGR 字节序。 */
    private static int argbToAbgr(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    /**
     * 缩略图最长边上限：解码后先降到这个尺寸再上传显存。
     * 原图动辄几千像素，按原始分辨率注册一张就等于常驻几十 MB 显存，而且是每张图一份。
     */
    private static final int THUMB_MAX = 512;

    /** 缩略图贴图缓存：文件名 → 贴图 ID。 */
    private static final java.util.Map<String, ResourceLocation> THUMB_IDS = new java.util.HashMap<>();
    private static final java.util.Map<String, int[]> THUMB_SIZE = new java.util.HashMap<>();
    /** 读不动的图（坏文件 / 不支持的格式）：记下来就不再每帧重试、也不再刷日志。 */
    private static final java.util.Set<String> THUMB_FAILED = new java.util.HashSet<>();

    /** 背景选择界面用的缩略图贴图（按文件名缓存，尺寸已降采样）。 */
    public static ResourceLocation thumbId(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        ResourceLocation cached = THUMB_IDS.get(name);
        if (cached != null) {
            return cached;
        }
        if (THUMB_FAILED.contains(name)) {
            return null;   // 失败缓存：不再重试
        }
        Path file = IMAGE_DIR.resolve(name);
        if (!Files.isRegularFile(file)) {
            THUMB_FAILED.add(name);
            return null;
        }
        try {
            var img = readThumb(file);
            // 稳定 ID：命名空间 + thumbs/ + 文件名。不掺 hashCode（撞了会让两张图共用一个贴图 ID），
            // 也和 backgrounds/ 的原图贴图分属不同路径，同一个文件不会有两份同 ID 的贴图。
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(KeyPanelMod.MODID, "thumbs/" + name);
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(img));
            THUMB_IDS.put(name, id);
            THUMB_SIZE.put(name, new int[]{img.getWidth(), img.getHeight()});
            return id;
        } catch (IOException | RuntimeException e) {
            THUMB_FAILED.add(name);
            KeyPanelMod.LOGGER.warn("[key_panel] 缩略图读取失败：{}（{}）", name, e.toString());
            return null;
        }
    }

    public static int[] thumbSize(String name) {
        int[] s = THUMB_SIZE.get(name);
        return s == null ? new int[]{1, 1} : s;
    }

    /**
     * 释放全部缩略图贴图（背景选择界面关闭时调用）。
     * 不释放的话每张缩略图会一直占着显存直到退出游戏。
     */
    public static void releaseThumbs() {
        var tm = Minecraft.getInstance().getTextureManager();
        for (ResourceLocation id : THUMB_IDS.values()) {
            tm.release(id);
        }
        THUMB_IDS.clear();
        THUMB_SIZE.clear();
    }

    /** 清掉失败缓存：用户往目录里换了新图时，允许再试一次。 */
    public static void clearThumbFailures() {
        THUMB_FAILED.clear();
    }

    /**
     * 读缩略图：解码 → 降到最长边 {@link #THUMB_MAX} → 转 NativeImage。
     * 走 {@link #decode(Path)}（能抽样解码），所以大图不会先在堆里铺满尺寸位图。
     */
    private static com.mojang.blaze3d.platform.NativeImage readThumb(Path file) throws IOException {
        java.awt.image.BufferedImage src = decode(file);
        int sw = src.getWidth();
        int sh = src.getHeight();
        if (sw <= 0 || sh <= 0) {
            throw new IOException(Component.translatable("key_panel.bg.decode_failed").getString());
        }
        int dw = sw;
        int dh = sh;
        if (Math.max(sw, sh) > THUMB_MAX) {
            if (sw >= sh) {
                dw = THUMB_MAX;
                dh = Math.max(1, (int) Math.round(sh * (double) THUMB_MAX / sw));
            } else {
                dh = THUMB_MAX;
                dw = Math.max(1, (int) Math.round(sw * (double) THUMB_MAX / sh));
            }
        }
        int[] srcPixels = src.getRGB(0, 0, sw, sh, null, 0, sw);
        var out = new com.mojang.blaze3d.platform.NativeImage(
                com.mojang.blaze3d.platform.NativeImage.Format.RGBA, dw, dh, false);
        for (int y = 0; y < dh; y++) {
            int sy = Math.min(sh - 1, (int) ((y + 0.5D) * sh / dh));
            int row = sy * sw;
            for (int x = 0; x < dw; x++) {
                int sx = Math.min(sw - 1, (int) ((x + 0.5D) * sw / dw));
                out.setPixelRGBA(x, y, argbToAbgr(srcPixels[row + sx]));
            }
        }
        return out;
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
