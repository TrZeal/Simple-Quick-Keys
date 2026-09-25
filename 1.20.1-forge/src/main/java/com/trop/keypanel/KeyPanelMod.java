package com.trop.keypanel;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

@Mod(KeyPanelMod.MODID)
public class KeyPanelMod {

    public static final String MODID = "key_panel";
    public static final Logger LOGGER = LogUtils.getLogger();

    public KeyPanelMod() {
        LOGGER.info("[key_panel] 客户端快捷功能面板已加载");
    }
}
