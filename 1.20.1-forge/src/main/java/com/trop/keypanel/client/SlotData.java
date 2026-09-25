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
