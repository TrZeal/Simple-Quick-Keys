package com.trop.keypanel.client;

import com.trop.keypanel.KeyPanelMod;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.client.settings.KeyModifier;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * 1.1 面板主界面：16:9 面板、9×3 槽位、页码条、edit 态（左三按钮竖排
 * 「格子 / 背景 / 图片」、右四行 R/G/B/A 滑块、方形色块点击复位）。
 * <p>
 * 1.19.2 没有 {@code GuiGraphics}：绘制全部经 {@link GuiCompat} 走
 * {@code PoseStack} + {@code GuiComponent}，面板底色/边框统一由 {@link PanelFrame} 负责。
 */
public class KeyPanelScreen extends Screen {

    private static final ResourceLocation TEX2 =
            ResourceLocation.fromNamespaceAndPath("key_panel", "textures/gui/key_panel_v2.png");
    private static final int TEX2_W = 512;
    private static final int TEX2_H = 512;
    /** v2 图集里三档卡片的列偏移。 */
    private static final int[] CARD_X = {0, 50, 92};
    private int tierIndex;

    private static final int COLS = 9;
    private static final int ROWS = 3;
    private static final int[][] TIERS = {
            {46, 54, 4, 14, 2, 0, 0, 490, 0, 490, 62, 490, 124},
            {40, 48, 3, 10, 2, 0, 230, 490, 190, 490, 244, 490, 298},
            {34, 40, 2, 8, 1, 0, 430, 490, 360, 490, 406, 490, 452},
    };
    private static final int PAGEBAR = 22;

    private int cardW = TIERS[0][0];
    private int cardH = TIERS[0][1];
    private int gap = TIERS[0][2];
    private int gapX = TIERS[0][2];
    private int gapY = TIERS[0][2];
    private int pad = TIERS[0][3];
    private int iconScale = TIERS[0][4];
    // 分档表里的旧图集坐标列：v1.1 的卡片改由 TEX2 的 CARD_X + 行号定位，
    // 这几列保留下来只为让 TIERS 表结构与旧版一致（不再参与绘制）。
    private int panelU;
    private int panelV;
    private int cardUNormal;
    private int cardUEmpty;
    private int cardUHover;
    private int cardVNormal;
    private int cardVEmpty;
    private int cardVHover;
    private int panelW;
    private int panelH;

    private static final int COL_EDGE      = 0xCC2FD9D9;
    private static final int COL_NAME      = 0xFFC6D6D6;
    private static final int COL_NAME_HOVER = 0xFFBFFFFF;
    private static final int COL_HINT      = 0x7786B8B8;

    private final List<SlotData> slots;
    private int panelX;
    private int panelY;
    private int gridX;
    private int gridY;
    private int hovered = -1;
    private int page;
    private boolean moveMode;
    private int moveSource = -1;
    private int selectedSlot = -1;
    private int sliderDrag = -1;
    private boolean alphaMigrated;
    private boolean bgTarget;
    private static final int STRIP_H = 96;
    private boolean hoverPrev;
    private boolean hoverNext;

    public KeyPanelScreen() {
        super(Component.empty());
        this.slots = ConfigManager.load();
        PanelStyle.load();
    }

    @Override
    protected void init() {
        relayout();
    }

    /** 旧配置兼容（只做一次）：颜色只存了 0xRRGGBB 时 alpha 是 0，按不透明补上。 */
    private void migrateAlphaOnce() {
        boolean changed = false;
        for (SlotData d : slots) {
            int c = d.getColor();
            // 只有 == -1 才是「没设过颜色」：alpha >= 128 时颜色是负数，用 < 0 会误判
            if (c != -1 && (c >>> 24) == 0) {
                d.setColor(c | 0xFF000000);
                changed = true;
            }
        }
        if (PanelStyle.color() != -1 && (PanelStyle.color() >>> 24) == 0) {
            PanelStyle.setColor(PanelStyle.color() | 0xFF000000);
        }
        if (changed) {
            saveSlots();
        }
    }

    private void relayout() {
        applyTier();
        // 网格铺满可用区域：间距按剩余空间摊开，不再留大片空边
        gapX = Math.max(gap, (panelW - 2 * (PanelFrame.PAD + GRID_PAD) - COLS * cardW) / (COLS - 1));
        gapY = Math.max(gap, (contentBottom() - contentTop() - ROWS * cardH) / (ROWS - 1));
        int totalH = panelH + (moveMode ? STRIP_H + 8 : 0);
        this.panelX = (this.width - panelW) / 2;
        this.panelY = (this.height - totalH) / 2;
        this.gridX = gridOriginX();
        this.gridY = gridOriginY();
    }

