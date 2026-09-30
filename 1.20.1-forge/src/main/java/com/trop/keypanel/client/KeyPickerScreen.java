package com.trop.keypanel.client;

import com.trop.keypanel.KeyPanelMod;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 键位选择界面：左栏「来源」+ 右栏键位列表。
 *
 * <p><b>1.20.1 Forge 移植说明</b>：本文件是 1.21.1（NeoForge）审计版 1.2.0 的逐行移植，
 * 归并/排序/搜索/小标题/滚动条同源等判定一字未改，只改了两处平台签名（已 javap 核实）：
 * {@code Screen#renderBackground} 在本平台是单参 {@code (GuiGraphics)}，
 * {@code Screen#mouseScrolled} 在本平台是 3 参 {@code (double,double,double)}（4 参是 1.20.2+）。
 * 其余用到的 API（{@code GuiGraphics#fill/renderOutline/drawString/blit}、{@code EditBox#setHint}、
 * {@code Button.builder().bounds()}、{@code Font#width}、{@code KeyMapping#getCategory()/CATEGORY_*}
 * 七个原版分类常量）在本平台同名同参。</p>
 */
public class KeyPickerScreen extends Screen {

    private static final int ROWS = 14;
    private static final int ROW_H = 16;
    private static final int LIST_W = 300;
    /** 右栏三列：功能名（左，按剩余宽度截断）· 分类（灰，固定列）· 键码（右，固定列）。行高与列表几何不变。 */
    private static final int KEY_COL_W = 96;
    private static final int CAT_COL_W = 76;
    // 左侧「来源」栏（原版 / 每个 malilib 模组一行）：行高与 ROWS 复用，两栏逐行对齐
    private static final int SRC_W = 88;
    private static final int SRC_GAP = 8;
    private static final int PANEL_W = SRC_W + SRC_GAP + LIST_W + 24;
    // 列表下方新增两行小字（malilib 热键限制说明）与按钮占位；列表本身的几何完全没动
    private static final int HINT1_Y = ROWS * ROW_H + 50;
    private static final int HINT2_Y = ROWS * ROW_H + 60;
    private static final int BUTTON_Y = ROWS * ROW_H + 74;
    private static final int PANEL_H = ROWS * ROW_H + 104;

    private static final int KIND_VANILLA = 1;
    private static final int KIND_MALILIB = 2;
    private static final int KIND_NOTICE = 3;
    /** 右栏的「分类小标题」行（1.1.7）：不可点击、不高亮、不参与搜索命中，只占一行给分组当标题。 */
    private static final int KIND_HEADER = 4;

    /** 左栏第一项的固定 key（原版键位）。 */
    private static final String SRC_VANILLA = "";

    /**
     * Minecraft 自带的键位分类（显式白名单，1.21.1 实测）。
     *
     * <p>依据：{@code KeyMapping} 里 {@code CATEGORY_SORT_ORDER} 的静态初始化就是这 7 个串，
     * 且对整个 minecraft-1.21.1-official.jar（8269 个 class）扫描 {@code key.categories.*} 字符串
     * 也只得到这 7 个 —— {@code movement / gameplay / inventory / creative / multiplayer / ui / misc}，
     * 其中 {@code ui} 是常量 {@code KeyMapping.CATEGORY_INTERFACE} 的值（1.21.1 没有 debug / spectator）。
     * 不在这张表里的分类一律当模组分类，会各自成栏，不再混进「原版键位」。</p>
     */
    private static final Set<String> VANILLA_CATEGORIES = Set.of(
            KeyMapping.CATEGORY_MOVEMENT,
            KeyMapping.CATEGORY_GAMEPLAY,
            KeyMapping.CATEGORY_INVENTORY,
            KeyMapping.CATEGORY_CREATIVE,
            KeyMapping.CATEGORY_MULTIPLAYER,
            KeyMapping.CATEGORY_INTERFACE,
            KeyMapping.CATEGORY_MISC);

    /** 模组分类栏的 key 前缀：保证与「原版（""）/ malilib 模组名」两种 key 都不会撞。 */
    private static final String SRC_CATEGORY_PREFIX = "category:";

    /**
     * malilib 行的兜底栏 key（1.2.0）：modName 与 category 双空时用它，保证
     * <b>非空且不等于「原版」的空 key</b>，这一栏才可能被选中。
     */
    private static final String SRC_MALILIB_OTHER = "malilib:other";

    /** 模组级分类的串前缀（{@code key.categories.<X>}）；不以此开头的一律当异常串。 */
    private static final String MOD_CATEGORY_PREFIX = "key.categories.";

    /**
     * 左栏「模组名」的分隔符（1.1.7）：分类的<b>显示名</b>里，第一个「结构分隔符」之前的那截
     * 才是模组名（{@code JEI（作弊模式） → JEI}、{@code JEI (Overlay) → JEI}、{@code JEI/Overlay → JEI}）。
     * 括号（中/英）、冒号（中/英）、竖线、短横线（含 en/em dash）、斜杠都算；空白本身也算 ——
     * 但只有当它后面紧跟这类结构分隔符时才是截断点（见 {@link #modNameFromLabel(String)}）。
     */
    private static final String MOD_NAME_SEPARATORS = "（(:：|-–—/ \t";

    /**
     * 「强分隔符」（= 限定词的开始）：括号 / 冒号 / 竖线 / 连字符 / 斜杠，<b>不含</b>空白。
     *
     * <p>两处用它：</p>
     * <ol>
     *   <li><b>截断只在结构分隔符处发生</b>：{@code JEI（作弊模式）}、{@code JEI (Overlay)} 都截成
     *       {@code JEI}；而 {@code Mouse Tweaks}、{@code Key Panel}、{@code Weird Category} 这种
     *       纯空格多词的名字保持整串 —— 否则会截成 {@code Mouse}/{@code Key}/{@code Weird}，
     *       既把双词模组名截错，又会把两个模组的首个单词相同者误并成一栏；</li>
     *   <li><b>良构父分类的译名要不要截</b>：{@code JEI（物品管理器）} 这种带限定词的父分类译名截成
     *       {@code JEI}，而 {@code Key Panel} / {@code Mouse Tweaks} 不截（1.1.6 老路径逐字不变）。</li>
     * </ol>
     */
    private static final String STRONG_SEPARATORS = "（(:：|-–—/";

    /** 列表行：原版键位 / malilib 热键 / 提示行，同一套行高与滚动几何。 */
    private static final class Row {
        final int kind;
        final KeyMapping mapping;
        final MalilibHotkeys.Entry hotkey;
        final String text;
        /** 右栏「分类」列：原版键位行 = 它自己的子分类可读名（MC 自带分类留空，见 {@link #categoryColumn}）。 */
        final String mid;
        final String right;
        final String search;
        /** 原版行的 {@code KeyMapping#getCategory()}（仅 KIND_VANILLA 用）；拆栏只看它，渲染/取值都不变。 */
        final String category;

        private Row(int kind, KeyMapping mapping, MalilibHotkeys.Entry hotkey,
                    String text, String mid, String right, String search, String category) {
            this.kind = kind;
            this.mapping = mapping;
            this.hotkey = hotkey;
            this.text = text;
            this.mid = mid;
            this.right = right;
            this.search = search;
            this.category = category;
        }

        static Row notice(String text) {
            return new Row(KIND_NOTICE, null, null, text, "", "", "", "");
        }

        /**
         * 右栏的「分类小标题」行（1.1.7）：只带一行文本，点它什么都不做
         * （{@link #clickable()} 返回 false，所以既不会被 {@code hovered} 命中，也不会高亮）。
         */
        static Row header(String text) {
            return new Row(KIND_HEADER, null, null, text, "", "", "", "");
        }

        static Row vanilla(KeyMapping mapping, String text, String right, String search, String category) {
            String mid = categoryColumn(category);
            // 1.2.0：搜索串必须带上右栏「分类」列显示的那个友好分类名（mid）与原始 category，
            // 否则用户照着右栏看到的分类名（例如「Cheat Mode」/「key.categories.jei.recipe」）搜不到
            // 这一行 —— 1.1.6/1.1.7 的回归。malilib 行那边 MalilibHotkeys.searchText 本来就带
            // categoryPlain + category，这里只给原版/模组键位行补，不重复加。
            return new Row(KIND_VANILLA, mapping, null, text, mid, right,
                    (search + " " + mid + " " + (category == null ? "" : category)).toLowerCase(Locale.ROOT),
                    category);
        }

        static Row malilib(MalilibHotkeys.Entry hotkey, String text, String mid, String right) {
            return new Row(KIND_MALILIB, null, hotkey, text, mid, right, MalilibHotkeys.searchText(hotkey), "");
        }

        boolean clickable() {
            return this.kind == KIND_VANILLA || this.kind == KIND_MALILIB;
        }
    }

    /**
     * 左栏一个「来源」：第一项固定是原版（Minecraft 自带分类的键位），其余两类各自成栏：
     * <ul>
     *   <li>原版按键系统（{@code options.keyMappings}）里<b>模组注册的分类</b>，例如 JEI 的
     *       {@code key.categories.jei.cheat_mode} / {@code key.categories.jei.recipe} —— 按
     *       {@link #sourceCategoryKey(String)} <b>归并到模组级</b>（{@code key.categories.jei}），
     *       同一模组的所有子分类只成<b>一栏</b>；子分类名仍逐行显示在右栏的「分类」列里；
     *       1.1.7 起再加一条「分类显示名按第一个结构分隔符截断」的判据
     *       （{@code JEI（作弊模式） → JEI}，见 {@link #modNameFromLabel(String)}），
     *       两条判据任一命中即同一栏，专门收拾分类键不规整、前缀规则抓不住的那类模组；</li>
     *   <li>malilib 热键，依据 {@code KeybindCategory#getModName()}（注册时传的模组名，例如 Tweakeroo），
     *       同一模组的多个分组合并成一栏，行内在右栏用分组名区分。</li>
     * </ul>
     * 栏名相同（忽略大小写）的合并成一栏，所以两类模组栏不会出现两行同名。
     */
    private static final class Source {
        /** 稳定标识：原版为 {@code ""}，malilib 模组是 modName，模组分类是 {@code "category:" + 第一行的模组级分类}。 */
        final String key;
        final String label;
        final List<Row> rows = new ArrayList<>();
        /**
         * 右栏要不要按分类分组（1.1.7）：这个来源里有 <b>≥2 个不同的分类名</b> 才插小标题。
         * 只有一个分类的模组栏（栏名已经说明了）与「原版键位」栏（各行的分类名恒为空）都不插，
         * 右栏与 1.1.6 逐像素一致。
         */
        boolean grouped;

        private Source(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    private final Screen parent;
    private final SlotData data;

    private final List<Row> all = new ArrayList<>();
    private final List<Row> filtered = new ArrayList<>();
    private final List<Source> sources = new ArrayList<>();
    /** 左栏经过「来源搜索」过滤后真正显示的那些来源；渲染/命中/滚动全部只看这张表。 */
    private final List<Source> visibleSources = new ArrayList<>();

    private EditBox search;
    /** 左栏「来源」搜索框：按栏名（原版 / 模组名 / 模组分类栏名）过滤左边那一列。 */
    private EditBox sourceSearch;
    private String sourceQuery = "";
    private int scroll;
    private int panelX;
    private int panelY;
    private int hovered = -1;
    private boolean draggingBar;
    /** 左栏当前来源（{@link #SRC_VANILLA} = 原版）；右栏搜索只在当前来源内过滤。 */
    private String selected = SRC_VANILLA;
    private int srcScroll;
    private int hoveredSource = -1;
    private boolean draggingSrcBar;
    /** 直连触发能力（init 里算一次；render 里只读字段，不做反射）。 */
    private boolean directOk;

    public KeyPickerScreen(Screen parent, SlotData data) {
        super(Component.translatable("key_panel.key.screen"));
        this.parent = parent;
        this.data = data;
    }

    @Override
    protected void init() {
        // B2：窗口/GUI 缩放会二次调用 init()，先清空累积字段，避免列表翻倍
        this.all.clear();
        this.filtered.clear();
        this.sources.clear();
        this.visibleSources.clear();
        this.scroll = 0;
        this.srcScroll = 0;
        this.hovered = -1;
        this.hoveredSource = -1;
        this.draggingBar = false;
        this.draggingSrcBar = false;
        Minecraft mc = Minecraft.getInstance();
        addVanillaRows(mc == null || mc.options == null ? null : mc.options.keyMappings);
        // malilib 热键：只在 init() 里反射枚举一次并缓存，render 每帧只查缓存
        addMalilibRows();
        this.panelX = (this.width - PANEL_W) / 2;
        this.panelY = (this.height - PANEL_H) / 2;

        this.search = new EditBox(this.font, listLeft(), panelY + 26, LIST_W, 16, Component.translatable("key_panel.key.search"));
        this.search.setMaxLength(48);
        this.search.setHint(Component.translatable("key_panel.key.search_hint"));
        this.search.setResponder(s -> {
            this.scroll = 0;
            applyFilter(s);
        });
        this.addRenderableWidget(this.search);

        // 左栏自己的搜索框：宽度 = 左栏宽，样式与右栏一致；两个框各自保留输入、互不影响
        this.sourceSearch = new EditBox(this.font, srcX(), panelY + 26, SRC_W, 16,
                Component.translatable("key_panel.key.source.search"));
        this.sourceSearch.setMaxLength(32);
        this.sourceSearch.setHint(Component.translatable("key_panel.key.source.search_hint"));
        this.sourceSearch.setValue(this.sourceQuery);
        this.sourceSearch.setResponder(this::onSourceQueryChanged);
        this.addRenderableWidget(this.sourceSearch);

        buildSources();
        this.setInitialFocus(this.search);
        applyFilter("");

        this.addRenderableWidget(Button.builder(Component.translatable("key_panel.picker.back"), b -> back())
                .bounds(panelX + PANEL_W / 2 - 30, panelY + BUTTON_Y, 60, 18).build());
        // 直连可用时，含鼠标键码的热键也能触发；不可用才沿用「发不出去」的暗色提示
        this.directOk = MalilibHotkeys.directAvailable();
    }

    /**
     * 原版按键系统（{@code options.keyMappings}）里的那一批：顺序原样保留，逐行记下自己的
     * {@code KeyMapping#getCategory()}（模组分类还会算一份可读的子分类名给右栏「分类」列），
     * 由 {@link #buildSources()} 决定它进哪一栏。
     *
     * <p>注意这里<b>不再</b>把整张表当成「原版键位」——那张表是「所有走原版按键系统的键位」，
     * 里面混着其它模组注册的键位（例如 JEI 的显示配方 / 显示用途）。</p>
     */
    void addVanillaRows(KeyMapping[] mappings) {
        if (mappings == null) {
            return;   // 离线 harness / 极早期：没有原版键位表可读
        }
        for (KeyMapping mapping : mappings) {
            if (mapping == null || mapping.getName() == null || mapping.getName().isEmpty()) {
                continue;
            }
            String name = Component.translatable(mapping.getName()).getString();
            String key = mapping.getTranslatedKeyMessage().getString();
            all.add(Row.vanilla(mapping, name, key,
                    (name + " " + key + " " + mapping.getName()).toLowerCase(Locale.ROOT),
                    mapping.getCategory()));
        }
    }

    /**
     * malilib 热键行：功能名一列、分类一列（都用可读名——分类是翻译键时会被 humanize，
     * 原始翻译键不会流到界面上）。枚举只在 init() 里做一次。
     */
    void addMalilibRows() {
        MalilibHotkeys.refresh();
        for (MalilibHotkeys.Entry entry : MalilibHotkeys.entries()) {
            all.add(Row.malilib(entry, entry.displayName, entry.categoryPlain, entry.keysDisplay));
        }
    }

    /**
     * 左栏数据来源。
     *
     * <p><b>第一栏固定是「原版键位」</b>：只装 {@code options.keyMappings} 里分类属于
     * {@link #VANILLA_CATEGORIES} （Minecraft 自带）的那些，栏名仍走
     * {@code key_panel.key.group.vanilla}，永远排第一。</p>
     *
     * <p>其余是「原版按键系统里其它分类」（就是各模组注册的键位，例如 JEI 给每个功能注册一个分类）。
     * 1.1.7 起合栏判据变成<b>两条规则任一命中即同一栏</b>（并查集做传递合并）：</p>
     * <ol>
     *   <li><b>显示名截断（新，主判据）</b>：取该分类的显示名
     *       （{@code Component.translatable(category)}，没译文先 humanize），按第一个分隔符截断得到
     *       模组名 —— {@code JEI（作弊模式） / JEI（叠加层） / JEI（配方） → JEI}。这是用户唯一能感知的
     *       东西，也是 1.1.6 漏归并的那组分类（它们的分类键不规整，前缀规则抓不住）的唯一兜底。
     *       模组名忽略大小写去空白后相同 → 同一栏；</li>
     *   <li><b>分类键前缀（1.1.6 老规则，附加判据）</b>：{@link #sourceCategoryKey(String)} 相同
     *       → 同一栏。有些模组显示名不带括号却按前缀才是同一个模组，靠这条兜住。</li>
     * </ol>
     *
     * <p>栏名：有良构 {@code key.categories.<X>} 的栏沿用 1.1.6 的父分类译名
     * （{@link #sourceCategoryLabel(String)}，逐字不变）；没有良构父分类（用户实机 JEI 那类）才用
     * 「各行显示名截断结果」—— 各行显示名不一致时退回 key 的可读化兜底。栏名相同的来源合并成一栏
     * （忽略大小写去重），栏内键位保持 {@code options.keyMappings} 的原数组顺序。</p>
     *
     * <p>malilib 热键那一批照旧按 {@code getModName()} 成栏；两类模组栏一起按栏名忽略大小写升序，
     * 栏名撞车时同样合并。没有 malilib 时也至少有「原版」一项，不会为空、不会报错。</p>
     */
    private void buildSources() {
        // 自身先清空：init() 会清，但重入调用（配置刷新/HUD 变化）时也不能累积重复行
        this.sources.clear();
        Source vanilla = new Source(SRC_VANILLA,
                Component.translatable("key_panel.key.group.vanilla").getString());
        this.sources.add(vanilla);

        // 原版自带分类进第一栏；其余（模组注册的分类）先攒起来统一归并
        List<Row> modCategoryRows = new ArrayList<>();
        for (Row row : this.all) {
            if (row.kind == KIND_VANILLA) {
                String category = row.category == null ? "" : row.category;
                if (isVanillaCategory(category)) {
                    vanilla.rows.add(row);
                } else {
                    modCategoryRows.add(row);
                }
            }
        }

        // 栏名（忽略大小写）-> 来源；LinkedHashMap 保证「先出现的栏名/顺序」稳定可复现
        Map<String, Source> byLabel = new LinkedHashMap<>();
        mergeModCategoryRows(modCategoryRows, byLabel);
        for (Row row : this.all) {
            if (row.kind == KIND_MALILIB && row.hotkey != null) {
                String mod = row.hotkey.modName;
                // modName 缺失（理论上不会）时退回分组名，保证每条热键都有归属、不会漏行
                String label = mod == null || mod.isBlank() ? row.hotkey.category : mod;
                if (label == null || label.isBlank()) {
                    // 1.2.0：modName 与 category 双空时旧代码把 label 兜底成空串，
                    // Source.key 也跟着是空串 —— 与「原版」的 key 撞车：左栏出现一个空栏名的来源，
                    // 而 findSource("") 永远先命中原版，这一栏<b>永远选不中</b>。
                    // 这里换成非空、且与原版 key 不同的兜底（「其它」那套文案 + 独立 key）。
                    addToLabelSource(byLabel, SRC_MALILIB_OTHER,
                            Component.translatable("key_panel.key.group.other").getString(), row);
                    continue;
                }
                addToLabelSource(byLabel, label, label, row);
            }
        }
        List<Source> rest = new ArrayList<>(byLabel.values());
        rest.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        for (Source src : rest) {
            // 1.2.0：并查集合并会把整块行 addAll 到吸收根末尾，「行序 = options.keyMappings 原数组顺序」
            // 的承诺因此会被打乱（两个同栏名的桶合并时尤其明显）。这里按各行在 all 里的原始下标重排一次，
            // 所有来源（模组分类栏 / malilib 栏 / 同名合并栏）统一恢复成原数组顺序。
            sortRowsByOriginalOrder(src.rows);
            // 只有 ≥2 个不同分类的模组栏才插小标题（分类名恒为空的「原版键位」栏天然不插）
            src.grouped = hasMultipleGroups(src.rows);
            this.sources.add(src);
        }
        // 选中的来源可能已经不在了（malilib 被移除 / 热键改名 / 分类栏名变化）→ 退回原版
        if (findSource(this.selected) == null) {
            this.selected = SRC_VANILLA;
        }
        // 重建之后立刻按当前来源搜索词过滤一次，保证 visibleSources / 右栏都跟得上
        applySourceFilter(this.sourceQuery);
    }

    /**
     * 把「原版按键系统里的模组分类行」按<b>显示名截断</b>与<b>分类键前缀</b>两条规则归并成模组栏。
     *
     * <p>两条规则任一相同即同一栏，且可传递（A 与 B 同显示名、B 与 C 同前缀 → A/B/C 一栏），
     * 所以这里用一张极小的并查集把两种 token（{@code G:<分类键前缀>} / {@code M:<模组名小写>}）
     * 连到一起，再按根把行分桶；分桶顺序 = 行出现顺序，栏内行序因此与
     * {@code options.keyMappings} 原数组顺序一致。</p>
     *
     * <p><b>护栏</b>：两行都带良构 {@code key.categories.<X>} 且 {@code <X>} 不同时，<b>不</b>靠显示名
     * 合并 —— 前缀规则已经明确说它们是两个模组级分类；否则 {@code Mouse Tweaks}（→ Mouse）与
     * {@code key.categories.mouse_features}（→ Mouse）会被显示名截断硬并成一栏。显示名规则负责的是
     * key 不规整那类（用户实机 JEI），那种情况下它照常合并，也能与良构的同名栏合并。</p>
     */
    private static void mergeModCategoryRows(List<Row> rows, Map<String, Source> byLabel) {
        Map<String, String> parent = new LinkedHashMap<>();        // token -> 父 token（并查集）
        Map<String, List<Row>> buckets = new LinkedHashMap<>();    // 根 token -> 该栏的行
        Map<String, String> anchor = new LinkedHashMap<>();        // 根 token -> 它的良构分类键前缀
        // 行在传入 rows（= all 里的模组分类行，保持原数组顺序）里的下标：桶内排序与栏 key 都用它，
        // 这样「并查集把一块 addAll 到吸收根末尾」也不会改变栏 key / 兜底栏名 / 最终行序。
        Map<Row, Integer> originalIndex = new java.util.IdentityHashMap<>(rows.size() * 2);
        for (int i = 0; i < rows.size(); i++) {
            originalIndex.put(rows.get(i), i);
        }
        for (Row row : rows) {
            String category = row.category == null ? "" : row.category;
            String groupToken = "G:" + sourceCategoryKey(category);
            String root = findToken(parent, groupToken);
            if (isWellFormedModCategory(category)) {
                anchor.putIfAbsent(root, groupToken);
            }
            String modName = modNameFromCategory(category);
            if (!modName.isEmpty()) {
                String modRoot = findToken(parent, "M:" + modName.toLowerCase(Locale.ROOT));
                if (!root.equals(modRoot) && mayMergeByModName(anchor, root, modRoot)) {
                    // 并到先出现的那个根上，并把已经攒在另一个根下的行整体搬过来
                    parent.put(modRoot, root);
                    List<Row> moved = buckets.remove(modRoot);
                    if (moved != null) {
                        buckets.computeIfAbsent(root, k -> new ArrayList<>()).addAll(moved);
                    }
                    String movedAnchor = anchor.remove(modRoot);
                    if (movedAnchor != null) {
                        anchor.putIfAbsent(root, movedAnchor);
                    }
                }
            }
            buckets.computeIfAbsent(root, k -> new ArrayList<>()).add(row);
        }
        for (List<Row> bucket : buckets.values()) {
            // 1.2.0：先把桶内行按「传入 rows 的原始下标」排好（合并时的 addAll 会把整块搬到吸收根末尾），
            // 再取栏 key 与兜底栏名 —— 两者都必须来自「整栏一致的那一行」/原数组里最靠前的那行，
            // 不能拿 bucket.get(0) 撞运气。
            bucket.sort(java.util.Comparator.comparingInt(
                    row -> originalIndex.getOrDefault(row, Integer.MAX_VALUE)));
            String groupKey = consistentGroupKey(bucket);
            String key = SRC_CATEGORY_PREFIX + groupKey;
            String label = sourceLabelForCategoryRows(bucket, groupKey);
            for (Row row : bucket) {
                addToLabelSource(byLabel, key, label, row);
            }
        }
    }

    /** 显示名合并的护栏：两边都已经有良构模组级分类、而且是不同的分类键时不许并。 */
    private static boolean mayMergeByModName(Map<String, String> anchor, String root, String modRoot) {
        String a = anchor.get(root);
        String b = anchor.get(modRoot);
        return a == null || b == null || a.equals(b);
    }

    /** 并查集：token 第一次出现时以自己为根；路径顺手压平。 */
    private static String findToken(Map<String, String> parent, String token) {
        String p = parent.get(token);
        if (p == null) {
            parent.put(token, token);
            return token;
        }
        if (p.equals(token)) {
            return token;
        }
        String root = findToken(parent, p);
        parent.put(token, root);
        return root;
    }

    /**
     * 一栏模组分类行的栏名。
     *
     * <ol>
     *   <li>有良构 {@code key.categories.<X>}（1.1.6 的模组级分类）→ 用它父分类的译名作栏名：
     *       这正是模组自己给的模组级名字（{@code JEI} / {@code Mymod} / {@code XP}），与 1.1.6 逐字一致；
     *       唯一的例外：父分类译名本身带限定词（{@code JEI（物品管理器）}）且各行显示名一致地就是它
     *       去掉限定词的前缀 → 用截断后的模组名（{@code JEI}）；</li>
     *   <li>没有良构父分类（用户实机 JEI 那类：分类键不规整）→ 用各行显示名截断结果；
     *       各行显示名不一致（有的有译文有的没有）→ 退回分类键的可读化兜底
     *       （用的是 {@code groupKey}，与 {@link Source#key} 同源，不再是「桶里第一行」撞运气）。</li>
     * </ol>
     */
    private static String sourceLabelForCategoryRows(List<Row> rows, String groupKey) {
        String parentLabel = null;   // 第一个良构 key.categories.<X> 的栏名（1.1.6 老规则）
        String agreed = null;        // 各行显示名截断结果（忽略大小写）一致时的取值
        boolean conflict = false;
        for (int i = 0; i < rows.size(); i++) {
            String category = rows.get(i).category == null ? "" : rows.get(i).category;
            if (parentLabel == null && isWellFormedModCategory(category)) {
                parentLabel = sourceCategoryLabel(sourceCategoryKey(category));
            }
            String modName = modNameFromCategory(category);
            if (modName.isEmpty()) {
                conflict = true;   // 显示名拿不到 → 不能拿它当栏名
                continue;
            }
            if (agreed == null) {
                agreed = modName;
            } else if (!agreed.equalsIgnoreCase(modName)) {
                conflict = true;
            }
        }
        if (parentLabel != null && !parentLabel.isBlank()) {
            if (agreed != null && !conflict && isQualifiedName(parentLabel, agreed)) {
                return agreed;     // 父分类译名带限定词 → 截断后的模组名更贴用户看到的名字
            }
            return parentLabel;
        }
        if (agreed != null && !conflict) {
            return agreed;
        }
        return fallbackLabel(groupKey);
    }

    /**
     * 一栏「与整栏一致」的分类键：{@link Source#key} 与兜底栏名都用它，替代 1.1.7 的
     * {@code bucket.get(0)}（合并后第一行是谁取决于 addAll 的顺序，属于撞运气）。规则：
     *
     * <ol>
     *   <li>整栏的良构 {@code key.categories.<X>} 只有一个取值 → 用它（与行序无关，最稳）；</li>
     *   <li>否则整栏分类键完全一致 → 用它；</li>
     *   <li>否则是「按显示名归并」出来的混合栏（各行的分类键本来就不同，例如 JEI 的三个
     *       {@code key.jei.*}）→ 用<b>原数组里最靠前</b>那一行的分类键：调用方已经把桶内行按原始
     *       下标排好，所以这就是「该模组最早注册的那个分类」，稳定可复现。</li>
     * </ol>
     */
    private static String consistentGroupKey(List<Row> rows) {
        String wellFormed = null;
        boolean wellFormedConflict = false;
        String first = null;   // 桶内第一行（调用方已按原数组下标排好）的分类键
        for (Row row : rows) {
            String category = row.category == null ? "" : row.category;
            String groupKey = sourceCategoryKey(category);
            if (first == null) {
                first = groupKey;
            }
            if (isWellFormedModCategory(category)) {
                if (wellFormed == null) {
                    wellFormed = groupKey;
                } else if (!wellFormed.equals(groupKey)) {
                    wellFormedConflict = true;
                }
            }
        }
        if (wellFormed != null && !wellFormedConflict) {
            return wellFormed;
        }
        return first == null ? "" : first;
    }

    /**
     * 把一栏的行按它们在 {@code all}（= {@code options.keyMappings} 的读入顺序，后面接 malilib 行）
     * 里的原始下标重排。并查集合并会把整块行 addAll 到吸收根末尾、同名栏合并又会把第二个桶整体
     * 追加到第一个桶后面，两处都会打乱「栏内行序 = 原数组顺序」的承诺，这一步统一补回来。
     * 不在 {@code all} 里的行（理论不会有）保持相对顺序排到最后。
     */
    private void sortRowsByOriginalOrder(List<Row> rows) {
        if (rows.size() < 2) {
            return;
        }
        Map<Row, Integer> order = new java.util.IdentityHashMap<>(this.all.size() * 2);
        for (int i = 0; i < this.all.size(); i++) {
            order.put(this.all.get(i), i);
        }
        rows.sort(java.util.Comparator.comparingInt(
                row -> order.getOrDefault(row, Integer.MAX_VALUE)));
    }

    /**
     * 栏名兜底：key 的可读化（{@link #sourceCategoryLabel(String)}）。连它都空
     * （病态的「翻译成空串」/ 空分类串）时退回 humanize，再不行才用「其它」——
     * 左栏绝不出现空栏名。
     */
    private static String fallbackLabel(String groupKey) {
        String label = sourceCategoryLabel(groupKey);
        if (label == null || label.isBlank()) {
            // 缩写整词大写只对良构模组级分类有意义，这里走纯拆词（one -> One，不是 ONE）
            label = MalilibHotkeys.sourceDisplayName(groupKey == null ? "" : groupKey, false);
        }
        return label == null || label.isBlank()
                ? Component.translatable("key_panel.key.group.other").getString()
                : label;
    }

    /** 这个分类键是不是良构的模组级分类（{@code key.categories.<X>}，{@code <X>} 非空）。 */
    static boolean isWellFormedModCategory(String category) {
        return wellFormedGroup(category) != null;
    }

    /** 良构模组级分类的 {@code key.categories.<X>}（前三段）；不规整（段数不足 / {@code <X>} 空 / 前缀不对）返回 null。 */
    private static String wellFormedGroup(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        String[] parts = category.split("\\.", -1);
        if (parts.length >= 3 && "key".equals(parts[0]) && "categories".equals(parts[1])
                && !parts[2].isBlank()) {
            return parts[0] + "." + parts[1] + "." + parts[2];
        }
        return null;
    }

    /** {@code text} 是否 = {@code prefix} + 限定词（强分隔符开头，允许中间夹空白）。 */
    private static boolean isQualifiedName(String text, String prefix) {
        if (prefix.isEmpty() || text.length() <= prefix.length()
                || !text.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return false;
        }
        int i = prefix.length();
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i < text.length() && isModNameSeparator(text.charAt(i), true);
    }

    /**
     * 左栏归并用的「分类显示名」：{@code Component.translatable(category)} 的译文优先；
     * <b>拿不到译文</b>（译文等于键名本身 / Language 还没就绪）时一律走
     * {@link MalilibHotkeys#sourceDisplayName(String, boolean)} 可读化 —— 连 {@code weird_category} 这种
     * 不带点的异常串也拆词，绝不把原始键当模组名。缩写整词大写（{@code jei → JEI}）只对
     * {@code key.} 开头的正常分类串生效，免得把 {@code not.categories.foo} 渲染成 {@code FOO}。
     */
    static String sourceDisplayName(String category) {
        if (category == null || category.isBlank()) {
            return "";
        }
        try {
            String translated = Component.translatable(category).getString();
            if (!translated.equals(category)) {
                return translated.trim();
            }
        } catch (Throwable ignored) {
            // Language 还没就绪：继续按「没译文」humanize，宁可拆词也不显示原始键
        }
        // 缩写整词大写（jei → JEI）只对「正常的短分类键」生效：key.* 开头且不超过 3 段，
        // 这样 key.categories.xp → XP，而 key.categories.mymod.deep.sub → Sub（不是 SUB）、
        // not.categories.foo → Foo（不是 FOO）。
        return MalilibHotkeys.sourceDisplayName(category, isShortKeyCategory(category));
    }

    /** 是否是 {@code key.} 开头、不超过 3 段的正常分类键（缩写整词大写的适用范围）。 */
    private static boolean isShortKeyCategory(String category) {
        if (!category.startsWith("key.")) {
            return false;
        }
        int segments = 1;
        for (int i = 0; i < category.length(); i++) {
            if (category.charAt(i) == '.' && ++segments > 3) {
                return false;
            }
        }
        return true;
    }

    /**
     * 分类的「模组名」：显示名按第一个<b>结构</b>分隔符截断（{@link #STRONG_SEPARATORS}）。
     * 无分隔符 → 整串（{@code Tweakeroo → Tweakeroo}）；空格只有在后面紧跟结构分隔符时才算截断点
     * （{@code JEI (Overlay) → JEI}，但 {@code Mouse Tweaks} 保持整串）；截出来是空的 → 退回整串，
     * 绝不返回空栏名。
     */
    static String modNameFromCategory(String category) {
        return modNameFromLabel(sourceDisplayName(category));
    }

    /** {@link #modNameFromCategory(String)} 的「从显示名截断」那一步（右栏小标题也用同一条规则）。 */
    static String modNameFromLabel(String label) {
        String s = label == null ? "" : label.trim();
        if (s.isEmpty()) {
            return "";
        }
        int cut = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isModNameSeparator(c, true)) {
                cut = i;
                break;
            }
            if (Character.isWhitespace(c) && strongSeparatorFollows(s, i + 1)) {
                cut = i;
                break;
            }
        }
        if (cut < 0) {
            return s;   // 没有结构分隔符：整串就是模组名
        }
        String head = s.substring(0, cut).trim();
        return head.isEmpty() ? s : head;
    }

    /** 从 {@code from} 起跳过空白后，下一个字符是不是结构分隔符。 */
    private static boolean strongSeparatorFollows(String text, int from) {
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i < text.length() && isModNameSeparator(text.charAt(i), true);
    }

    /** 分隔符判定：{@code strongOnly=false} 时空格/制表等空白也算（右栏小标题去前缀时用）。 */
    private static boolean isModNameSeparator(char c, boolean strongOnly) {
        if (STRONG_SEPARATORS.indexOf(c) >= 0) {
            return true;
        }
        return !strongOnly && (MOD_NAME_SEPARATORS.indexOf(c) >= 0 || Character.isWhitespace(c));
    }

    /** 这一栏的行里有没有 ≥2 个不同的分类名（有的才插小标题）。 */
    private static boolean hasMultipleGroups(List<Row> rows) {
        String first = null;
        for (Row row : rows) {
            String group = row.mid == null ? "" : row.mid.trim();
            if (group.isEmpty()) {
                continue;
            }
            if (first == null) {
                first = group;
            } else if (!first.equals(group)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 右栏一个小标题的文本（1.1.7）：默认取该行的分类列（可读名，绝不显示原始键），
     * 若它开头就是来源的模组名（{@code JEI（作弊模式）}、{@code Tweakeroo / Generic}），
     * 就把模组名与紧跟的括号/分隔符去掉，只留限定词（{@code 作弊模式} / {@code Generic}）——
     * 左栏已经写着模组名了，小标题再重复一遍纯属噪音。
     */
    static String groupHeaderLabel(String sourceLabel, String mid) {
        String text = mid == null ? "" : mid.trim();
        if (text.isEmpty()) {
            return "";
        }
        String label = sourceLabel == null ? "" : sourceLabel.trim();
        String inText = modNameFromLabel(text);
        String labelMod = modNameFromLabel(label);
        if (!inText.isEmpty()
                && (inText.equalsIgnoreCase(label) || inText.equalsIgnoreCase(labelMod))) {
            String rest = trimGroupTail(text.substring(inText.length()));
            if (!rest.isEmpty()) {
                return rest;
            }
        }
        return text;
    }

    /** 去掉限定词前缀后残留的分隔符 / 开括号（{@code （作弊模式） → 作弊模式}、{@code / Generic → Generic}）。 */
    private static String trimGroupTail(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && (Character.isWhitespace(text.charAt(start))
                || text.charAt(start) == '/' || text.charAt(start) == '\\'
                || isModNameSeparator(text.charAt(start), false)
                || text.charAt(start) == '（' || text.charAt(start) == '(')) {
            start++;
        }
        while (end > start && (Character.isWhitespace(text.charAt(end - 1))
                || text.charAt(end - 1) == '）' || text.charAt(end - 1) == ')')) {
            end--;
        }
        return text.substring(start, end);
    }

    /** 这个分类是不是 Minecraft 自带的（不在白名单里一律当模组分类）。 */
    static boolean isVanillaCategory(String category) {
        return category != null && VANILLA_CATEGORIES.contains(category);
    }

    /**
     * 分类栏名：优先用 {@code Component.translatable(category).getString()}；
     * <b>译文等于键名本身</b>（模组没提供语言文件，例如 {@code key.categories.jei}）时复用
     * malilib 那套可读化逻辑（去前缀 / 拆词 / 首字母大写，全大写缩写原样保留），
     * 绝不把原始翻译键当栏名摆到左栏上。
     *
     * <p>这里是<b>子分类</b>用的（右栏每行的「分类」列，例如 {@code key.categories.jei.cheat_mode}
     * → 「Cheat Mode」）；左栏的<b>模组级</b>栏名走 {@link #sourceCategoryLabel(String)}
     * （多一条「jei → JEI」的缩写规则）。</p>
     */
    static String categoryLabel(String category) {
        if (category == null || category.isBlank()) {
            return Component.translatable("key_panel.key.group.other").getString();
        }
        try {
            String translated = Component.translatable(category).getString();
            if (!translated.equals(category)) {
                return translated;   // 有译文：直接用（JEI / Tweakeroo 这类模组自己给的栏名）
            }
        } catch (Throwable ignored) {
            // Language 还没就绪：继续按「没译文」走 humanize，宁可拆词也不显示原始键
        }
        String word = MalilibHotkeys.categoryDisplayName(category);
        return word.isEmpty() ? Component.translatable("key_panel.key.group.other").getString() : word;
    }

    /**
     * 右栏「分类」列的值：模组子分类给可读名（译文优先，没译文 humanize），
     * <b>MC 自带分类留空</b> —— 原版栏的行照 1.1.5 的老样子渲染（只有功能名 + 键码），
     * 模组键位行（左栏已按模组归并成一栏）才多出一列自己的子分类名，供用户分辨。
     */
    static String categoryColumn(String category) {
        return isVanillaCategory(category) ? "" : categoryLabel(category);
    }

    /**
     * 模组级来源 key：把「一个功能一个子分类」的模组归到同一栏。
     *
     * <p>规则（只按 {@code '.'} 拆段，看前两段是不是 {@code key} / {@code categories}）：</p>
     * <ul>
     *   <li>{@code key.categories.<X>}（3 段）→ 原样 {@code key.categories.<X>}；</li>
     *   <li>{@code key.categories.<X>.<子项>} 及段数更多的（4 段以上）→ 一律截到
     *       {@code key.categories.<X>}，所以 {@code key.categories.jei.cheat_mode} /
     *       {@code key.categories.jei.recipe} 等全部并进同一栏；</li>
     *   <li><b>边界</b>：段数不足（{@code key.categories}、{@code key.categories.}）、
     *       {@code <X>} 为空、以及不以 {@code key.categories.} 开头的异常串 →
     *       <b>原样返回该串</b>，各自成栏（按栏名忽略大小写去重），不崩也不丢行；
     *       {@code null} / 空白 → 空串，落到「其它」那一栏。</li>
     * </ul>
     */
    static String sourceCategoryKey(String category) {
        if (category == null || category.isBlank()) {
            return "";
        }
        String[] parts = category.split("\\.", -1);
        if (parts.length >= 3 && "key".equals(parts[0]) && "categories".equals(parts[1])
                && !parts[2].isBlank()) {
            return parts[0] + "." + parts[1] + "." + parts[2];
        }
        return category;
    }

    /**
     * 左栏模组级来源的栏名：优先用 {@code Component.translatable(模组级分类)} 的译文
     * （能给 {@code key.categories.jei} 提供译文的模组直接用它的译名）；<b>译文等于键名本身</b>
     * （没译文 / Language 还没就绪）时走 {@link MalilibHotkeys#sourceDisplayName(String)}：
     * 丢掉 {@code key.categories.} 前缀、拆词、首字母大写，全大写缩写原样保留，
     * 全小写的短词按缩写整词大写（{@code jei → JEI}、{@code xp → XP}）。
     *
     * <p>缩写规则只对真·模组级分类（{@code key.categories.<X>}）生效；不以它开头的异常串
     * 走 {@link MalilibHotkeys#sourceDisplayName(String, boolean)} 的纯拆词兜底，
     * 免得把 {@code not.categories.foo} 的末段渲染成 {@code FOO}。</p>
     */
    static String sourceCategoryLabel(String groupKey) {
        if (groupKey == null || groupKey.isBlank()) {
            return Component.translatable("key_panel.key.group.other").getString();
        }
        try {
            String translated = Component.translatable(groupKey).getString();
            if (!translated.equals(groupKey)) {
                return translated;
            }
        } catch (Throwable ignored) {
            // 同 categoryLabel：拿不到译文就 humanize，绝不显示原始键
        }
        boolean modCategory = groupKey.startsWith(MOD_CATEGORY_PREFIX)
                && groupKey.length() > MOD_CATEGORY_PREFIX.length();
        String word = MalilibHotkeys.sourceDisplayName(groupKey, modCategory);
        return word.isEmpty() ? Component.translatable("key_panel.key.group.other").getString() : word;
    }

    /** 往「同名一栏」里加一行：栏名（忽略大小写）相同就并进已有那一栏，否则新建。 */
    private static void addToLabelSource(Map<String, Source> byLabel, String key, String label, Row row) {
        String mapKey = label.toLowerCase(Locale.ROOT);
        Source src = byLabel.get(mapKey);
        if (src == null) {
            src = new Source(key, label);
            byLabel.put(mapKey, src);
        }
        src.rows.add(row);
    }

    /** 左栏搜索框内容变化：换了词就回到顶部，然后重新过滤来源并同步右栏。 */
    private void onSourceQueryChanged(String query) {
        String q = query == null ? "" : query;
        if (!q.equals(this.sourceQuery)) {
            this.sourceQuery = q;
            this.srcScroll = 0;
        }
        applySourceFilter(q);
    }

    /**
     * 左栏「来源」过滤：对栏名（原版 / malilib 模组名 / 模组分类栏名）做不区分大小写的子串匹配，
     * 同时把该来源下每个键位行的搜索串（友好分类名 / 功能名 / 原始 config 名 / 键码）也纳入匹配；
     * 纯空白 = 全部显示。
     *
     * <p>选中项被过滤掉时自动选中当前可见的第一项（原版本来就在最前，所以「匹配则保持第一」），
     * 右栏随之刷新；一个来源都没匹配上时右栏显示一行灰字提示，不会停留在已隐藏的来源上。</p>
     */
    private void applySourceFilter(String query) {
        this.visibleSources.clear();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        for (Source src : this.sources) {
            if (q.isEmpty() || sourceMatches(src, q)) {
                this.visibleSources.add(src);
            }
        }
        this.srcScroll = Math.max(0, Math.min(srcMaxScroll(), this.srcScroll));
        this.hoveredSource = -1;
        if (this.visibleSources.isEmpty()) {
            this.filtered.clear();
            this.filtered.add(Row.notice(Component.translatable("key_panel.key.source.no_match").getString()));
            this.scroll = 0;
            this.hovered = -1;
            return;
        }
        if (findVisibleSource(this.selected) == null) {
            this.selected = this.visibleSources.get(0).key;
            this.scroll = 0;
        }
        applyFilter(this.search == null ? "" : this.search.getValue());
    }

    /** 来源是否命中搜索词：来源名 / 该来源下任一键位行的搜索串。 */
    private static boolean sourceMatches(Source src, String q) {
        if (src.key.toLowerCase(Locale.ROOT).contains(q) || src.label.toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        for (Row row : src.rows) {
            if (row.search.contains(q)) {
                return true;
            }
        }
        return false;
    }

    /** 在「过滤后可见」的来源里找；找不到返回 null（区别于 {@link #findSource} 找的是全量）。 */
    private Source findVisibleSource(String key) {
        for (Source src : this.visibleSources) {
            if (src.key.equals(key)) {
                return src;
            }
        }
        return null;
    }

    private Source findSource(String key) {
        for (Source src : this.sources) {
            if (src.key.equals(key)) {
                return src;
            }
        }
        return null;
    }

    private Source currentSource() {
        Source src = findSource(this.selected);
        if (src != null) {
            return src;
        }
        return this.sources.isEmpty() ? null : this.sources.get(0);
    }

    /** 切换来源：右栏回到顶部并按「当前来源 + 当前搜索词」重新过滤（左栏滚动位置不动）。 */
    private void selectSource(String key) {
        if (key.equals(this.selected)) {
            return;
        }
        this.selected = key;
        this.scroll = 0;
        applyFilter(this.search == null ? "" : this.search.getValue());
    }

    /**
     * 搜索只在当前来源内过滤；结果为空（空来源 / 无匹配）时用一行灰字提示。
     *
     * <p>1.1.7 起，来源自身有 ≥2 个分类（{@link Source#grouped}）时，右栏在<b>过滤之后</b>按
     * 「相邻同分类」插一行分类小标题（{@link Row#header(String)}）：小标题从过滤结果里现算，
     * 所以某组整组被搜索词滤掉时它的标题自然不出现；小标题只占一行、不可点击、不高亮。
     * 只按相邻行分组（不重排），栏内行序仍是 {@code options.keyMappings} 原数组顺序。</p>
     */
    private void applyFilter(String query) {
        this.filtered.clear();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        Source src = currentSource();
        if (src != null) {
            String group = null;
            for (Row row : src.rows) {
                if (!q.isEmpty() && !row.search.contains(q)) {
                    continue;
                }
                if (src.grouped) {
                    String g = groupHeaderLabel(src.label, row.mid);
                    if (!g.isEmpty() && !g.equals(group)) {
                        this.filtered.add(Row.header(g));
                        // 1.2.0：只在真的插入小标题时更新 group。旧代码无条件 `group = g`，
                        // 一行分类为空（mid 为空）的行会把 group 打回空串，同一组的后续行于是
                        // 又插一次小标题 —— 同一组被切成两段标题。
                        group = g;
                    }
                }
                this.filtered.add(row);
            }
        }
        if (this.filtered.isEmpty()) {
            this.filtered.add(Row.notice(Component.translatable("key_panel.key.source.empty").getString()));
        }
    }

    private int listLeft() {
        return panelX + 12 + SRC_W + SRC_GAP;
    }

    private int listTop() {
        return panelY + 48;
    }

    private int barX() {
        return listLeft() + LIST_W + 3;
    }

    private int barHeight() {
        int trackH = ROWS * ROW_H;
        return Math.max(12, trackH * ROWS / Math.max(1, filtered.size()));
    }

    /**
     * 右栏滚动条是否真的画出来了（渲染与命中必须同源，1.2.0）：
     * 列表短于可视行数（{@code filtered.size() <= ROWS}）时不画滚动条，
     * 那一条窄条区域就<b>不该再吞点击</b> —— 与同文件左栏 {@link #srcBarVisible()}、
     * {@code ItemPickerScreen} 的既有写法一致。
     */
    private boolean barVisible() {
        return this.filtered.size() > ROWS;
    }

    private void dragTo(double mouseY) {
        int trackH = ROWS * ROW_H;
        int barH = barHeight();
        double t = (mouseY - listTop() - barH / 2.0D) / Math.max(1.0D, trackH - barH);
        this.scroll = Math.max(0, Math.min(maxScroll(), (int) Math.round(t * maxScroll())));
    }

    private int maxScroll() {
        return Math.max(0, filtered.size() - ROWS);
    }

    // ===== 左栏（来源）几何与滚动 =====

    private int srcX() {
        return panelX + 12;
    }

    private int srcBarX() {
        return srcX() + SRC_W - 4;
    }

    private boolean srcBarVisible() {
        return this.visibleSources.size() > ROWS;
    }

    private int srcMaxScroll() {
        return Math.max(0, this.visibleSources.size() - ROWS);
    }

    private int srcBarHeight() {
        int trackH = ROWS * ROW_H;
        return Math.max(12, trackH * ROWS / Math.max(1, this.visibleSources.size()));
    }

    private void dragSrcTo(double mouseY) {
        int trackH = ROWS * ROW_H;
        int barH = srcBarHeight();
        double t = (mouseY - listTop() - barH / 2.0D) / Math.max(1.0D, trackH - barH);
        this.srcScroll = Math.max(0, Math.min(srcMaxScroll(), (int) Math.round(t * srcMaxScroll())));
    }

    /** 回到上一级界面；{@code Minecraft} 还没就绪（离线 harness）时什么也不做。 */
    private void back() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.setScreen(parent);
        }
    }

    @Override
    public void renderBackground(GuiGraphics g) {
        // B3：本模组界面自己画背景；手写调用 + super.render 内部调用会叠两次，这里留空实现。
        // 1.20.1 实测：本方法是单参 renderBackground(GuiGraphics)（4 参是 1.20.2+）。
    }

    /** 按可用宽度截断并补省略号（只用于新增的 malilib 行与提示行）。 */
    private String fit(String text, int maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return "";
        }
        if (this.font.width(text) <= maxWidth) {
            return text;
        }
        String shown = text;
        while (shown.length() > 1 && this.font.width(shown) > maxWidth) {
            shown = shown.substring(0, shown.length() - 1);
        }
        return shown.length() > 1 ? shown.substring(0, shown.length() - 1) + "…" : shown;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, 0xF0060A0B);
        g.renderOutline(panelX, panelY, PANEL_W, PANEL_H, 0xFF2FD9D9);
        g.drawString(this.font, Component.translatable("key_panel.key.title").getString(), panelX + 12, panelY + 10, 0xFFBFFFFF, false);

        drawSourceColumn(g, mouseX, mouseY);

        this.hovered = -1;
        int left = listLeft();
        for (int i = 0; i < ROWS; i++) {
            int idx = scroll + i;
            if (idx >= filtered.size()) {
                break;
            }
            Row row = filtered.get(idx);
            int y = listTop() + i * ROW_H;
            if (row.kind == KIND_NOTICE) {
                g.drawString(this.font, fit(row.text, LIST_W - 12), left + 6, y + 4, 0xFF8FA8A8, false);
                continue;
            }
            if (row.kind == KIND_HEADER) {
                // 分类小标题（1.1.7）：不参与 hover/高亮/奇偶行底色，只有一行字 + 一条细分隔线
                g.drawString(this.font, fit(row.text, LIST_W - 12), left + 4, y + 4, 0xFF7FD8D8, false);
                g.fill(left + 4, y + ROW_H - 1, left + LIST_W - 4, y + ROW_H, 0x552FD9D9);
                continue;
            }
            boolean hover = row.clickable() && mouseX >= left && mouseX < left + LIST_W
                    && mouseY >= y && mouseY < y + ROW_H;
            if (hover) {
                this.hovered = idx;
                g.fill(left, y, left + LIST_W, y + ROW_H, 0xFF17484E);
                g.renderOutline(left, y, LIST_W, ROW_H, 0xFF45F0F0);
            } else if ((i & 1) == 1) {
                g.fill(left, y, left + LIST_W, y + ROW_H, 0x33000000);
            }
            int rightW = this.font.width(row.right);
            int rightX = left + LIST_W - rightW - 6;
            if (row.kind == KIND_VANILLA && row.mid.isEmpty()) {
                // 原版栏（分类列天然为空）：渲染与 1.1.5 逐像素一致，只有功能名 + 键码
                g.drawString(this.font, row.text, left + 6, y + 4, 0xFFC6D6D6, false);
                g.drawString(this.font, row.right, rightX, y + 4, 0xFF8FF7F7, false);
            } else {
                // 功能名 / 分类 / 键码三列各自算宽度：名字占左边剩余宽度并截断，
                // 分类固定列右对齐贴着键码列，键码列贴右边，互相不挤压、右边不再被硬截断。
                // 模组键位行（左栏已按模组归并成一栏）在这里显示自己的子分类名，供用户分辨；
                // 原版行 / malilib 行都走同一套几何与颜色。
                String keys = fit(row.right, KEY_COL_W);
                int keysW = this.font.width(keys);
                int keysX = left + LIST_W - keysW - 6;
                String cat = fit(row.mid, CAT_COL_W);
                int catW = cat.isEmpty() ? 0 : this.font.width(cat);
                int nameLeft = left + 6;
                int nameW = keysX - 8 - (catW == 0 ? 0 : catW + 8) - nameLeft;
                if (nameW < 60) {
                    // 键码串特别长时分类让位，把宽度全留给功能名，避免名字被挤成两三个字
                    cat = "";
                    catW = 0;
                    nameW = keysX - 8 - nameLeft;
                }
                String shown = fit(row.text, Math.max(30, nameW));
                g.drawString(this.font, shown, nameLeft, y + 4,
                        hover && row.kind != KIND_VANILLA ? 0xFFBFFFFF : 0xFFC6D6D6, false);
                if (catW > 0) {
                    g.drawString(this.font, cat, keysX - 8 - catW, y + 4, 0xFF7FD8D8, false);
                }
                // 含鼠标键码的热键：能直连时照常亮色；只有直连不可用（要发原始事件）时才暗色。
                // 原版键位行（hotkey == null）照旧恒为亮色。
                boolean usable = row.kind == KIND_VANILLA
                        || (row.hotkey != null && (row.hotkey.keyboardOnly || this.directOk));
                g.drawString(this.font, keys, keysX, y + 4, usable ? 0xFF8FF7F7 : 0xFF8FA8A8, false);
            }
        }
        if (barVisible()) {
            int trackH = ROWS * ROW_H;
            int barH = barHeight();
            int barY = listTop() + (trackH - barH) * scroll / Math.max(1, maxScroll());
            g.fill(barX(), listTop(), barX() + 3, listTop() + trackH, 0xFF12262A);
            g.fill(barX(), barY, barX() + 3, barY + barH, 0xFF45F0F0);
        }
        // 限制说明：走翻译键，中英都有
        g.drawString(this.font, fit(Component.translatable("key_panel.key.hint.hold").getString(), PANEL_W - 24),
                panelX + 12, panelY + HINT1_Y, 0x7786B8B8, false);
        g.drawString(this.font, fit(Component.translatable("key_panel.key.hint.mouse").getString(), PANEL_W - 24),
                panelX + 12, panelY + HINT2_Y, 0x7786B8B8, false);
        super.render(g, mouseX, mouseY, partialTick);
    }

    /**
     * 左栏：过滤后的来源行（选中高亮 / 悬停）+ 需要时的滚动条。半透明只用描边，不叠底。
     * 「来源」标题的位置让给了左栏搜索框（panelY+26），搜索框的占位提示就是这一列的用途说明。
     */
    private void drawSourceColumn(GuiGraphics g, int mouseX, int mouseY) {
        int x = srcX();
        int top = listTop();
        int trackH = ROWS * ROW_H;
        g.renderOutline(x, top, SRC_W, trackH, 0x552FD9D9);

        this.hoveredSource = -1;
        if (this.visibleSources.isEmpty()) {
            g.drawString(this.font, fit(Component.translatable("key_panel.key.source.no_match").getString(), SRC_W - 8),
                    x + 4, top + 4, 0xFF8FA8A8, false);
            return;
        }
        for (int i = 0; i < ROWS; i++) {
            int idx = srcScroll + i;
            if (idx >= this.visibleSources.size()) {
                break;
            }
            Source src = this.visibleSources.get(idx);
            int y = top + i * ROW_H;
            boolean sel = src.key.equals(this.selected);
            boolean hover = mouseX >= x && mouseX < x + SRC_W && mouseY >= y && mouseY < y + ROW_H;
            if (hover) {
                this.hoveredSource = idx;
            }
            if (sel || hover) {
                // 选中/悬停都用同一块不透明行底（和右栏 hover 同色），不会和半透明底色叠加
                g.fill(x, y, x + SRC_W, y + ROW_H, 0xFF17484E);
            }
            if (sel) {
                g.renderOutline(x, y, SRC_W, ROW_H, 0xFF45F0F0);
            }
            g.drawString(this.font, fit(src.label, SRC_W - 12), x + 5, y + 4,
                    sel ? 0xFFBFFFFF : 0xFFC6D6D6, false);
        }
        if (srcBarVisible()) {
            int barH = srcBarHeight();
            int barY = top + (trackH - barH) * srcScroll / Math.max(1, srcMaxScroll());
            g.fill(srcBarX(), top, srcBarX() + 3, top + trackH, 0xFF12262A);
            g.fill(srcBarX(), barY, srcBarX() + 3, barY + barH, 0xFF45F0F0);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && srcBarVisible()
                && mouseX >= srcBarX() - 1 && mouseX < srcBarX() + 4
                && mouseY >= listTop() && mouseY < listTop() + ROWS * ROW_H) {
            this.draggingSrcBar = true;
            dragSrcTo(mouseY);
            return true;
        }
        if (button == 0 && hoveredSource >= 0 && hoveredSource < this.visibleSources.size()) {
            selectSource(this.visibleSources.get(hoveredSource).key);
            return true;
        }
        // 1.2.0：与 render 同源的保护 —— 列表短（filtered.size() <= ROWS）时没画滚动条，
        // 那一条窄区域就不该吞点击（否则紧贴列表右侧的点击会莫名其妙地什么都不做）。
        if (button == 0 && barVisible()
                && mouseX >= barX() - 2 && mouseX < barX() + 9
                && mouseY >= listTop() && mouseY < listTop() + ROWS * ROW_H) {
            this.draggingBar = true;
            dragTo(mouseY);
            return true;
        }
        if (button == 0 && hovered >= 0 && hovered < filtered.size()
                && pickRow(filtered.get(hovered))) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * 选中右栏的一行，写入正在编辑的格子。热键与原版键位<b>互斥</b>：
     * 选热键就清掉 keyBinding、选原版键位就清掉热键，避免同一格两个来源打架。
     *
     * <p>拆成独立方法（而不是继续写在 {@link #mouseClicked} 里）是为了离线 harness 能直接驱动
     * 这段判定，不必伪造整块 GUI。返回 true = 这一行被消费了。</p>
     */
    boolean pickRow(Row row) {
        if (row == null) {
            return false;
        }
        if (row.kind == KIND_MALILIB && row.hotkey != null) {
            data.setKeyBinding("");            // 互斥：热键生效时不留旧的原版键位
            data.setHotkey(row.hotkey.id);
            KeyPanelMod.LOGGER.info("[key_panel] slot {} 绑定 malilib 热键 {}（原版键位已清空）",
                    data.getIndex(), row.hotkey.id);
            back();
            return true;
        }
        if (row.kind == KIND_VANILLA && row.mapping != null) {
            data.setHotkey("");                // 互斥：选原版键位时清掉热键，否则会被热键分支挡住
            data.setKeyBinding(row.mapping.getName());
            KeyPanelMod.LOGGER.info("[key_panel] slot {} 绑定原版键位 {}（malilib 热键已清空）",
                    data.getIndex(), row.mapping.getName());
            back();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingSrcBar) {
            dragSrcTo(mouseY);
            return true;
        }
        if (draggingBar) {
            dragTo(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.draggingBar = false;
        this.draggingSrcBar = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        // 1.20.1 实测：本方法是 3 参 (mouseX, mouseY, delta)（4 参是 1.20.2+），
        // 语义与 1.21.1 的 scrollY 相同（正值 = 向上滚），下面的滚动逻辑一字未改。
        // 鼠标在左栏时滚来源，否则滚右栏键位
        boolean overSources = mouseX >= srcX() && mouseX < srcX() + SRC_W
                && mouseY >= listTop() && mouseY < listTop() + ROWS * ROW_H;
        if (overSources && srcMaxScroll() > 0) {
            this.srcScroll = Math.max(0, Math.min(srcMaxScroll(), srcScroll - (int) Math.signum(scrollY)));
            return true;
        }
        this.scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(scrollY)));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {
            back();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
