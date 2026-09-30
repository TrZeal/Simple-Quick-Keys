package com.trop.keypanel.client;

import com.google.gson.annotations.SerializedName;

public class SlotData {

    @SerializedName("index")
    private int index;

    @SerializedName("item_id")
    private String itemId;

    @SerializedName("custom_name")
    private String customName;

    @SerializedName("key_binding")
    private String keyBinding;

    @SerializedName("command")
    private String command = "";

    /**
     * 方案②：malilib 系模组（Tweakeroo / MiniHUD / Litematica 等）热键 ID，格式 {@code 模组名|配置名}。
     * 空串 = 未设置。与 {@link #keyBinding} <b>互斥</b>（选热键就清 keyBinding，反之亦然）；
     * 旧配置缺该字段时 Gson 留 null，getter 统一按空串处理，行为与升级前完全一致。
     */
    @SerializedName("hotkey")
    private String hotkey = "";

    /** 该格卡片的背景颜色（0xRRGGBB）；-1 表示使用默认（不着色）。 */
    @SerializedName("color")
    private int color = -1;

    public SlotData() {
        this(0, "", "", "");
    }

    public SlotData(int index, String itemId, String customName, String keyBinding) {
        this(index, itemId, customName, keyBinding, "");
    }

    public SlotData(int index, String itemId, String customName, String keyBinding, String command) {
        this.index = index;
        this.itemId = itemId == null ? "" : itemId;
        this.customName = customName == null ? "" : customName;
        this.keyBinding = keyBinding == null ? "" : keyBinding;
        this.command = command == null ? "" : command;
    }

    public String getCommand() {
        return command == null ? "" : command;
    }

    public void setCommand(String command) {
        this.command = command == null ? "" : command;
    }

    /** malilib 热键 ID（{@code 模组名|配置名}）；空串表示该格没设 malilib 热键。 */
    public String getHotkey() {
        return hotkey == null ? "" : hotkey;
    }

    public void setHotkey(String hotkey) {
        this.hotkey = hotkey == null ? "" : hotkey;
    }

    public int getColor() {
        return color;
    }

    public void setColor(int color) {
        this.color = color;
    }

    public int getIndex() {
        return index;
    }

    public String getItemId() {
        return itemId;
    }

    public String getCustomName() {
        return customName;
    }

    public String getKeyBinding() {
        return keyBinding;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public void setItemId(String itemId) {
        this.itemId = itemId;
    }

    public void setCustomName(String customName) {
        this.customName = customName;
    }

    public void setKeyBinding(String keyBinding) {
        this.keyBinding = keyBinding;
    }

    public boolean isEmpty() {
        return itemId == null || itemId.isEmpty();
    }

    public static class File {
        @SerializedName("slots")
        public java.util.List<SlotData> slots = new java.util.ArrayList<>();
    }
}