    private void applyTierFields(int[] t) {
        cardW = t[0];
        cardH = t[1];
        gap = t[2];
        pad = t[3];
        iconScale = t[4];
        panelU = t[5];
        panelV = t[6];
        cardUNormal = t[7];
        cardVNormal = t[8];
        cardUHover = t[9];
        cardVHover = t[10];
        cardUEmpty = t[11];
        cardVEmpty = t[12];
    }

    private void applyTier() {
        for (int[] t : TIERS) {
            int gw = COLS * t[0] + (COLS - 1) * t[2];
            int gh = ROWS * t[1] + (ROWS - 1) * t[2];
            int[] size = panelSizeFor(gw, gh, moveMode);
            if (size[0] <= this.width - 6 && size[1] + (moveMode ? STRIP_H + 10 : 0) <= this.height - 6) {
                cardW = t[0];
                cardH = t[1];
                gap = t[2];
                pad = t[3];
                applyTierFields(t);
                tierIndex = 0;
                for (int k = 0; k < TIERS.length; k++) {
                    if (TIERS[k] == t) {
                        tierIndex = k;
                    }
                }
                panelW = size[0];
                panelH = size[1];
                return;
            }
        }
        int[] t = TIERS[TIERS.length - 1];
        cardW = t[0];
        cardH = t[1];
        gap = t[2];
        pad = t[3];
        applyTierFields(t);
        tierIndex = TIERS.length - 1;
        int gw = COLS * t[0] + (COLS - 1) * t[2];
        int gh = ROWS * t[1] + (ROWS - 1) * t[2];
        int[] size = panelSizeFor(gw, gh, moveMode);
        panelW = size[0];
        panelH = size[1];
    }

    private static int[] panelSizeFor(int gridW, int gridH, boolean edit) {
        int needW = gridW + 28 + 20;                                 // 左右边框各 14 + 网格两侧各留 10
        // 纵向：边框 14 + 网格上边距 10 + 网格 + 8 + 页码条 22 + 边框 14
        int needH = gridH + 14 + 10 + 8 + PAGEBAR + 14;
        int w = needW;
        int h = needH;
        if (w * 9 >= h * 16) {
            h = (int) Math.ceil(w * 9.0D / 16.0D);
        } else {
            w = (int) Math.ceil(h * 16.0D / 9.0D);
        }
        return new int[]{Math.max(w, needW), Math.max(h, needH)};
    }

    private static final int GRID_PAD = 10;

    private int gridOriginX() {
        return panelX + PanelFrame.PAD + GRID_PAD;
    }

    /** 网格可用区域的上下边界（避开边框与页码条）。 */
    private int contentTop() {
        return panelY + PanelFrame.PAD + GRID_PAD;
    }

    private int pageBarTop() {
        return panelY + panelH - PanelFrame.PAD - PAGEBAR;
    }

    private int contentBottom() {
        return pageBarTop() - 8;
    }

    private int gridOriginY() {
        int gridH = ROWS * cardH + (ROWS - 1) * gapY;
        int top = contentTop();
        int bottom = contentBottom();
        return top + Math.max(0, (bottom - top - gridH) / 2);
    }

