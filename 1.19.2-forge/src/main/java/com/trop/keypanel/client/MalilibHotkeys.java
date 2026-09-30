package com.trop.keypanel.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.trop.keypanel.KeyPanelMod;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 方案②：malilib 系模组（Tweakeroo / MiniHUD / Litematica 等）热键直连。
 *
 * <p>malilib 的热键不是原版 {@link net.minecraft.client.KeyMapping}，它只登记在 malilib 自己的
 * {@code fi.dy.masa.malilib.event.InputEventHandler} 的 hotkeyMap 里，永远不会出现在
 * {@code Options.keyMappings} / {@code KeyMapping.ALL}，所以这里整个走反射做<b>软依赖</b>：
 * 不写进 build.gradle 依赖、不 import fi.dy.masa.*，malilib 不在时静默降级。</p>
 *
 * <p>枚举入口（与 1.21.1 版逐行同源；malilib 的 {@code hotkeys} / {@code event} 两个包在
 * 1.19.x ~ 1.21.x 各分支上签名一致，本平台没有 malilib 的 jar 可 javap，故这里读的是
 * 1.21.1 分支 {@code pre-rewrite/fabric/1.21.1-masa} 的源码；三条通路任一解析不到都会各自
 * 安全降级，不会因为没有 malilib 而崩）：
 * {@code InputEventHandler.getKeybindManager()} → {@code IKeybindManager#getKeybindCategories()}
 * → {@code KeybindCategory#getModName() / getCategory() / getHotkeys()} → {@code IHotkey#getKeybind()}
 * → {@code IKeybind#getKeys()}。键码约定见 malilib {@code util/KeyCodes}：
 * 键盘 = GLFW 键码（&gt;= 0），鼠标 = 按钮号 - 100（&lt; 0，即 MOUSE_BUTTON_n = GLFW 按钮号 - 100）。</p>
 *
 * <p>枚举结果缓存：{@link Entry} 持有存活的 IKeybind 引用，键码在触发时按需重新读取，
 * 因此渲染路径只做 Map 查询、不会每帧反射。</p>
 *
 * <h2>直连触发（{@link #invoke(Entry)}）</h2>
 *
 * <p>malilib（{@code pre-rewrite/fabric/1.21.1-masa} 分支，1.19.x 线同签名）里<b>没有</b>
 * {@code IHotkey#onKeyInput(int, KeyAction)} 这种东西：{@code IHotkey} 只有
 * {@code getKeybind()}（{@code hotkeys/IHotkey.java:9}）。malilib 内部收到按键后走的是：
 * {@code event/InputEventHandler.java:145} {@code KeybindMulti.onKeyInputPre(...)}（public static，维护
 * 静态 {@code PRESSED_KEYS}，{@code hotkeys/KeybindMulti.java:441}）→
 * {@code event/InputEventHandler.java:276} <b>{@code IKeybind#updateIsPressed()}</b> →
 * {@code hotkeys/KeybindMulti.java:159} {@code triggerKeyAction(...)} →
 * {@code hotkeys/KeybindMulti.java:170/185} <b>{@code IHotkeyCallback#onKeyAction(KeyAction, IKeybind)}</b>
 * （{@code hotkeys/IHotkeyCallback.java:11}）。</p>
 *
 * <p>注意 {@code InputEventHandler#checkKeyBindsForChanges}（{@code :266}）会把一个键码
 * <b>分发给绑在该键上的所有 keybind</b>——这正是「伪造按键会连带触发别的功能」的根因，
 * 所以这里不用 {@code InputEventHandler#onKeyInput}，而是对<b>目标热键自己的那个 IKeybind</b>
 * 调 {@code updateIsPressed()}；键码状态只用 {@code KeybindMulti.onKeyInputPre} 注入，且调用前后
 * 严格配对（PRESS 注入 → 触发 → RELEASE 注入），不把状态留在 malilib 里，也不改用户配置。</p>
 *
 * <p>两条直连通路（按序尝试，任一失败就退到下一条，全失败由调用方回退「发原始按键事件」）：</p>
 * <ol>
 *   <li>{@code STATE}：{@code KeybindMulti.onKeyInputPre} + {@code IKeybind#updateIsPressed()}——
 *       完全走 malilib 自己的状态机，会尊重 activateOn(PRESS/RELEASE/BOTH)、
 *       {@code KeybindSettings.Context}、exclusive 与「忽略键」等设置，回调里
 *       {@code key.isKeybindHeld()} 也是真的。<b>「状态机收下了吗」看注入的键码在不在 malilib 的
 *       按下键码集合里，不看 {@code isPressed()} 的边沿语义</b>（见 {@link #invokeState}）；</li>
 *   <li>{@code CALLBACK}：{@code KeybindMulti#getCallback()}（{@code KeybindMulti.java:557}，public，
 *       但不在 IKeybind 接口上）+ {@code IHotkeyCallback#onKeyAction(KeyAction, IKeybind)}——
 *       状态机把这次按键挡下（例如被 malilib 的忽略键/上下文过滤）时兜底，按
 *       {@code KeybindSettings#getActivateOn()}（{@code KeybindSettings.java:64}）只发对应的动作。</li>
 * </ol>
 *
 * <p>两条通路<b>互斥</b>：一次 {@link #invoke(Entry)} 只会走其中一条，且回调一旦被调用过（哪怕它自己
 * 抛异常）就按「已触发」处理，绝不回退到「发原始按键」（那会经原版输入入口再触发一次）——
 * 同一次面板点击里同一个热键最多触发一次。</p>
 */
final class MalilibHotkeys {

    private static final String CLS_EVENT_HANDLER = "fi.dy.masa.malilib.event.InputEventHandler";
    private static final String CLS_MANAGER = "fi.dy.masa.malilib.hotkeys.IKeybindManager";
    private static final String CLS_CATEGORY = "fi.dy.masa.malilib.hotkeys.KeybindCategory";
    private static final String CLS_HOTKEY = "fi.dy.masa.malilib.hotkeys.IHotkey";
    private static final String CLS_KEYBIND = "fi.dy.masa.malilib.hotkeys.IKeybind";
    private static final String CLS_KEYBIND_IMPL = "fi.dy.masa.malilib.hotkeys.KeybindMulti";
    private static final String CLS_KEY_ACTION = "fi.dy.masa.malilib.hotkeys.KeyAction";
    private static final String CLS_CALLBACK = "fi.dy.masa.malilib.hotkeys.IHotkeyCallback";
    private static final String CLS_SETTINGS = "fi.dy.masa.malilib.hotkeys.KeybindSettings";

    /** 单个热键最多接受多少个键码（正常组合键 2~3 个，这里只做防御性上限）。 */
    private static final int MAX_KEYS = 16;

    /**
     * malilib 全局「当前按下的键码」集合的候选字段名（{@code KeybindMulti} 上的静态字段，
     * 真实 malilib 里是 {@code List<Integer>}，桩里也是 List，所以按 {@code Collection} 读）。
     * 只用于两处增强：判定一次注入有没有被 malilib 收下（见 {@link #injectionLanded(List)}），
     * 以及组合键 RELEASE 时别把玩家<b>物理按住</b>的键一起清掉（见
     * {@link #releaseInjected(List, Set)}）。读不到这些字段时两条增强各自安全降级，
     * 不影响主流程。
     */
    private static final String[] PRESSED_KEYS_FIELDS = {"PRESSED_KEYS", "pressedKeys"};

    /** {@link #invoke(Entry)} 的结果。 */
    enum Trigger {
        /** 已走 malilib 自己的按键状态机（{@code IKeybind#updateIsPressed()}）触发。 */
        STATE,
        /** 已直接调用该热键的 {@code IHotkeyCallback#onKeyAction}。 */
        CALLBACK,
        /** 直连能力在，但该热键没有回调（例如只在 tick 里轮询 isKeybindHeld 的功能），什么都没触发。 */
        NO_CALLBACK,
        /** 两条直连都不可用或失败：调用方应回退到「发原始按键事件」。 */
        UNAVAILABLE
    }

    /** 状态机通路（{@link #invokeState}）的结果。 */
    private enum StateResult {
        /** 状态机已处理（回调已按 activateOn 被调用，或已经进到回调里才抛异常）。 */
        HANDLED,
        /** 状态机没把这次按键算作按下（被 malilib 的忽略键/上下文挡下）→ 交给回调通路兜底。 */
        NOT_REGISTERED,
        /** 注入键码阶段就失败了，还没碰过回调 → 安全地交给回调通路。 */
        FAILED
    }

    /** 一个可选的 malilib 热键条目。除 {@link #keys()} 外全部是快照，渲染时不再反射。 */
    static final class Entry {
        /**
         * 持久化用的稳定 ID：{@code 模组名|配置名}，两段都做过 {@link #stripFormatting(String)}
         * （malilib 的配置名可能带 {@code §6...§r} 之类的颜色码，不能写进配置）。
         */
        final String id;
        /** 没去格式码的原始 ID（{@code 模组名|getName()}），只用于兼容旧配置里已经写下的值。 */
        final String legacyId;
        final String modName;
        /** 分组原始返回串（malilib 已翻译；没翻译时就是原始翻译键）。 */
        final String category;
        /**
         * 分类的可读名：翻译键一律走 {@link #humanize(String)}（绝不把
         * {@code tweakeroo.hotkeys.category.generic} 这种原始键摆到界面上），
         * 拿不到可读词时退回模组名。
         */
        final String categoryPlain;
        /** 配置名（未翻译，稳定）。 */
        final String name;
        /**
         * 界面显示名。取值链：malilib 的 {@code getConfigGuiDisplayName()}（会走翻译，且必须不是
         * 没翻出来的原始键）→ 去格式码的 {@code getName()} → {@code 模组名|配置名} 兜底。
         */
        final String displayName;
        /** 显示名去掉格式码后的样子：EditBox 不解析 {@code §}，只读展示要用这个。 */
        final String displayPlain;
        /** 快照时刻的键码显示串，例如 {@code T} / {@code Left Shift + T}。 */
        final String keysDisplay;
        /** 快照时刻是否全部是键盘键码（含鼠标键码的热键本模组发不出去）。 */
        final boolean keyboardOnly;
        /**
         * 同一个来源（{@code modName}）下有多个分类时为 true：卡片/悬停/编辑界面用
         * 「分类 · 功能名」区分，否则只显示功能名。枚举完统一算，见 {@link #build()}。
         */
        boolean categoryPrefix;

        private final Object keybind;
        private final List<Integer> keysSnapshot;

        private Entry(String id, String legacyId, String modName, String category, String categoryPlain,
                      String name, String displayName, String displayPlain, String keysDisplay,
                      boolean keyboardOnly, Object keybind, List<Integer> keysSnapshot) {
            this.id = id;
            this.legacyId = legacyId;
            this.modName = modName;
            this.category = category;
            this.categoryPlain = categoryPlain;
            this.name = name;
            this.displayName = displayName;
            this.displayPlain = displayPlain;
            this.keysDisplay = keysDisplay;
            this.keyboardOnly = keyboardOnly;
            this.keybind = keybind;
            this.keysSnapshot = keysSnapshot;
        }

        /**
         * 当前键码（触发时实时读取，用户刚改过绑定时也能拿到新值）。
         *
         * <p><b>「读失败」与「确实为空」严格分开</b>：只有 {@code getKeys()} 抛异常/方法缺失（= 读失败）
         * 才退回枚举时的快照；读成功但表是空的（用户在 malilib 里清掉了这个热键的绑定）就如实返回空表 ——
         * 否则回退路径 {@code sendSequence} 会把<b>旧键码</b>真的发给原版输入入口，
         * 连带触发绑在那些键码上的其它功能（1.2.0 修的回归）。</p>
         */
        List<Integer> keys() {
            List<Integer> live = readKeysOrNull(this.keybind);
            if (live != null) {
                return live;
            }
            return this.keysSnapshot;
        }

        /**
         * 该条目持有的 malilib {@code IKeybind} 引用（malilib 缺失时为 null）。
         *
         * <p><b>仅供离线验证脚手架使用</b>（{@code build/verify/harness/.../VerifyHarness} 拿它把
         * {@code sendSequence} 的字符串序列记到桩上），模组源码里零调用。保留是为了让
         * 「sendSequence 的 PRESS/RELEASE 顺序」这类断言不必另外造一条枚举通路。</p>
         */
        Object keybindObject() {
            return this.keybind;
        }
    }

    private static boolean built;
    private static boolean failed;
    private static boolean warned;
    /** 可选显示兜底方法缺失的 warn（与 {@link #warned} 分开，互不吞日志）。 */
    private static boolean optionalWarned;
    private static boolean present;
    private static List<Entry> cache = Collections.emptyList();
    /** 规范化 ID → 条目（新写入的 ID 走这张表）。 */
    private static final Map<String, Entry> BY_ID = new HashMap<>();
    /** 旧配置里已经写下的、带格式码的原样 ID → 条目。 */
    private static final Map<String, Entry> BY_LEGACY_ID = new HashMap<>();
    /** 归一化（去格式码/小写）ID → 条目，兼容匹配用。 */
    private static final Map<String, Entry> BY_NORM = new HashMap<>();
    /** 归一化「模组名|显示名」→ 条目，旧版本可能存过显示名时的兜底。 */
    private static final Map<String, Entry> BY_DISPLAY = new HashMap<>();

    private static Class<?> clsCategory;
    private static Class<?> clsHotkey;
    private static Class<?> clsKeybind;
    private static Method mGetKeybindManager;
    private static Method mGetKeybindCategories;
    private static Method mGetModName;
    private static Method mGetCategory;
    private static Method mGetHotkeys;
    private static Method mGetKeybind;
    private static Method mGetName;
    private static Method mGetGuiDisplayName;
    private static Method mGetKeys;
    private static Method mGetKeysDisplay;

    // ===== 直连触发用（与枚举用的反射分开解析：直连失败不影响枚举，反之亦然） =====

    private static boolean directResolved;
    private static boolean directWarned;
    /** 「读不到 malilib 按下键码集合」的 warn（只记一条）。 */
    private static boolean pressedSetWarned;
    /** 「读某个热键的键码失败、退回快照」的 warn（只记一条）。 */
    private static boolean readKeysWarned;
    /** 通路 1：{@code KeybindMulti#onKeyInputPre} + {@code IKeybind#updateIsPressed}。 */
    private static boolean directState;
    /** 通路 2：{@code KeybindMulti#getCallback} + {@code IHotkeyCallback#onKeyAction}。 */
    private static boolean directCallback;
    private static Method mOnKeyInputPre;
    private static Method mUpdateIsPressed;
    private static Method mGetSettings;
    private static Method mGetActivateOn;
    private static Method mGetCallbackImpl;
    private static Method mOnKeyAction;
    private static Object actionPress;
    private static Object actionRelease;
    private static Object actionBoth;
    /** {@code KeybindMulti} 上的静态「当前按下的键码」集合（malilib 的全局按键状态）；读不到为 null。 */
    private static java.lang.reflect.Field fPressedKeys;

    private MalilibHotkeys() {
    }

    /**
     * malilib 系模组的类是否可用（与「枚举到多少热键」无关）。
     *
     * <p><b>仅供离线验证脚手架使用</b>（{@code build/verify} 的 present/broken/absent 三种 classpath
     * 断言「类在 / 类在但反射炸 / 类不在」），模组源码里零调用 —— 界面侧的直连能力判断走
     * {@link #directAvailable()}。保留是为了让「malilib 缺失时静默降级」这条不变量有独立的探针。</p>
     */
    static boolean present() {
        ensure();
        return present;
    }

    /** 缓存的热键列表；malilib 不在或反射失败时返回空表。 */
    static List<Entry> entries() {
        ensure();
        return cache;
    }

    /**
     * 按持久化 ID 找热键；找不到返回 null（不抛异常）。
     *
     * <p>三级兼容，保证旧配置里的 ID 改了规则也还能命中：</p>
     * <ol>
     *   <li>规范化 ID（新写法，{@code 模组名|配置名}，无格式码）精确匹配；</li>
     *   <li>旧配置里写下的原样 ID（例如 {@code Tweakeroo|§6tweakInventoryPreview§r}）精确匹配；</li>
     *   <li>把查询串去掉格式码/首尾空白后再匹配一次（大小写也放宽），并兜底按显示名匹配
     *       （旧版本可能存过显示名）。</li>
     * </ol>
     */
    static Entry byId(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        ensure();
        Entry hit = BY_ID.get(id);
        if (hit != null) {
            return hit;
        }
        hit = BY_LEGACY_ID.get(id);
        if (hit != null) {
            return hit;
        }
        String norm = normalizeKey(id);
        hit = BY_NORM.get(norm);
        if (hit != null) {
            return hit;
        }
        return BY_DISPLAY.get(norm);
    }

    /**
     * 去掉 Minecraft 格式化代码（{@code §} + 后一个字符）与首尾空白。
     * malilib 的配置名/显示名会带 {@code §6...§r}，写进配置的 ID 必须先把它们剥掉。
     */
    static String stripFormatting(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u00a7') {
                i++;   // 跳过格式码后面的那个字符
                continue;
            }
            sb.append(c);
        }
        return sb.toString().trim();
    }

    /** ID 归一化：去格式码 + 去空白 + 转小写，用于兼容匹配。 */
    private static String normalizeKey(String text) {
        return stripFormatting(text).toLowerCase(Locale.ROOT);
    }

    /**
     * 卡片/悬停/编辑界面用的可读名：同一来源下有多个分类时是
     * {@code 分类 · 功能名}，否则只有功能名。
     */
    static String labelOf(Entry entry) {
        if (entry == null) {
            return "";
        }
        if (entry.categoryPrefix && !entry.categoryPlain.isEmpty()) {
            return entry.categoryPlain + " · " + entry.displayName;
        }
        return entry.displayName;
    }

    /** 渲染路径用的显示名查询（纯 Map 查表，不做反射）：找不到返回空串。 */
    static String displayNameOf(String id) {
        return labelOf(byId(id));
    }

    /**
     * 编辑界面只读展示用：{@code 功能名 (分类 · 模组名)}，已去掉格式码（EditBox 不解析 {@code §}）；
     * 找不到该 ID 时返回空串，由调用方决定显示什么。
     *
     * <p>功能名放最前面：只读框宽度有限、可能被截断，被截掉的应该是后面的上下文，
     * 不能把功能名本身截没（卡片那边才用「分类 · 功能名」的短标签）。</p>
     */
    static String plainSummaryOf(String id) {
        Entry e = byId(id);
        if (e == null) {
            return "";
        }
        String name = e.displayPlain.isEmpty() ? stripFormatting(e.name) : e.displayPlain;
        List<String> context = new ArrayList<>(2);
        if (e.categoryPrefix && !e.categoryPlain.isEmpty()) {
            context.add(e.categoryPlain);
        }
        String mod = stripFormatting(e.modName);
        if (!mod.isEmpty()) {
            context.add(mod);
        }
        return context.isEmpty() ? name : name + " (" + String.join(" · ", context) + ")";
    }

    /** 渲染路径用的完整描述：{@code 模组 / 友好分类 / 功能名 (键码)}。 */
    static String summaryOf(String id) {
        Entry e = byId(id);
        if (e == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(64);
        sb.append(e.modName);
        if (!e.categoryPlain.isEmpty()) {
            sb.append(" / ").append(e.categoryPlain);
        }
        sb.append(" / ").append(e.displayName);
        if (!e.keysDisplay.isEmpty()) {
            sb.append(" (").append(e.keysDisplay).append(')');
        }
        return sb.toString();
    }

    /** 发键回调：{@code (键码, GLFW action)}。 */
    interface KeySink {
        void send(int keyCode, int action);
    }

    /**
     * 组合键按序列发送：先按顺序全部 PRESS，再逆序 RELEASE。
     * 与 malilib {@code KeybindMulti} 的状态机对齐——它要求组合键的所有键同时出现在 PRESSED_KEYS 里
     * 才算「按下」，所以不能一个键 PRESS+RELEASE 交替发。
     *
     * @return 实际按下的键数
     */
    static int sendSequence(List<Integer> keys, KeySink sink) {
        int count = 0;
        for (int code : keys) {
            sink.send(code, org.lwjgl.glfw.GLFW.GLFW_PRESS);
            count++;
        }
        for (int i = keys.size() - 1; i >= 0; i--) {
            sink.send(keys.get(i), org.lwjgl.glfw.GLFW.GLFW_RELEASE);
        }
        return count;
    }

    /** 强制重建缓存（选择界面打开时调用一次；不放在 render 里）。 */
    static void refresh() {
        built = false;
        failed = false;
        build();
    }

    // ===================== 直连触发 =====================

    /**
     * 是否具备直连能力（与具体热键无关）：malilib 缺失或两个入口都反射不到时为 false。
     * 结果只解析一次，渲染路径可以安全调用（第一次之后就是纯字段读）。
     */
    static boolean directAvailable() {
        ensure();
        resolveDirect();
        return directState || directCallback;
    }

    /**
     * 直连触发某个已枚举热键：只碰<b>这一个</b> IKeybind，因此不会连带触发绑同一键码的其它功能，
     * 也不依赖窗口/输入状态。全程只读用户配置，不调用任何会写配置的方法。
     *
     * <p>顺序（由调用方保证已经关掉界面，让 malilib 的 INGAME 上下文成立）：</p>
     * <ol>
     *   <li>有键码时先走状态机通路；</li>
     *   <li>状态机没算作按下（或注入阶段就失败）时走回调通路；</li>
     *   <li>两条都不行才返回 {@link Trigger#UNAVAILABLE}，由调用方回退「发原始按键事件」。</li>
     * </ol>
     *
     * <p>动作选择：一个「瞬按」在 malilib 状态机里就是 PRESS 边沿 + RELEASE 边沿，
     * 由 {@code KeybindSettings#getActivateOn()} 决定回调拿到哪一个——
     * {@code PRESS} 型只收到 PRESS，{@code RELEASE} 型只收到 RELEASE，{@code BOTH} 型两个都收到。
     * 状态机通路天然如此；回调通路自己按同一个设置复制该语义。</p>
     */
    static Trigger invoke(Entry entry) {
        if (entry == null) {
            return Trigger.UNAVAILABLE;
        }
        ensure();
        resolveDirect();
        Object keybind = entry.keybind;
        if (keybind == null || (!directState && !directCallback)) {
            return Trigger.UNAVAILABLE;
        }
        List<Integer> keys = entry.keys();
        // 空键码的热键（malilib 的 allowEmpty）状态机无能为力，直接交给回调通路
        if (directState && !keys.isEmpty() && invokeState(keybind, keys) == StateResult.HANDLED) {
            // 状态机已经按 settle 过这次按键；但如果这个热键压根没有回调，等于什么都不会发生，
            // 如实报 NO_CALLBACK（仍然不回退发原始按键——那正是「连带触发别的功能」的来源）
            return callbackMissing(keybind) ? Trigger.NO_CALLBACK : Trigger.STATE;
        }
        if (directCallback) {
            return invokeCallback(keybind);
        }
        return Trigger.UNAVAILABLE;
    }

    /**
     * 能否确定这个热键<b>没有</b>回调？探测不了（通路 2 不可用 / 取回调失败）时返回 false，
     * 也就是当成「有回调」，避免把正常触发误报成 NO_CALLBACK。
     */
    private static boolean callbackMissing(Object keybind) {
        if (!directCallback) {
            return false;
        }
        Method getter = callbackGetterFor(keybind);
        if (getter == null) {
            return false;
        }
        try {
            return getter.invoke(keybind) == null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 通路 1：照 malilib 自己的顺序来——先 {@code KeybindMulti.onKeyInputPre} 注入键码
     * （等价于 {@code InputEventHandler#onKeyInput} 内部做的第一步），再对目标 keybind 调
     * {@code IKeybind#updateIsPressed()}（等价于 {@code checkKeyBindsForChanges} 内部的那一步，
     * 但只对一个 keybind）。finally 里把这次注入的键码放掉，异常也不会把状态留在 malilib 里。
     *
     * <p><b>1.2.0 起不再用 {@code IKeybind#isPressed()} 判定「状态机收下了吗」</b>：
     * {@code isPressed()} 在真实 malilib 里是<b>按下边沿</b>谓词
     * （{@code pressed && !pressedLast && heldTime == 0}），而它是在 {@code updateIsPressed()} 之后才读的
     * ——边沿早被这次调用消费掉了，恒为 false，于是每次都会误判成 NOT_REGISTERED 再走一遍回调通路，
     * PRESS/BOTH 型热键就会<b>触发两次</b>。这里改成看「注入的键码有没有被 malilib 收进它的
     * 按下键码集合」（{@link #injectionLanded(List)}），与边沿语义无关：</p>
     * <ul>
     *   <li>收下了（键码在集合里）→ 状态机已消化这次按键，返回 {@link StateResult#HANDLED}，
     *       绝不再走回调通路（同一个热键一次点击最多触发一次）；</li>
     *   <li>没被收下（malilib 的忽略键过滤 / 注入没生效）→ {@link StateResult#NOT_REGISTERED}，
     *       按设计交给回调通路兜底，仍然只触发一次；</li>
     *   <li>集合读不到（malilib 换了字段名/可见性）→ 保守当成「已收下」，宁可少触发也不重复触发。</li>
     * </ul>
     *
     * <p><b>取舍</b>：这个判据只回答「注入有没有被 malilib 收下」，不回答「回调有没有真的被调用」
     * （后者在反射下不可观测）。所以当按下集合里确实有这些键码、但状态机又因为别的设置没触发
     * （例如 {@code allowExtraKeys=false} 且玩家同时按着组合键以外的键）时，本次会<b>少触发</b>一次，
     * 而不是退到回调通路冒重复触发的风险 —— 「最多一次」是 1.2.0 的硬要求。</p>
     *
     * <p><b>RELEASE 只放本次真正注入的键码</b>：注入前先记下哪些键码<b>已经在</b> malilib 的按下集合里
     * （= 玩家物理按住的键），结束时跳过它们，见 {@link #releaseInjected(List, java.util.Set)}。</p>
     */
    private static StateResult invokeState(Object keybind, List<Integer> keys) {
        // 注入前玩家已按住的键（可能是 null：读不到按下集合 → 退回「全部释放」的老行为）
        Set<Integer> preexisting = pressedKeysCopy();
        boolean callbackTouched = false;
        boolean landed = true;
        try {
            injectKeys(keys, org.lwjgl.glfw.GLFW.GLFW_PRESS);
            landed = injectionLanded(keys);
            if (landed) {
                // 下一行就会进 malilib 的状态机（可能调用用户回调）：
                // 之后即使抛异常也不再走通路 2，避免重复触发
                callbackTouched = true;
                mUpdateIsPressed.invoke(keybind);
            }
        } catch (Throwable t) {
            warnDirectOnce(t, callbackTouched ? "调用该热键回调时抛异常（已按已触发处理，不重复触发）"
                                              : "注入键码失败，改走回调直连");
            return callbackTouched ? StateResult.HANDLED : StateResult.FAILED;
        } finally {
            releaseInjected(keys, preexisting);
        }
        if (!landed) {
            return StateResult.NOT_REGISTERED;
        }
        try {
            // RELEASE 边沿：activateOn 为 RELEASE/BOTH 的热键在这一步拿到回调
            mUpdateIsPressed.invoke(keybind);
        } catch (Throwable t) {
            warnDirectOnce(t, "调用该热键回调（RELEASE 边沿）时抛异常（已按已触发处理）");
        }
        return StateResult.HANDLED;
    }

    /** 通路 2：直接调用该热键自己的回调 {@code IHotkeyCallback#onKeyAction}。 */
    private static Trigger invokeCallback(Object keybind) {
        Method getter = callbackGetterFor(keybind);
        if (getter == null) {
            return Trigger.UNAVAILABLE;
        }
        Object callback;
        try {
            callback = getter.invoke(keybind);
        } catch (Throwable t) {
            warnDirectOnce(t, "取不到该热键的回调，回退模拟按键");
            return Trigger.UNAVAILABLE;
        }
        if (callback == null) {
            // 没有回调的热键（典型：只在 tick 里轮询 isKeybindHeld 的按住型功能）没有可直连的功能，
            // 这里不回退发原始按键——那正是「连带触发别的功能」的来源
            return Trigger.NO_CALLBACK;
        }
        // 已经进过回调（哪怕回调自己抛异常）就不再报 UNAVAILABLE：否则调用方会回退「发原始按键」，
        // 而那条路会经原版输入入口再触发一次，变成重复触发。
        boolean invoked = false;
        try {
            Object activateOn = readActivateOn(keybind);
            if (activateOn != actionRelease) {
                invoked = true;
                mOnKeyAction.invoke(callback, actionPress, keybind);
            }
            if (activateOn == actionRelease || activateOn == actionBoth) {
                invoked = true;
                mOnKeyAction.invoke(callback, actionRelease, keybind);
            }
            return Trigger.CALLBACK;
        } catch (Throwable t) {
            warnDirectOnce(t, invoked ? "直连回调抛异常（已按已触发处理，不重复触发）"
                                      : "直连回调调用失败，回退模拟按键");
            return invoked ? Trigger.CALLBACK : Trigger.UNAVAILABLE;
        }
    }

    /** 按 malilib 的约定注入一个键码（键盘 = GLFW 键码，鼠标 = 按钮号 - 100）。 */
    private static void injectKeys(List<Integer> keys, int action) throws Exception {
        for (int code : keys) {
            mOnKeyInputPre.invoke(null, code, 0, 0, action);
        }
    }

    /**
     * 把本次注入的键码放掉：<b>只放「注入前不在 malilib 按下集合里」的那些</b>。
     *
     * <p>玩家可能正物理按着组合键里的某个键（例如一直按着 Shift，用面板触发 Shift+U）：
     * 注入 PRESS 之后 malilib 的按下集合里那个键本来就该在，如果我们把它一起 RELEASE 掉，
     * 就等于替玩家松开了他物理按住的键 —— 该键绑定的其它功能会得到一次假松开，
     * 「按住 Shift 疾跑」这类状态也会被打断。{@code preexisting == null}（读不到按下集合）
     * 时退回老行为（全部释放，只影响组合键里被玩家同时按住的键）。</p>
     */
    private static void releaseInjected(List<Integer> keys, Set<Integer> preexisting) {
        for (int i = keys.size() - 1; i >= 0; i--) {
            int code = keys.get(i);
            if (preexisting != null && preexisting.contains(code)) {
                continue;   // 玩家物理按住的键：不是我们按下去的，也不该由我们松开
            }
            try {
                mOnKeyInputPre.invoke(null, code, 0, 0, org.lwjgl.glfw.GLFW.GLFW_RELEASE);
            } catch (Throwable ignored) {
                // RELEASE 注入失败只可能让键码留在 PRESSED_KEYS 里；下一次真实按键或
                // malilib 每 tick 的 reCheckPressedKeys()（GLFW 复核）会把它清掉
            }
        }
    }

    /**
     * 这次注入被 malilib 收下了吗？判据是「注入的键码都在 malilib 的按下集合里」，
     * <b>与 {@code isPressed()} 的边沿语义无关</b>（这正是 1.1.7 的重复触发根因）。
     *
     * <p>读不到那个集合时返回 true（保守：当成已收下 → 不回退回调通路），
     * 保证「同一个热键一次点击最多触发一次」这条不变量在最坏情况下也成立。</p>
     */
    private static boolean injectionLanded(List<Integer> keys) {
        java.util.Collection<Integer> pressed = pressedKeysView();
        if (pressed == null) {
            return true;
        }
        for (int code : keys) {
            if (!pressed.contains(code)) {
                return false;
            }
        }
        return true;
    }

    /**
     * malilib 全局按下键码集合的实时视图（只读用，真实 malilib 里是 {@code List<Integer>}，
     * 所以这里只要求 {@code Collection}）；读不到返回 null。
     */
    @SuppressWarnings("unchecked")
    private static java.util.Collection<Integer> pressedKeysView() {
        if (fPressedKeys == null) {
            return null;
        }
        try {
            Object value = fPressedKeys.get(null);
            return value instanceof java.util.Collection<?> collection ? (java.util.Collection<Integer>) collection
                                                                      : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 注入前的按下集合快照（用来判断哪些键是玩家自己按住的）；读不到返回 null。 */
    private static Set<Integer> pressedKeysCopy() {
        java.util.Collection<Integer> view = pressedKeysView();
        return view == null ? null : new java.util.LinkedHashSet<>(view);
    }

    /** 读 {@code KeybindSettings#getActivateOn()}（KeyAction 枚举常量）；拿不到返回 null。 */
    private static Object readActivateOn(Object keybind) {
        if (mGetSettings == null || mGetActivateOn == null) {
            return null;
        }
        try {
            Object settings = mGetSettings.invoke(keybind);
            return settings == null ? null : mGetActivateOn.invoke(settings);
        } catch (Throwable t) {
            warnDirectOnce(t, "读不到该热键的 activateOn，按 PRESS 处理");
            return null;
        }
    }

    /** {@code getCallback()} 不在 IKeybind 接口上，只能从实现类（或运行期类）上取。 */
    private static Method callbackGetterFor(Object keybind) {
        if (mGetCallbackImpl != null && mGetCallbackImpl.getDeclaringClass().isInstance(keybind)) {
            return mGetCallbackImpl;
        }
        try {
            return keybind.getClass().getMethod("getCallback");
        } catch (Throwable t) {
            return null;
        }
    }

    /** 解析直连通路用到的类/方法；malilib 不在时静默保持两条通路都不可用。 */
    private static void resolveDirect() {
        if (directResolved) {
            return;
        }
        directResolved = true;
        if (!present) {
            return;
        }
        try {
            mUpdateIsPressed = clsKeybind.getMethod("updateIsPressed");
            Class<?> impl = Class.forName(CLS_KEYBIND_IMPL);
            mOnKeyInputPre = impl.getMethod("onKeyInputPre", int.class, int.class, int.class, int.class);
            // 增强项（可选）：malilib 的全局按下键码集合。只影响「注入是否收下」的判定与
            // 组合键 RELEASE 的保护范围，读不到就各自保守降级，不算直连失败。
            fPressedKeys = findStaticField(impl, PRESSED_KEYS_FIELDS);
            if (fPressedKeys == null) {
                warnPressedSetOnce();
            }
            directState = true;
        } catch (Throwable t) {
            directState = false;
        }
        try {
            Class<?> impl = Class.forName(CLS_KEYBIND_IMPL);
            Class<?> action = Class.forName(CLS_KEY_ACTION);
            actionPress = action.getField("PRESS").get(null);
            actionRelease = action.getField("RELEASE").get(null);
            actionBoth = action.getField("BOTH").get(null);
            mGetCallbackImpl = impl.getMethod("getCallback");
            mOnKeyAction = Class.forName(CLS_CALLBACK).getMethod("onKeyAction", action, clsKeybind);
            mGetSettings = clsKeybind.getMethod("getSettings");
            mGetActivateOn = Class.forName(CLS_SETTINGS).getMethod("getActivateOn");
            directCallback = true;
        } catch (Throwable t) {
            directCallback = false;
        }
        if (!directState && !directCallback) {
            // malilib 在、但直连入口一个都找不到：这是真的降级（会退回发原始按键），要有一条 warn
            warnDirectOnce(new NoSuchMethodException("malilib direct trigger entry points not found"),
                    "直连入口反射不到，回退模拟按键");
        }
    }

    /**
     * 直连相关的降级日志，只记一条。只打异常自身的类型与消息（绝对路径会被替换成
     * {@code <path>}），不打堆栈、不打本机路径。
     */
    private static void warnDirectOnce(Throwable t, String what) {
        if (directWarned) {
            return;
        }
        directWarned = true;
        try {
            KeyPanelMod.LOGGER.warn("[key_panel] malilib 热键直连降级：{}（{}）", what, describe(t));
        } catch (Throwable ignored) {
            // 连日志都打不出来时也必须保持静默降级，不能把异常抛回界面
        }
    }

    /** 异常的可读描述：剥掉反射外壳，并抹掉可能出现的绝对路径。 */
    private static String describe(Throwable t) {
        Throwable root = t;
        for (int i = 0; i < 3 && root.getCause() != null
                && (root instanceof java.lang.reflect.InvocationTargetException
                    || root instanceof ExceptionInInitializerError); i++) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        String text = root.getClass().getSimpleName() + (msg == null || msg.isEmpty() ? "" : ": " + msg);
        return text.replaceAll("[A-Za-z]:\\\\[^\\s\"']+|/[^\\s\"':;,)]+", "<path>");
    }

    private static void ensure() {
        if (built || failed) {
            return;
        }
        build();
    }

    private static void build() {
        built = true;
        cache = Collections.emptyList();
        clearIndex();
        if (!resolve()) {
            return;
        }
        try {
            List<Entry> out = enumerate();
            out.sort((a, b) -> {
                int v = a.modName.compareToIgnoreCase(b.modName);
                if (v != 0) {
                    return v;
                }
                v = a.category.compareToIgnoreCase(b.category);
                if (v != 0) {
                    return v;
                }
                return a.displayName.compareToIgnoreCase(b.displayName);
            });
            cache = Collections.unmodifiableList(out);
            markCategoryPrefixes(out);
            for (Entry e : out) {
                BY_ID.putIfAbsent(e.id, e);
                BY_LEGACY_ID.putIfAbsent(e.legacyId, e);
                BY_NORM.putIfAbsent(normalizeKey(e.id), e);
                BY_NORM.putIfAbsent(normalizeKey(e.legacyId), e);
                String mod = stripFormatting(e.modName);
                String display = e.displayPlain.isEmpty() ? stripFormatting(e.name) : e.displayPlain;
                if (!mod.isEmpty() && !display.isEmpty()) {
                    BY_DISPLAY.putIfAbsent(normalizeKey(mod + "|" + display), e);
                }
            }
        } catch (Throwable t) {
            // 反射异常一律吞掉降级：绝不因为 malilib 内部结构变化崩掉本模组界面
            cache = Collections.emptyList();
            clearIndex();
            warnOnce(t);
        }
    }

    /** 索引全部清空（重建/降级时用，避免累积旧条目）。 */
    private static void clearIndex() {
        BY_ID.clear();
        BY_LEGACY_ID.clear();
        BY_NORM.clear();
        BY_DISPLAY.clear();
    }

    /**
     * 标记「同一来源下分类不止一个」的条目：这类条目的卡片/悬停/编辑界面展示成
     * {@code 分类 · 功能名}，只有一个分类的来源（左栏已经写着模组名）就只显示功能名，避免啰嗦。
     */
    private static void markCategoryPrefixes(List<Entry> entries) {
        Map<String, java.util.Set<String>> byMod = new HashMap<>();
        for (Entry e : entries) {
            byMod.computeIfAbsent(e.modName, k -> new java.util.HashSet<>()).add(e.categoryPlain);
        }
        for (Entry e : entries) {
            java.util.Set<String> cats = byMod.get(e.modName);
            e.categoryPrefix = cats != null && cats.size() > 1;
        }
    }

    /**
     * 解析入口类/方法，分三档，<b>不再是「10 个方法全有或全无」</b>（1.2.0 修的回归）：
     *
     * <ol>
     *   <li><b>入口类都不在</b>（malilib 没装）：预期路径，静默返回 false；</li>
     *   <li><b>类在、但「必需方法」找不到</b>（枚举链路：InputEventHandler → IKeybindManager →
     *       KeybindCategory → IHotkey → IKeybind#getKeys）：记一条 {@code warnOnce} 再降级成不可用
     *       —— 不能静默，但也不能崩；</li>
     *   <li><b>只缺「可选方法」</b>（{@code IHotkey#getConfigGuiDisplayName} /
     *       {@code IKeybind#getKeysDisplayString}，纯显示兜底）：{@code present} 仍为 true，
     *       枚举与直连照常可用，只有对应那一处显示退回本模组自己的兜底
     *       （显示名用配置名、键码串用 InputConstants 自己算）。</li>
     * </ol>
     *
     * <p>1.1.7 把 10 个 {@code getMethod} 塞进同一个 try：只要显示兜底那两个之一缺失，
     * {@code present} 就是 false，{@link #resolveDirect()} 里 {@code if (!present) return;}
     * 于是<b>直连能力一起失效</b>。1.2.0 起两类方法分开解析，各降各的级。</p>
     */
    private static boolean resolve() {
        if (present) {
            return true;
        }
        if (failed) {
            return false;
        }
        Class<?> eventHandler;
        try {
            eventHandler = Class.forName(CLS_EVENT_HANDLER);
        } catch (Throwable t) {
            // malilib 没装时 ClassNotFoundException 是预期路径：静默降级，不刷日志
            present = false;
            failed = true;
            built = true;
            return false;
        }
        try {
            // ===== 必需方法：枚举链路（触发链路的反射在 resolveDirect 里单独解析）=====
            clsCategory = Class.forName(CLS_CATEGORY);
            clsHotkey = Class.forName(CLS_HOTKEY);
            clsKeybind = Class.forName(CLS_KEYBIND);
            mGetKeybindManager = eventHandler.getMethod("getKeybindManager");
            mGetKeybindCategories = Class.forName(CLS_MANAGER).getMethod("getKeybindCategories");
            mGetModName = clsCategory.getMethod("getModName");
            mGetCategory = clsCategory.getMethod("getCategory");
            mGetHotkeys = clsCategory.getMethod("getHotkeys");
            mGetKeybind = clsHotkey.getMethod("getKeybind");
            mGetName = clsHotkey.getMethod("getName");
            mGetKeys = clsKeybind.getMethod("getKeys");
        } catch (Throwable t) {
            // 类在、必需方法却找不到：malilib 内部结构变了，这是真降级，必须留一条 warn（不静默）
            present = false;
            failed = true;
            built = true;
            warnOnce(t);
            return false;
        }
        // ===== 可选方法：只用于显示兜底，缺了不影响 present / 枚举 / 直连 =====
        mGetGuiDisplayName = optionalMethod(clsHotkey, "getConfigGuiDisplayName");
        mGetKeysDisplay = optionalMethod(clsKeybind, "getKeysDisplayString");
        if (mGetGuiDisplayName == null || mGetKeysDisplay == null) {
            warnOptionalOnce(mGetGuiDisplayName == null
                    ? "IHotkey#getConfigGuiDisplayName（显示名退回配置名）"
                    : "IKeybind#getKeysDisplayString（键码串退回本模组自己算的键名）");
        }
        present = true;
        return true;
    }

    /** 解析一个可选方法；找不到返回 null，调用方按 null 走各自的兜底。 */
    private static Method optionalMethod(Class<?> owner, String name) {
        try {
            return owner.getMethod(name);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 在类上找一个静态字段（先按 public 找，再按声明找并放开访问权）。
     * 找不到返回 null —— 调用方必须能在 null 下安全工作。
     */
    private static java.lang.reflect.Field findStaticField(Class<?> owner, String... names) {
        for (String name : names) {
            try {
                java.lang.reflect.Field f = owner.getField(name);
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    return f;
                }
            } catch (Throwable ignored) {
                // 继续按声明找（private/包内字段）
            }
            try {
                java.lang.reflect.Field f = owner.getDeclaredField(name);
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    return f;
                }
            } catch (Throwable ignored) {
                // 这个名字没有，试下一个
            }
        }
        return null;
    }

    private static List<Entry> enumerate() throws Exception {
        Object manager = mGetKeybindManager.invoke(null);
        if (manager == null) {
            return new ArrayList<>();
        }
        Object categoriesObj = mGetKeybindCategories.invoke(manager);
        if (!(categoriesObj instanceof List<?> categories)) {
            return new ArrayList<>();
        }
        List<Entry> out = new ArrayList<>();
        for (Object category : categories) {
            if (category == null || !clsCategory.isInstance(category)) {
                continue;
            }
            String modName = asString(safeInvoke(mGetModName, category));
            String categoryName = asString(safeInvoke(mGetCategory, category));
            Object hotkeysObj = safeInvoke(mGetHotkeys, category);
            if (!(hotkeysObj instanceof List<?> hotkeys)) {
                continue;
            }
            for (Object hotkey : hotkeys) {
                if (hotkey == null || !clsHotkey.isInstance(hotkey)) {
                    continue;
                }
                String name = asString(safeInvoke(mGetName, hotkey));
                if (name.isEmpty()) {
                    continue;
                }
                Object keybind = safeInvoke(mGetKeybind, hotkey);
                if (keybind == null || !clsKeybind.isInstance(keybind)) {
                    continue;
                }
                List<Integer> keys = readKeys(keybind);
                String rawDisplay = translatedName(hotkey, name);
                String displayName = friendlyName(modName, name, rawDisplay);
                String categoryPlain = friendlyCategory(modName, categoryName);
                String keysDisplay = keysDisplay(keybind, keys);
                boolean keyboardOnly = true;
                for (int code : keys) {
                    if (code < 0) {
                        keyboardOnly = false;
                        break;
                    }
                }
                // ID 两段都去格式码：malilib 的配置名/显示名会带 §6...§r，不能写进配置
                String id = stripFormatting(modName) + "|" + stripFormatting(name);
                String legacyId = modName + "|" + name;
                out.add(new Entry(id, legacyId, modName, categoryName, categoryPlain, name, displayName,
                        stripFormatting(displayName), keysDisplay, keyboardOnly, keybind,
                        Collections.unmodifiableList(keys)));
            }
        }
        return out;
    }

    private static Object safeInvoke(Method method, Object target) {
        if (method == null || target == null) {
            return null;
        }
        try {
            return method.invoke(target);
        } catch (Throwable t) {
            warnOnce(t);
            return null;
        }
    }

    /** 读 IKeybind#getKeys() 的快照版本（枚举时用）：读失败按空表处理，见 {@link #readKeysOrNull(Object)}。 */
    private static List<Integer> readKeys(Object keybind) {
        List<Integer> out = readKeysOrNull(keybind);
        return out == null ? new ArrayList<>(0) : out;
    }

    /**
     * 读 {@code IKeybind#getKeys()}：
     * <ul>
     *   <li>读成功 → 返回键码表（<b>可能是空表</b>：malilib 的 allowEmpty 或用户刚清掉绑定）；</li>
     *   <li>读失败（方法缺失 / 反射抛异常 / 返回的不是 List）→ 返回 {@code null}。</li>
     * </ul>
     * 两者必须区分：{@link Entry#keys()} 只在读失败时才允许退回枚举快照。
     */
    private static List<Integer> readKeysOrNull(Object keybind) {
        if (mGetKeys == null || keybind == null) {
            return null;
        }
        Object value;
        try {
            value = mGetKeys.invoke(keybind);
        } catch (Throwable t) {
            // 单独一条、说清楚只是「这次读失败、退回快照」，不要占用 warnOnce 那条
            // 「整个功能不可用」的日志位，也不要把它误报成整体降级
            warnReadKeysOnce(t);
            return null;
        }
        if (!(value instanceof List<?> raw)) {
            return null;
        }
        List<Integer> out = new ArrayList<>(4);
        for (Object o : raw) {
            if (o instanceof Integer code) {
                out.add(code);
                if (out.size() >= MAX_KEYS) {
                    break;
                }
            }
        }
        return out;
    }

    /** 显示名：优先 malilib 的翻译名（getConfigGuiDisplayName），失败退回配置名。 */
    private static String translatedName(Object hotkey, String fallback) {
        Object value = safeInvoke(mGetGuiDisplayName, hotkey);
        String s = asString(value);
        return s.isEmpty() ? fallback : s;
    }

    /**
     * 功能名取值链（界面不许出现原始翻译键）：
     * {@code getConfigGuiDisplayName()} → 去格式码的 {@code getName()} → {@code 模组名|配置名}。
     * 任一环节拿到的字符串如果是「没翻出来的翻译键」，先 {@link #humanize(String)} 成可读词再往下走。
     */
    private static String friendlyName(String modName, String rawName, String candidate) {
        String value = displayable(candidate);
        if (value.isEmpty()) {
            value = displayable(rawName);
        }
        if (value.isEmpty()) {
            value = stripFormatting(modName) + "|" + stripFormatting(rawName);
        }
        return value;
    }

    /** 分类可读名：翻译键一律 humanize，实在不可读才退回模组名。 */
    private static String friendlyCategory(String modName, String categoryName) {
        String plain = stripFormatting(categoryName);
        if (plain.isEmpty() || !looksLikeUntranslatedKey(plain)) {
            return plain;
        }
        String word = humanize(plain);
        return word.isEmpty() ? stripFormatting(modName) : word;
    }

    /**
     * 显示用文本：正常名字原样返回；如果它是「没翻出来的翻译键」
     * （malilib 在模组缺语言文件时会把键名原样返回，例如
     * {@code tweakeroo.hotkeys.category.generic}），就转成可读词，绝不把原始键摆到界面上。
     */
    private static String displayable(String text) {
        String plain = stripFormatting(text);
        if (plain.isEmpty() || !looksLikeUntranslatedKey(plain)) {
            return plain;
        }
        String word = humanize(plain);
        return word.isEmpty() ? plain : word;
    }

    /**
     * 键位选择界面左栏「分类来源」栏名复用入口（{@link #displayable(String)} 的包内包装）：
     * 原版按键系统里模组注册的分类（{@code KeyMapping#getCategory()}）如果是没翻出来的
     * 翻译键，就走同一套可读化逻辑（去前缀 / 拆词 / 首字母大写，全大写缩写原样保留），
     * 保证左栏不会出现 {@code key.categories.jei} 这种原始键。有译文的分类不走这里。
     */
    static String categoryDisplayName(String category) {
        return displayable(category);
    }

    /** {@link #sourceDisplayName(String)} 里「全小写单词按缩写整词大写」的长度上限（jei=3、xp=2）。 */
    private static final int ACRONYM_MAX_LENGTH = 3;

    /**
     * 左栏「模组级来源」栏名复用入口（1.1.6 新增，只给 {@code KeyPickerScreen} 用）：
     * 与 {@link #categoryDisplayName(String)} 同源（去格式码 → 只留最后一段 → 拆词），
     * 但多一条缩写规则 —— 分类的模组段是<b>单个全小写短词</b>时整词大写
     * （{@code jei → JEI}、{@code xp → XP}、{@code ftb → FTB}）；
     * 已经全大写的词原样保留（{@code FTB_quests → FTB Quests}）；其余词照旧首字母大写
     * （{@code mymod → Mymod}、{@code other_mod → Other Mod}，不会变成「Other MOD」）。
     *
     * <p>单独开一个方法是为了不动老路径：malilib 的功能名 / 分类名 / 显示名继续走
     * {@link #humanize(String)}（{@code xp_boost → Xp Boost}），行为与 1.1.5 一致。</p>
     */
    static String sourceDisplayName(String text) {
        return sourceDisplayName(text, true);
    }

    /**
     * {@link #sourceDisplayName(String)} 的完整版：{@code acronyms=false} 时不做「整词大写」
     * 那条规则（其余完全一致），给「不以 {@code key.categories.} 开头的异常分类串」兜底用 ——
     * 异常串照旧拆词可读化，但不会把 {@code not.categories.foo} 的末段渲染成 {@code FOO}。
     */
    static String sourceDisplayName(String text, boolean acronyms) {
        String s = stripFormatting(text);
        if (s.isEmpty()) {
            return "";
        }
        int dot = s.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < s.length()) {
            s = s.substring(dot + 1);
        }
        List<String> words = new ArrayList<>(4);
        StringBuilder word = new StringBuilder(8);
        for (int i = 0; i <= s.length(); i++) {
            char c = i < s.length() ? s.charAt(i) : '\0';
            boolean sep = c == '_' || c == '-' || c == '.' || c == '/' || c == ' ' || c == '\0';
            boolean camel = !sep && i > 0
                    && Character.isUpperCase(c) && Character.isLowerCase(s.charAt(i - 1));
            if (sep || camel) {
                if (word.length() > 0) {
                    words.add(word.toString());
                    word.setLength(0);
                }
            }
            if (!sep) {
                word.append(c);
            }
        }
        // 只有「整段就是一个全小写短词」才按缩写整词大写；带分隔符的多词段照常规首字母大写，
        // 否则 other_mod 会变成 Other MOD
        boolean acronym = acronyms && words.size() == 1;
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (String w : words) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(acronymWord(w, acronym));
        }
        return sb.toString();
    }

    /** {@link #sourceDisplayName(String)} 的单词落地：缩写整词大写，其余首字母大写。 */
    private static String acronymWord(String word, boolean acronym) {
        if (word.isEmpty()) {
            return word;
        }
        boolean upper = true;
        boolean lower = true;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (Character.isLowerCase(c)) {
                upper = false;
            } else if (Character.isUpperCase(c)) {
                lower = false;
            }
        }
        if (acronym && lower && word.length() <= ACRONYM_MAX_LENGTH) {
            return word.toUpperCase(Locale.ROOT);                      // jei -> JEI、xp -> XP
        }
        if (!upper) {
            return Character.toUpperCase(word.charAt(0)) + word.substring(1);   // 常规词首字母大写
        }
        return word;                                                   // 已是全大写：原样保留
    }

    /**
     * 判断一个串是不是「翻译键本身」（= 该模组没提供语言文件，malilib 原样返回了键名）。
     * 判据：没有空格、含点、只由 key 允许的字符组成，且 {@code Component.translatable()} 查不到译文
     * （查到的译文等于输入本身）。真的翻译失败（Language 还没就绪）时按「是键」处理，宁可 humanize。
     */
    private static boolean looksLikeUntranslatedKey(String text) {
        if (text.indexOf(' ') >= 0 || text.indexOf('.') < 0) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-';
            if (!ok) {
                return false;
            }
        }
        try {
            return net.minecraft.network.chat.Component.translatable(text).getString().equals(text);
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 把翻译键/下划线名变成可读词：丢掉 {@code xxx.hotkeys.category.} 之类前缀（只留最后一段），
     * 按下划线/横线/点/斜杠与驼峰边界拆词，每个词首字母大写。
     * 例：{@code tweakeroo.hotkeys.category.tool_swap} → {@code Tool Swap}。
     */
    private static String humanize(String text) {
        String s = stripFormatting(text);
        if (s.isEmpty()) {
            return "";
        }
        int dot = s.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < s.length()) {
            s = s.substring(dot + 1);
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        boolean newWord = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '_' || c == '-' || c == '.' || c == '/') {
                newWord = true;
                continue;
            }
            if (i > 0 && Character.isUpperCase(c) && Character.isLowerCase(s.charAt(i - 1))) {
                newWord = true;   // 驼峰边界：tweakInventoryPreview -> Tweak Inventory Preview
            }
            if (newWord && sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(newWord ? Character.toUpperCase(c) : c);
            newWord = false;
        }
        return sb.toString().trim();
    }

    /**
     * 键码显示串：优先用原版 InputConstants 的本地化名（Left Shift / T / Button 1），
     * 拿不到再退回 malilib 自己的 getKeysDisplayString()。
     */
    private static String keysDisplay(Object keybind, List<Integer> keys) {
        StringBuilder sb = new StringBuilder(24);
        for (int code : keys) {
            if (sb.length() > 0) {
                sb.append(" + ");
            }
            sb.append(keyName(code));
        }
        if (sb.length() > 0) {
            return sb.toString();
        }
        return asString(safeInvoke(mGetKeysDisplay, keybind));
    }

    /** 键码 → 可读名；键盘走 KEYSYM，鼠标（&lt; 0）按 malilib 约定还原成按钮号。 */
    static String keyName(int code) {
        try {
            if (code < 0) {
                return InputConstants.Type.MOUSE.getOrCreate(code + 100).getDisplayName().getString();
            }
            return InputConstants.Type.KEYSYM.getOrCreate(code).getDisplayName().getString();
        } catch (Throwable t) {
            return String.valueOf(code);
        }
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 搜索用的小写串：模组名 / 友好分类 / 原始分类（可能是翻译键或配置名）/
     * 功能名（友好 + 原始 + 去格式码）/ 原始 config 名 / 键码 / 两种 ID。
     * 中英双语都能命中：中文来自 malilib 的译文，英文来自原始配置名。
     */
    static String searchText(Entry entry) {
        return (entry.modName + " " + entry.categoryPlain + " " + entry.category + " "
                + entry.displayName + " " + entry.displayPlain + " " + entry.name + " "
                + entry.keysDisplay + " " + entry.id + " " + entry.legacyId
                + " " + labelOf(entry)).toLowerCase(Locale.ROOT);
    }

    /**
     * 只记一条 warn（malilib 内部结构变化时不会刷屏）。
     * 只打异常自身的类型与消息（绝对路径会被替换成 {@code <path>}），不打堆栈、不打本机路径。
     */
    private static void warnOnce(Throwable t) {
        if (warned) {
            return;
        }
        warned = true;
        try {
            KeyPanelMod.LOGGER.warn("[key_panel] malilib 热键反射失败，已降级（该功能不可用）：{}", describe(t));
        } catch (Throwable ignored) {
            // 连日志都打不出来时也必须保持静默降级，不能把异常抛回界面
        }
    }

    /**
     * 可选显示兜底方法缺失：只记一条、只说少了哪个方法（不一定是错误 —— 别的 malilib 版本里
     * 这些方法可能改名了）。<b>present / 枚举 / 直连都不受影响</b>，所以与 {@link #warnOnce} 分开计数，
     * 免得互相把对方的日志吞掉。文案里只有方法名，没有本机路径。
     */
    private static void warnOptionalOnce(String what) {
        if (optionalWarned) {
            return;
        }
        optionalWarned = true;
        try {
            KeyPanelMod.LOGGER.warn("[key_panel] malilib 可选显示方法缺失（仅该处显示降级，枚举与直连不受影响）：{}", what);
        } catch (Throwable ignored) {
            // 日志失败也保持静默
        }
    }

    /**
     * 读不到 malilib 的全局按下键码集合：只记一条。影响面写清楚 ——
     * ①「注入是否被收下」的判定退回保守模式（当成已收下，绝不重复触发）；
     * ② 组合键 RELEASE 退回「全部释放」，玩家物理按住的组合键可能被一起松开。
     */
    private static void warnPressedSetOnce() {
        if (pressedSetWarned) {
            return;
        }
        pressedSetWarned = true;
        try {
            KeyPanelMod.LOGGER.warn("[key_panel] 读不到 malilib 的按下键码集合（KeybindMulti.PRESSED_KEYS）："
                    + "组合键触发时不会区分「玩家正按住」的键，其余不受影响");
        } catch (Throwable ignored) {
            // 日志失败也保持静默
        }
    }

    /**
     * 读某个热键的键码失败（{@code IKeybind#getKeys()} 抛异常）：只记一条。
     * 此时才允许退回枚举快照，所以这不是「整体降级」，日志文案要说清楚。
     */
    private static void warnReadKeysOnce(Throwable t) {
        if (readKeysWarned) {
            return;
        }
        readKeysWarned = true;
        try {
            KeyPanelMod.LOGGER.warn("[key_panel] 读取 malilib 热键键码失败（本次退回枚举快照）：{}", describe(t));
        } catch (Throwable ignored) {
            // 日志失败也保持静默
        }
    }
}
