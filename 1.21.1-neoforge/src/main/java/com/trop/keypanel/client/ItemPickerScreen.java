package com.trop.keypanel.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ItemPickerScreen extends Screen {

    private static final int COLS = 16;
    private static final int ROWS = 9;
    private static final int CELL = 18;

    private final Screen parent;
    private final SlotData data;

    private final List<ItemStack> all = new ArrayList<>();
    private final List<ItemStack> filtered = new ArrayList<>();
    private final List<String> names = new ArrayList<>();

    private EditBox search;
    private int scroll;
    private boolean draggingBar;
    private int panelX;
    private int panelY;
    private int gridW;
    private int gridH;
    private int hovered = -1;

    public ItemPickerScreen(Screen parent, SlotData data) {
        super(Component.literal("选择图标"));
        this.parent = parent;
        this.data = data;
    }

    @Override
    protected void init() {
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) {
                continue;
            }
            ItemStack stack = new ItemStack(item);
            this.all.add(stack);
            this.names.add(stack.getHoverName().getString().toLowerCase(Locale.ROOT));
        }
        this.gridW = COLS * CELL;
        this.gridH = ROWS * CELL;
        this.panelX = (this.width - (gridW + 24)) / 2;
        this.panelY = (this.height - (gridH + 92)) / 2;

        this.search = new EditBox(this.font, panelX + 12, panelY + 24, gridW, 16, Component.literal("搜索"));
        this.search.setMaxLength(48);
        this.search.setHint(Component.literal("输入关键词搜索物品…"));
        this.search.setResponder(s -> {
            this.scroll = 0;
            applyFilter(s);
        });
        this.addRenderableWidget(this.search);
        this.setInitialFocus(this.search);
        applyFilter("");

        this.addRenderableWidget(Button.builder(Component.literal("返回"), b -> back())
                .bounds(panelX + gridW / 2 - 30 + 12, panelY + gridH + 66, 60, 18).build());
    }

    private void applyFilter(String query) {
        this.filtered.clear();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < all.size(); i++) {
            ItemStack stack = all.get(i);
            if (q.isEmpty() || names.get(i).contains(q)
                    || BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().contains(q)) {
                filtered.add(stack);
            }
        }
    }

    private int gridY0() {
        return panelY + 46;
    }

    private int maxScroll() {
        int rows = (filtered.size() + COLS - 1) / COLS;
        return Math.max(0, rows - ROWS);
    }

    private void back() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(panelX, panelY, panelX + gridW + 24, panelY + gridH + 92, 0xF0060A0B);
        g.renderOutline(panelX, panelY, gridW + 24, gridH + 92, 0xFF2FD9D9);
        g.drawString(this.font, "选择图标（点击即选中）", panelX + 12, panelY + 10, 0xFFBFFFFF, false);
        g.fill(panelX + 12, panelY + 46, panelX + 12 + gridW, panelY + 46 + gridH, 0xFF0A0E0F);

        this.hovered = -1;
        int first = scroll * COLS;
        for (int i = 0; i < COLS * ROWS; i++) {
            int idx = first + i;
            if (idx >= filtered.size()) {
                break;
            }
            int col = i % COLS;
            int row = i / COLS;
            int x = panelX + 12 + col * CELL;
            int y = panelY + 46 + row * CELL;
            boolean hover = mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL;
            if (hover) {
                this.hovered = idx;
                g.fill(x, y, x + CELL, y + CELL, 0xFF17484E);
                g.renderOutline(x, y, CELL, CELL, 0xFF45F0F0);
            }
            g.renderItem(filtered.get(idx), x + 2, y + 2);
        }
        int rows = (filtered.size() + COLS - 1) / COLS;
        if (rows > ROWS) {
            int barH = Math.max(14, gridH * ROWS / rows);
            int barY = gridY0() + (gridH - barH) * scroll / Math.max(1, maxScroll());
            int barX = panelX + 12 + gridW + 3;
            g.fill(barX, gridY0(), barX + 6, gridY0() + gridH, 0xFF12262A);
            g.fill(barX, barY, barX + 6, barY + barH, 0xFF45F0F0);
        }
        super.render(g, mouseX, mouseY, partialTick);
        g.flush();

        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, 400.0F);
        if (hovered >= 0 && hovered < filtered.size()) {
            ItemStack stack = filtered.get(hovered);
            String name = stack.getHoverName().getString();
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            int w = Math.max(this.font.width(name), this.font.width(id)) + 8;
            int tx = Math.min(mouseX + 8, this.width - w - 4);
            int ty = mouseY + 10;
            g.fill(tx, ty, tx + w, ty + 22, 0xF0060A0B);
            g.renderOutline(tx, ty, w, 22, 0xFF45F0F0);
            g.drawString(this.font, name, tx + 4, ty + 3, 0xFFFFFFFF, false);
            g.drawString(this.font, id, tx + 4, ty + 13, 0xFF7FD8D8, false);
        }
        g.pose().popPose();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int barX = panelX + 12 + gridW + 3;
        if (button == 0 && mouseX >= barX && mouseX < barX + 6 && mouseY >= gridY0() && mouseY < gridY0() + gridH) {
            this.draggingBar = true;
            dragTo(mouseY);
            return true;
        }
        if (button == 0 && hovered >= 0 && hovered < filtered.size()) {
            ItemStack stack = filtered.get(hovered);
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            data.setItemId(id.toString());
            if (data.getCustomName() == null || data.getCustomName().isEmpty()) {
                data.setCustomName(stack.getHoverName().getString());
            }
            back();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingBar) {
            dragTo(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.draggingBar = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void dragTo(double mouseY) {
        int rows = (filtered.size() + COLS - 1) / COLS;
        int barH = Math.max(14, gridH * ROWS / rows);
        double t = (mouseY - gridY0() - barH / 2.0D) / Math.max(1.0D, gridH - barH);
        this.scroll = Math.max(0, Math.min(maxScroll(), (int) Math.round(t * maxScroll())));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
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