    @Override
    public void renderBackground(PoseStack poseStack) {
        // 本模组界面自己画背景：不叠加原版的暗化层，
        // 否则它会盖在面板之上，把切角四角压成方的（渲染顺序问题）。
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(poseStack);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        this.hovered = cardAt(mouseX, mouseY);
        int[] lp = arrowRect(true);
        int[] rp = arrowRect(false);
        this.hoverPrev = mouseX >= lp[0] && mouseX < lp[2] && mouseY >= lp[1] && mouseY < lp[3];
        this.hoverNext = mouseX >= rp[0] && mouseX < rp[2] && mouseY >= rp[1] && mouseY < rp[3];

        if (!alphaMigrated) {
            alphaMigrated = true;
            migrateAlphaOnce();
        }
        // 每帧自己跟一次拖动：mouseDragged 事件可能中途断掉（拖到一半停住就是这个原因），
        // 这里用当前鼠标位置继续调，松手当帧自动收尾。
        if (sliderDrag >= 0) {
            long win = Minecraft.getInstance().getWindow().getWindow();
            if (org.lwjgl.glfw.GLFW.glfwGetMouseButton(win, org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS) {
                dragSlider(sliderDrag, mouseX);
            } else {
                sliderDrag = -1;
                saveSlots();
                PanelStyle.save();
            }
        }
        // 半透明贴图必须自己把混合函数设回标准值：只 enableBlend 不够，
        // 1.19.2 的 fill 结尾会 disableBlend，别的绘制也可能把 blendFunc 留成 ONE/ZERO，
        // 那样贴图 alpha 会被当成不透明。
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        drawPanel(poseStack);
        drawCards(poseStack);
        drawPageBar(poseStack);
        if (moveMode) {
            drawColorStrip(poseStack);
        }

        super.render(poseStack, mouseX, mouseY, partialTick);
        GuiCompat.flush();
        poseStack.pushPose();
        poseStack.translate(0.0D, 0.0D, 400.0D);
        drawHoverTip(poseStack, mouseX, mouseY);
        poseStack.popPose();
        RenderSystem.enableBlend();   // 半透明贴图（卡片/背景图）必须保持混合开启，关掉会被画成不透明
    }

    private int[] moveButtonRect() {
        return new int[]{panelX + 44, panelY + panelH - PAGEBAR + 2, panelX + 44 + 84, panelY + panelH - PAGEBAR + 20};
    }

    private boolean isOverMoveButton(double mouseX, double mouseY) {
        int[] r = moveButtonRect();
        return mouseX >= r[0] && mouseX < r[2] && mouseY >= r[1] && mouseY < r[3];
    }

    private void swapSlots(int a, int b) {
        if (a == b || a < 0 || b < 0 || a >= slots.size() || b >= slots.size()) {
            return;
        }
        SlotData sa = slots.get(a);
        SlotData sb = slots.get(b);
        slots.set(a, sb);
        slots.set(b, sa);
        sa.setIndex(b);
        sb.setIndex(a);
        saveSlots();
        KeyPanelMod.LOGGER.info("[key_panel] swap slot {} <-> {}", a, b);
    }

    /** edit 态提示语（全部走翻译键）。 */
    private String moveHint() {
        if (bgTarget) {
            int c = PanelStyle.color();
            String hex = c == -1 ? Component.translatable("key_panel.panel.color.default_look").getString()
                    : String.format("#%08X", c);
            return Component.translatable("key_panel.panel.hint.bg", hex).getString();
        }
        if (selectedSlot >= 0 && selectedSlot < slots.size()) {
            int c = slots.get(selectedSlot).getColor();
            String hex = c == -1 ? Component.translatable("key_panel.panel.color.default").getString()
                    : String.format("#%08X", c);
            return Component.translatable("key_panel.panel.hint.slot", selectedSlot + 1, hex).getString();
        }
        return moveSource < 0 ? Component.translatable("key_panel.panel.hint.edit").getString()
                : Component.translatable("key_panel.panel.hint.move").getString();
    }

    private void drawPanel(PoseStack poseStack) {
        // 统一走 PanelFrame：底色/壁纸按切角裁，和背景选择界面、附属卡片同一套逻辑
        PanelFrame.drawBackground(poseStack, panelX, panelY, panelW, panelH,
                PanelStyle.color() != -1 ? PanelStyle.color() : 0xB00A0E0F);
    }

    private void drawCards(PoseStack poseStack) {
        for (int i = 0; i < COLS * ROWS; i++) {
            int slotIndex = page * ConfigManager.PAGE_SIZE + i;
            if (slotIndex >= slots.size()) {
                break;
            }
            SlotData data = slots.get(slotIndex);
            int col = i % COLS;
            int row = i / COLS;
            // 与 cardAt() 用同一行公式（gridX/gridY + 列行 × 卡宽/高 + 间距），画在这、点也在这
            int x = gridX + col * (cardW + gapX);
            int y = gridY + row * (cardH + gapY);
            boolean hover = i == hovered;
            boolean active = isActiveToggle(data);
            boolean empty = data.isEmpty();
            int drawY = (hover && !empty) ? y - 1 : y;

            int slotColor = data.getColor();
            int cardV = (empty && !active) ? 60 : ((hover || active) ? 114 : 2);
            // 底色用代码画（fill 的半透明是好的），图集只负责描边：
            // 贴图自带的 alpha 在某些渲染状态下会被当成不透明，画出来就是一块黑。
            // 格子自己带足够厚的底色：无论面板是深色、浅色还是壁纸，格子都保持自己的样子
            int cardFill = empty ? 0x660A1214 : 0x7A0D181A;
            if (hover || active) {
                cardFill = 0x9A17485A;
            }
            // 只有 == -1 表示「没设过颜色」，负数是 alpha ≥ 128 的半透明色，不能当未设置
            if (!moveMode && slotColor != -1) {
                cardFill = slotColor;
            }
            PanelFrame.fillChamfered(poseStack, x, drawY, cardW, cardH, 4, cardFill);
            GuiCompat.blitTexture(poseStack, TEX2, x, drawY, cardW, cardH,
                    CARD_X[Math.min(tierIndex, 2)], cardV, cardW, cardH, TEX2_W, TEX2_H);
            if (moveMode && slotIndex == selectedSlot) {
                GuiCompat.outline(poseStack, x - 1, drawY - 1, cardW + 2, cardH + 2, 0xFFBFFFFF);
            }

            if (empty) {
                continue;
            }
            ItemStack stack = stackOf(data);
            if (!stack.isEmpty()) {
                float iconPx = 16.0F * iconScale;
                float iconY = drawY + (iconPx >= 32.0F ? 4.0F : (cardH - iconPx) / 2.0F - 3.0F);
                GuiCompat.renderItemScaled(this.itemRenderer, stack,
                        x + (cardW - iconPx) / 2.0F, iconY, (float) iconScale);
            }
            String label = displayName(data);
            if (!label.isEmpty()) {
                int maxW = cardW - 6;
                String shown = label;
                while (this.font.width(shown) > maxW && shown.length() > 1) {
                    shown = shown.substring(0, shown.length() - 1);
                }
                if (!shown.equals(label) && shown.length() > 1) {
                    shown = shown.substring(0, shown.length() - 1) + "…";
                }
                GuiCompat.drawString(poseStack, this.font, shown, x + (cardW - this.font.width(shown)) / 2,
                        drawY + cardH - 12, (hover || active) ? COL_NAME_HOVER : COL_NAME);
            }
            if (moveMode && slotIndex == moveSource) {
                GuiCompat.outline(poseStack, x, drawY, cardW, cardH, 0xFF45F0F0);
                GuiCompat.fill(poseStack, x + 1, drawY + 1, x + cardW - 1, drawY + cardH - 1, 0x4445F0F0);
            }
            if (active) {
                GuiCompat.fill(poseStack, x + 8, drawY + cardH - 6, x + cardW - 8, drawY + cardH - 5, 0xFF45F0F0);
            }
        }
    }

    private static KeyMapping virtualHolder;
    private static com.mojang.blaze3d.platform.InputConstants.Key virtualHolderOriginal;

    static void restoreVirtualHolder() {
        if (virtualHolder != null && virtualHolderOriginal != null) {
            virtualHolder.setKeyModifierAndCode(KeyModifier.NONE, virtualHolderOriginal);
            virtualHolder.setDown(false);
            KeyMapping.resetMapping();
        }
        virtualHolder = null;
        virtualHolderOriginal = null;
    }

    /** 面板关闭时确保虚拟键的临时改绑已还原。 */
    @Override
    public void removed() {
        super.removed();
        restoreVirtualHolder();
    }

    private static boolean rebindToVirtual(KeyMapping mapping, KeyMapping virtual) {
        if (virtual == null || mapping == null) {
            return false;
        }
        if (mapping == virtualHolder) {
            return true;
        }
        restoreVirtualHolder();
        virtualHolderOriginal = mapping.getKey();
        virtualHolder = mapping;
        mapping.setKeyModifierAndCode(KeyModifier.NONE, virtual.getKey());
        mapping.setDown(false);
        KeyMapping.resetMapping();
        return true;
    }

    private static String displayName(SlotData data) {
        if (data.getCustomName() != null && !data.getCustomName().isEmpty()) {
            return data.getCustomName();
        }
        if (data.getKeyBinding() != null && !data.getKeyBinding().isEmpty()) {
            String name = Component.translatable(data.getKeyBinding()).getString();
            if (!name.equals(data.getKeyBinding())) {
                return name;
            }
        }
        ItemStack stack = stackOf(data);
        if (!stack.isEmpty()) {
            return stack.getHoverName().getString();
        }
        return "";
    }

    private void drawHoverTip(PoseStack poseStack, int mouseX, int mouseY) {
        if (page * ConfigManager.PAGE_SIZE + Math.max(0, hovered) >= slots.size() || hovered < 0) {
            return;
        }
        SlotData data = slots.get(page * ConfigManager.PAGE_SIZE + hovered);
        if (data.isEmpty()) {
            return;
        }
        String name = displayName(data);
        if (name.isEmpty()) {
            name = data.getItemId();
        }
        String how;
        if (!data.getCommand().isEmpty()) {
            how = Component.translatable("key_panel.panel.tip.command", data.getCommand()).getString();
        } else if (data.getKeyBinding() == null || data.getKeyBinding().isEmpty()) {
            how = Component.translatable("key_panel.panel.tip.unbound").getString();
        } else {
            how = Component.translatable("key_panel.panel.tip.function",
                    Component.translatable(data.getKeyBinding()).getString()).getString();
        }
        int w = Math.max(this.font.width(name), this.font.width(how)) + 10;
        int h = 24;
        int tx = Math.max(2, Math.min(mouseX + 14, this.width - w - 4));
        int ty = Math.max(2, Math.min(mouseY - h - 8, this.height - h - 4));
        GuiCompat.fill(poseStack, tx, ty, tx + w, ty + h, 0xF0060A0B);
        GuiCompat.outline(poseStack, tx, ty, w, h, 0xFF45F0F0);
        GuiCompat.drawString(poseStack, this.font, name, tx + 5, ty + 4, 0xFFFFFFFF);
        GuiCompat.drawString(poseStack, this.font, how, tx + 5, ty + 14, 0xFF7FD8D8);
    }

    private void drawPageBar(PoseStack poseStack) {
        int barY = panelY + panelH - PAGEBAR;
        int cy = barY + PAGEBAR / 2;
        int pages = ConfigManager.PAGES;
        GuiCompat.fill(poseStack, panelX + 18, barY, panelX + panelW - 18, barY + 1, 0x332FD9D9);
        boolean canPrev = page > 0;
        arrow(poseStack, panelX + 30, cy, true, canPrev ? (hoverPrev ? 0xFF8FF7F7 : COL_EDGE) : 0x33FFFFFF);
        boolean canNext = page < pages - 1;
        arrow(poseStack, panelX + panelW - 30, cy, false, canNext ? (hoverNext ? 0xFF8FF7F7 : COL_EDGE) : 0x33FFFFFF);
        String txt = (page + 1) + " / " + pages;
        GuiCompat.drawString(poseStack, this.font, txt, panelX + (panelW - this.font.width(txt)) / 2, cy - 4,
                moveMode ? 0xFF8FF7F7 : COL_HINT);
        int[] mb = moveButtonRect();
        GuiCompat.blitTexture(poseStack, TEX2, mb[0], mb[1], 84, 18, moveMode ? 100 : 0, 240, 96, 20,
                TEX2_W, TEX2_H);
        String label = Component.translatable("key_panel.panel.edit").getString();
        GuiCompat.drawString(poseStack, this.font, label, mb[0] + (84 - this.font.width(label)) / 2, mb[1] + 5,
                moveMode ? 0xFF3E8A8C : 0xFFBFFFFF);
    }

    private void drawColorStrip(PoseStack poseStack) {
        int top = stripTop();
        PanelFrame.fillChamfered(poseStack, panelX, top, panelW, STRIP_H, 6, 0xB00A1214);
        PanelFrame.draw(poseStack, panelX, top, panelW, STRIP_H);

        String[] names = {
                Component.translatable("key_panel.panel.target.slot").getString(),
                Component.translatable("key_panel.panel.target.bg").getString(),
                Component.translatable("key_panel.panel.target.image").getString()};
        for (int i = 0; i < 3; i++) {
            int[] r = targetButtonRect(i);
            // 「图片」是按钮（点开选图），不是当前目标，所以不参与常亮
            boolean on = (i == 0 && !bgTarget) || (i == 1 && bgTarget);
            GuiCompat.fill(poseStack, r[0], r[1], r[2], r[3], on ? 0xE017484E : 0xC00C1416);
            GuiCompat.outline(poseStack, r[0], r[1], r[2] - r[0], r[3] - r[1], on ? 0xFF45F0F0 : 0x552FD9D9);
            GuiCompat.drawString(poseStack, this.font, names[i],
                    r[0] + (r[2] - r[0] - this.font.width(names[i])) / 2, r[1] + 7,
                    on ? 0xFFBFFFFF : 0xFF8FA8A8);
        }

        boolean usable = bgTarget || !slots.isEmpty();
        int target = targetColor();
        int shown = target == -1 ? (bgTarget ? 0xB00A0E0F : 0x7A0D181A) : target;
        int tx = sliderTrackX();
        int tw = sliderTrackW();
        for (int i = 0; i < 4; i++) {
            // 与 sliderAt() 同一行公式：top + 14 + i * 20，画在这、命中判定也在这
            int y = top + 14 + i * 20;
            int alpha = (shown >>> 24);
            int val = i == 3 ? Math.max(1, alpha) : ((shown >> (16 - i * 8)) & 0xFF);
            String letter = i == 0 ? "R" : (i == 1 ? "G" : (i == 2 ? "B" : "A"));
            GuiCompat.drawString(poseStack, this.font, letter, tx - 14, y, usable ? 0xFFC6D6D6 : 0xFF5A6A6A);
            GuiCompat.fill(poseStack, tx, y, tx + tw, y + 8, 0xC012262A);
            int knob = tx + (tw - 1) * val / 255;
            GuiCompat.fill(poseStack, knob - 2, y - 3, knob + 3, y + 11, usable ? 0xFF45F0F0 : 0xFF2A5A5A);
        }
        int[] pr = previewRect();
        GuiCompat.fill(poseStack, pr[0], pr[1], pr[2], pr[3], 0xFF000000 | shown);
        GuiCompat.outline(poseStack, pr[0], pr[1], pr[2] - pr[0], pr[3] - pr[1], 0x662FD9D9);
    }

    /** 当前调色目标：-1 才是「没设过颜色」。 */
    private int targetColor() {
        if (bgTarget) {
            return PanelStyle.color();
        }
        // 「格子」现在一次管全部格子：所有格子颜色一致就返回它，否则用第一格的值显示
        if (slots.isEmpty()) {
            return -1;
        }
        int first = slots.get(0).getColor();
        for (SlotData d : slots) {
            if (d.getColor() != first) {
                return first;
            }
        }
        return first;
    }

    private void setTargetColor(int rgb) {
        if (bgTarget) {
            PanelStyle.setColor(rgb);
        } else {
            for (SlotData d : slots) {
                d.setColor(rgb);
            }
        }
    }

    private int[] targetButtonRect(int i) {
        int top = stripTop();
        int w = 64;
        int x = panelX + 16;
        int y = top + 11 + i * 26;
        return new int[]{x, y, x + w, y + 22};
    }

    private int targetButtonAt(double mx, double my) {
        if (!moveMode) {
            return -1;
        }
        for (int i = 0; i < 3; i++) {
            int[] r = targetButtonRect(i);
            if (mx >= r[0] && mx < r[2] && my >= r[1] && my < r[3]) {
                return i;
            }
        }
        return -1;
    }

    private int stripTop() {
        return panelY + panelH + 8;
    }

    private int sliderTrackX() {
        return panelX + 96;
    }

    private int sliderTrackW() {
        return panelW - 96 - 150;
    }

    private int[] previewRect() {
        int x = panelX + panelW - 96;
        return new int[]{x, stripTop() + 16, x + 64, stripTop() + 80};
    }

    /** 滑条命中：与 drawColorStrip() 用同一套坐标（top + 14 + i * 20）。 */
    private int sliderAt(double mx, double my) {
        if (!moveMode) {
            return -1;
        }
        int tx = sliderTrackX();
        int tw = sliderTrackW();
        int top = stripTop();
        if (mx < tx - 8 || mx > tx + tw + 8) {
            return -1;
        }
        for (int i = 0; i < 4; i++) {
            int y = top + 14 + i * 20;
            if (my >= y - 7 && my < y + 15) {
                return i;
            }
        }
        return -1;
    }

    private void dragSlider(int which, double mx) {
        if (!bgTarget && slots.isEmpty()) {
            return;
        }
        int tx = sliderTrackX();
        int tw = Math.max(2, sliderTrackW() - 1);
        double t = Math.max(0.0D, Math.min(1.0D, (mx - tx) / (double) tw));
        int val = (int) Math.round(t * 255.0D);
        int tc = targetColor();
        int cur = tc == -1 ? (bgTarget ? 0xB00A0E0F : 0x7A0D181A) : tc;
        if (which == 3) {
            // 左=透明、右=不透明；只改 alpha 位，不碰 RGB
            // 最左写 1 而不是 0：0 专门留给「旧配置没写 alpha」
            cur = (cur & 0x00FFFFFF) | (Math.max(1, val) << 24);
        } else {
            int shift = 16 - which * 8;
            cur = (cur & ~(0xFF << shift)) | (val << shift);
        }
        setTargetColor(cur);   // 不能再 & 0xFFFFFF：那会把刚拖出来的透明度抹掉
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (sliderDrag >= 0) {
            dragSlider(sliderDrag, mouseX);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (sliderDrag >= 0) {
            sliderDrag = -1;
            saveSlots();
            PanelStyle.save();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private int[] arrowRect(boolean left) {
        int barY = panelY + panelH - PAGEBAR;
        int cy = barY + PAGEBAR / 2;
        int cx = left ? panelX + 30 : panelX + panelW - 30;
        return new int[]{cx - 8, cy - 8, cx + 8, cy + 8};
    }

    private static void arrow(PoseStack poseStack, int cx, int cy, boolean left, int color) {
        int size = 5;
        for (int i = 0; i <= size; i++) {
            int px = left ? cx - size + i : cx + size - i;
            GuiCompat.fill(poseStack, px, cy - i, px + 1, cy + i + 1, color);
        }
    }

    /** 卡片命中：与 drawCards() 同一行公式（gridX/gridY + 列行 × 卡宽/高 + 间距）。 */
    private int cardAt(double mouseX, double mouseY) {
        for (int i = 0; i < COLS * ROWS; i++) {
            int col = i % COLS;
            int row = i / COLS;
            int x = gridX + col * (cardW + gapX);
            int y = gridY + row * (cardH + gapY) - 1;
            if (mouseX >= x && mouseX < x + cardW && mouseY >= y && mouseY < y + cardH + 1) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (delta > 0) {
            page = Math.max(0, page - 1);
        } else if (delta < 0) {
            page = Math.min(ConfigManager.PAGES - 1, page + 1);
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int[] lp = arrowRect(true);
        int[] rp = arrowRect(false);
        if (mouseX >= lp[0] && mouseX < lp[2] && mouseY >= lp[1] && mouseY < lp[3]) {
            page = Math.max(0, page - 1);
            return true;
        }
        if (mouseX >= rp[0] && mouseX < rp[2] && mouseY >= rp[1] && mouseY < rp[3]) {
            page = Math.min(ConfigManager.PAGES - 1, page + 1);
            return true;
        }
        if (button == 0 && isOverMoveButton(mouseX, mouseY)) {
            moveMode = !moveMode;
            moveSource = -1;
            selectedSlot = -1;
            relayout();
            KeyPanelMod.LOGGER.info("[key_panel] edit mode = {}", moveMode);
            return true;
        }
        if (moveMode && button == 0) {
            int tb = targetButtonAt(mouseX, mouseY);
            if (tb == 0) {
                bgTarget = false;
                return true;
            }
            if (tb == 1) {
                bgTarget = true;
                return true;
            }
            if (tb == 2) {
                Minecraft.getInstance().setScreen(new BackgroundPickerScreen(this));
                return true;
            }
        }
        if (moveMode) {
            int which = sliderAt(mouseX, mouseY);
            if (which >= 0) {
                sliderDrag = which;
                dragSlider(which, mouseX);
                return true;
            }
            int[] pr = previewRect();
            if (mouseX >= pr[0] && mouseX < pr[2] && mouseY >= pr[1] && mouseY < pr[3]) {
                setTargetColor(-1);
                saveSlots();
                PanelStyle.save();
                return true;
            }
        }
        int index = cardAt(mouseX, mouseY);
        if (moveMode && index >= 0) {
            int slotIndex = page * ConfigManager.PAGE_SIZE + index;
            if (button == 1) {
                moveMode = false;
                relayout();
                moveSource = -1;
                return true;
            }
            if (slotIndex < slots.size()) {
                if (moveSource < 0) {
                    moveSource = slotIndex;
                    selectedSlot = slotIndex;
                } else {
                    swapSlots(moveSource, slotIndex);
                    selectedSlot = slotIndex;
                    moveSource = -1;
                }
                return true;
            }
        }
        if (index >= 0) {
            int slotIndex = page * ConfigManager.PAGE_SIZE + index;
            if (slotIndex >= slots.size()) {
                return false;
            }
            SlotData data = slots.get(slotIndex);
            KeyPanelMod.LOGGER.info("[key_panel] click slot {} button {}", slotIndex, button);
            if (button == 0) {
                trigger(data);
                return true;
            }
            if (button == 1) {
                Minecraft.getInstance().setScreen(new KeyPanelEditScreen(this, data));
                return true;
            }
        }
        KeyPanelMod.LOGGER.info("[key_panel] click at ({}, {}) hit nothing", (int) mouseX, (int) mouseY);
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean runVanillaAction(KeyMapping mapping) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options == null) {
            return false;
        }
        if (mapping == mc.options.keyInventory) {
            if (mc.player.isCreative()) {
                mc.setScreen(new net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen(mc.player));
            } else {
                mc.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
            }
            return true;
        }
        if (mapping == mc.options.keySwapOffhand) {
            mc.player.connection.send(new net.minecraft.network.protocol.game.ServerboundPlayerActionPacket(
                    net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                    net.minecraft.core.BlockPos.ZERO, net.minecraft.core.Direction.DOWN));
            return true;
        }
        if (mapping == mc.options.keyDrop) {
            mc.player.drop(false);
            return true;
        }
        if (mapping == mc.options.keyTogglePerspective) {
            mc.options.setCameraType(mc.options.getCameraType().cycle());
            return true;
        }
        if (mapping == mc.options.keyShift) {
            mapping.setDown(!mapping.isDown());
            return true;
        }
        if (mapping == mc.options.keySprint) {
            mapping.setDown(!mapping.isDown());
            return true;
        }
        if (mapping == mc.options.keyJump) {
            if (mc.player.isOnGround()) {
                mc.player.jumpFromGround();
            }
            return true;
        }
        if (mapping == mc.options.keyChat) {
            mc.setScreen(new net.minecraft.client.gui.screens.ChatScreen(""));
            return true;
        }
        if (mapping == mc.options.keyCommand) {
            mc.setScreen(new net.minecraft.client.gui.screens.ChatScreen("/"));
            return true;
        }
        if (mapping == mc.options.keySocialInteractions) {
            mc.setScreen(new net.minecraft.client.gui.screens.social.SocialInteractionsScreen());
            return true;
        }
        if (mapping == mc.options.keyPlayerList) {
            boolean shown = mc.options.keyPlayerList.isDown();
            mc.options.keyPlayerList.setDown(!shown);
            return true;
        }
        return false;
    }

    private void trigger(SlotData data) {
        if (data == null) {
            return;
        }
        String cmd = data.getCommand();
        if (cmd != null && !cmd.isEmpty()) {
            String send = cmd.startsWith("/") ? cmd.substring(1) : cmd;
            KeyPanelMod.LOGGER.info("[key_panel] run command: {}", send);
            if (Minecraft.getInstance().player != null) {
                // 1.19.2：public 的发指令入口是 commandSigned（1.20+ 是 connection.sendCommand）
                Minecraft.getInstance().player.commandSigned(send, null);
            }
            this.onClose();
            return;
        }
        if (data.isEmpty() || data.getKeyBinding() == null || data.getKeyBinding().isEmpty()) {
            KeyPanelMod.LOGGER.info("[key_panel] slot {} is empty (no command / no key binding)", data.getIndex());
            return;
        }
        KeyMapping mapping = findKeyMapping(data.getKeyBinding());
        if (mapping == null) {
            KeyPanelMod.LOGGER.info("[key_panel] key binding '{}' not found", data.getKeyBinding());
            return;
        }
        KeyPanelMod.LOGGER.info("[key_panel] trigger slot {} -> {}", data.getIndex(), data.getKeyBinding());
        boolean keepOpen = mapping == Minecraft.getInstance().options.keyShift
                || mapping == Minecraft.getInstance().options.keySprint
                || mapping == Minecraft.getInstance().options.keyPlayerList;
        if (!keepOpen) {
            this.onClose();
        }
        if (runVanillaAction(mapping)) {
            return;
        }
        // 触发方式：先把本格对应的虚拟键临时绑定到目标功能上，再把这个键交给原版按键入口处理，
        // 由原版完成点击计数、按下状态与按键匹配（详见 KeyPanelClientEvents#fireKeyEvent）。
        KeyMapping virtual = KeyBindings.virtual(data.getIndex());
        boolean rebound = rebindToVirtual(mapping, virtual);
        com.mojang.blaze3d.platform.InputConstants.Key key =
                rebound && virtual != null ? virtual.getKey() : mapping.getKey();
        if (key == com.mojang.blaze3d.platform.InputConstants.UNKNOWN) {
            KeyPanelMod.LOGGER.info("[key_panel] 槽位 {} 的目标功能没有可用键码，跳过模拟", data.getIndex());
            return;
        }
        int before = KeyCompat.readClickCount(mapping);
        KeyPanelClientEvents.fireKeyEvent(key, 0, org.lwjgl.glfw.GLFW.GLFW_PRESS);
        KeyPanelClientEvents.fireKeyEvent(key, 0, org.lwjgl.glfw.GLFW.GLFW_RELEASE);
        // 原版入口处理完毕后立即还原，使该功能在「按键设置」中的键位始终保持不变。
        restoreVirtualHolder();
        KeyPanelClientEvents.pressTemporarily(mapping);   // 还原后继续维持按下状态，供长按类功能使用
        KeyPanelMod.LOGGER.info("[key_panel] 模拟按键 {} → {}（clickCount {} -> {}）",
                key.getName(), data.getKeyBinding(), before, KeyCompat.readClickCount(mapping));
    }

    public void saveSlots() {
        ConfigManager.save(this.slots);
    }

    private static boolean isActiveToggle(SlotData data) {
        if (data == null || data.isEmpty() || data.getKeyBinding() == null) {
            return false;
        }
        KeyMapping mapping = findKeyMapping(data.getKeyBinding());
        return mapping != null && isToggleKey(mapping) && mapping.isDown();
    }

    private static boolean isToggleKey(KeyMapping mapping) {
        Minecraft mc = Minecraft.getInstance();
        return mapping == mc.options.keyShift || mapping == mc.options.keySprint
                || mapping == mc.options.keyUp || mapping == mc.options.keyDown;
    }

    private static KeyMapping findKeyMapping(String name) {
        for (KeyMapping mapping : Minecraft.getInstance().options.keyMappings) {
            if (mapping.getName().equals(name)) {
                return mapping;
            }
        }
        return null;
    }

    private static ItemStack stackOf(SlotData data) {
        if (data == null || data.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ResourceLocation id = ResourceLocation.tryParse(data.getItemId());
        if (id == null) {
            return ItemStack.EMPTY;
        }
        // 1.19.2：原版注册表是 Registry.ITEM（BuiltInRegistries 1.19.3+ 才有）
        var item = Registry.ITEM.get(id);
        if (item == null || item == Items.AIR) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(item);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
