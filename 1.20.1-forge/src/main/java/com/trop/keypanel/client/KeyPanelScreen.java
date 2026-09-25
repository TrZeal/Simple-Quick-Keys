package com.trop.keypanel.client;

import com.trop.keypanel.KeyPanelMod;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.client.settings.KeyModifier;
import org.lwjgl.glfw.GLFW;

import java.util.List;

public class KeyPanelScreen extends Screen {

    private static final ResourceLocation TEXTURE =
            new ResourceLocation("key_panel", "textures/gui/key_panel.png");
    private static final int TEX_W = 1024;
    private static final int TEX_H = 1024;
    private int cardUNormal;
    private int cardUEmpty;
    private int cardUHover;
    private int cardVNormal;
    private int cardVEmpty;
    private int cardVHover;

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
    private int pad = TIERS[0][3];
    private int iconScale = TIERS[0][4];
    private int panelU;
    private int panelV;
    private int panelW;
    private int panelH;
    private static final int CORNER = 12;

    private static final int COL_BG_TOP    = 0xFF0A0E0F;
    private static final int COL_BG_BOTTOM = 0xFF040607;
    private static final int COL_EDGE      = 0xCC2FD9D9;
    private static final int COL_EDGE_DIM  = 0x552FD9D9;
    private static final int COL_ARC       = 0xFF8FF7F7;
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
    private boolean hoverPrev;
    private boolean hoverNext;

    public KeyPanelScreen() {
        super(Component.empty());
        this.slots = ConfigManager.load();
    }

    @Override
    protected void init() {
        applyTier();
        this.panelX = (this.width - panelW) / 2;
        this.panelY = (this.height - panelH) / 2;
        this.gridX = this.panelX + pad;
        this.gridY = this.panelY + pad;
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
            int w = COLS * t[0] + (COLS - 1) * t[2] + t[3] * 2;
            int h = ROWS * t[1] + (ROWS - 1) * t[2] + t[3] * 2 + PAGEBAR;
            if (w <= this.width - 6 && h <= this.height - 6) {
                cardW = t[0];
                cardH = t[1];
                gap = t[2];
                pad = t[3];
                applyTierFields(t);
                panelW = w;
                panelH = h;
                return;
            }
        }
        int[] t = TIERS[TIERS.length - 1];
        cardW = t[0];
        cardH = t[1];
        gap = t[2];
        pad = t[3];
        applyTierFields(t);
        panelW = COLS * t[0] + (COLS - 1) * t[2] + t[3] * 2;
        panelH = ROWS * t[1] + (ROWS - 1) * t[2] + t[3] * 2 + PAGEBAR;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }


    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        this.hovered = cardAt(mouseX, mouseY);
        int[] lp = arrowRect(true);
        int[] rp = arrowRect(false);
        this.hoverPrev = mouseX >= lp[0] && mouseX < lp[2] && mouseY >= lp[1] && mouseY < lp[3];
        this.hoverNext = mouseX >= rp[0] && mouseX < rp[2] && mouseY >= rp[1] && mouseY < rp[3];

        drawPanel(g);
        drawCards(g);
        drawPageBar(g);

        super.render(g, mouseX, mouseY, partialTick);
        g.flush();
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, 400.0F);
        drawHoverTip(g, mouseX, mouseY);
        g.pose().popPose();
        RenderSystem.disableBlend();
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

    private String moveHint() {
        return moveSource < 0 ? "移动模式：点一个槽位选中（再点按钮退出）" : "移动模式：再点一个槽位完成移动（右键取消）";
    }

    private void drawPanel(GuiGraphics g) {
        g.blit(TEXTURE, panelX, panelY, panelW, panelH,
                panelU, panelV, panelW, panelH, TEX_W, TEX_H);
    }

    private void drawCards(GuiGraphics g) {
        for (int i = 0; i < COLS * ROWS; i++) {
            int slotIndex = page * ConfigManager.PAGE_SIZE + i;
            if (slotIndex >= slots.size()) {
                break;
            }
            SlotData data = slots.get(slotIndex);
            int col = i % COLS;
            int row = i / COLS;
            int x = gridX + col * (cardW + gap);
            int y = gridY + row * (cardH + gap);
            boolean hover = i == hovered;
            boolean active = isActiveToggle(data);
            boolean empty = data.isEmpty();
            int drawY = (hover && !empty) ? y - 1 : y;

            int u;
            int v;
            if (empty && !active) {
                u = cardUEmpty;
                v = cardVEmpty;
            } else if (hover || active) {
                u = cardUHover;
                v = cardVHover;
            } else {
                u = cardUNormal;
                v = cardVNormal;
            }
            g.blit(TEXTURE, x, drawY, cardW, cardH, u, v, cardW, cardH, TEX_W, TEX_H);

            if (empty) {
                continue;
            }
            ItemStack stack = stackOf(data);
            if (!stack.isEmpty()) {
                g.pose().pushPose();
                float iconPx = 16.0F * iconScale;
                float iconY = drawY + (iconPx >= 32.0F ? 4.0F : (cardH - iconPx) / 2.0F - 3.0F);
                g.pose().translate(x + (cardW - iconPx) / 2.0F, iconY, 0.0F);
                g.pose().scale(iconScale, iconScale, 1.0F);
                g.renderItem(stack, 0, 0);
                g.pose().popPose();
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
                g.drawString(this.font, shown, x + (cardW - this.font.width(shown)) / 2, drawY + cardH - 12,
                        (hover || active) ? COL_NAME_HOVER : COL_NAME, false);
            }
            if (moveMode && slotIndex == moveSource) {
                g.renderOutline(x, drawY, cardW, cardH, 0xFF45F0F0);
                g.fill(x + 1, drawY + 1, x + cardW - 1, drawY + cardH - 1, 0x4445F0F0);
            }
            if (active) {
                g.fill(x + 8, drawY + cardH - 6, x + cardW - 8, drawY + cardH - 5, 0xFF45F0F0);
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

    private void drawHoverTip(GuiGraphics g, int mouseX, int mouseY) {
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
            how = "命令：" + data.getCommand();
        } else if (data.getKeyBinding() == null || data.getKeyBinding().isEmpty()) {
            how = "未绑定功能";
        } else {
            how = "功能：" + Component.translatable(data.getKeyBinding()).getString();
        }
        int w = Math.max(this.font.width(name), this.font.width(how)) + 10;
        int h = 24;
        int tx = Math.max(2, Math.min(mouseX + 14, this.width - w - 4));
        int ty = Math.max(2, Math.min(mouseY - h - 8, this.height - h - 4));
        g.fill(tx, ty, tx + w, ty + h, 0xF0060A0B);
        g.renderOutline(tx, ty, w, h, 0xFF45F0F0);
        g.drawString(this.font, name, tx + 5, ty + 4, 0xFFFFFFFF, false);
        g.drawString(this.font, how, tx + 5, ty + 14, 0xFF7FD8D8, false);
    }

    private void drawPageBar(GuiGraphics g) {
        int barY = panelY + panelH - PAGEBAR;
        int cy = barY + PAGEBAR / 2;
        int pages = ConfigManager.PAGES;
        g.fill(panelX + 18, barY, panelX + panelW - 18, barY + 1, 0x332FD9D9);
        boolean canPrev = page > 0;
        arrow(g, panelX + 30, cy, true, canPrev ? (hoverPrev ? 0xFF8FF7F7 : COL_EDGE) : 0x33FFFFFF);
        boolean canNext = page < pages - 1;
        arrow(g, panelX + panelW - 30, cy, false, canNext ? (hoverNext ? 0xFF8FF7F7 : COL_EDGE) : 0x33FFFFFF);
        String txt = moveMode ? moveHint() : ((page + 1) + " / " + pages);
        g.drawString(this.font, txt, panelX + (panelW - this.font.width(txt)) / 2, cy - 4,
                moveMode ? 0xFF8FF7F7 : COL_HINT, false);
        int[] mb = moveButtonRect();
        g.blit(TEXTURE, mb[0], mb[1], 84, 18, 700, moveMode ? 430 : 400, 84, 18, TEX_W, TEX_H);
        String label = moveMode ? "移动中…" : "移动槽位";
        g.drawString(this.font, label, mb[0] + (84 - this.font.width(label)) / 2, mb[1] + 5,
                moveMode ? 0xFF3E8A8C : 0xFFBFFFFF, false);
    }

    private int[] arrowRect(boolean left) {
        int barY = panelY + panelH - PAGEBAR;
        int cy = barY + PAGEBAR / 2;
        int cx = left ? panelX + 30 : panelX + panelW - 30;
        return new int[]{cx - 8, cy - 8, cx + 8, cy + 8};
    }

    private static void arrow(GuiGraphics g, int cx, int cy, boolean left, int color) {
        int size = 5;
        for (int i = 0; i <= size; i++) {
            int px = left ? cx - size + i : cx + size - i;
            g.fill(px, cy - i, px + 1, cy + i + 1, color);
        }
    }



    private static int cornerInset(int row, int height, int r) {
        if (row < r) {
            return r - row - 1;
        }
        if (row >= height - r) {
            return r - (height - row - 1) - 1;
        }
        return 0;
    }

    private static void outlineRounded(GuiGraphics g, int x, int y, int w, int h, int r, int color) {
        for (int i = 0; i < h; i++) {
            int inset = cornerInset(i, h, r);
            if (i == 0 || i == h - 1) {
                g.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
                continue;
            }
            g.fill(x + inset, y + i, x + inset + 1, y + i + 1, color);
            g.fill(x + w - inset - 1, y + i, x + w - inset, y + i + 1, color);
        }
    }

    private static int lerpColor(int a, int b, float t) {
        t = Math.max(0.0F, Math.min(1.0F, t));
        int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int ca = (int) (aa + (ba - aa) * t);
        int cr = (int) (ar + (br - ar) * t);
        int cg = (int) (ag + (bg - ag) * t);
        int cb = (int) (ab + (bb - ab) * t);
        return (ca << 24) | (cr << 16) | (cg << 8) | cb;
    }

    private int cardAt(double mouseX, double mouseY) {
        for (int i = 0; i < COLS * ROWS; i++) {
            int col = i % COLS;
            int row = i / COLS;
            int x = gridX + col * (cardW + gap);
            int y = gridY + row * (cardH + gap) - 1;
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
            KeyPanelMod.LOGGER.info("[key_panel] move mode = {}", moveMode);
            return true;
        }
        int index = cardAt(mouseX, mouseY);
        if (moveMode && index >= 0) {
            int slotIndex = page * ConfigManager.PAGE_SIZE + index;
            if (button == 1) {
                moveMode = false;
                moveSource = -1;
                return true;
            }
            if (slotIndex < slots.size()) {
                if (moveSource < 0) {
                    moveSource = slotIndex;
                } else {
                    swapSlots(moveSource, slotIndex);
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
                mc.setScreen(new net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen(
                        mc.player, mc.player.connection.enabledFeatures(), mc.options.operatorItemsTab().get()));
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
            if (mc.player.onGround()) {
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
                Minecraft.getInstance().player.connection.sendCommand(send);
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
        var item = BuiltInRegistries.ITEM.get(id);
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
