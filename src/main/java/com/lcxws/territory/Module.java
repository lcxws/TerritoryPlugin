package com.lcxws.territory;

import org.bukkit.Material;

/**
 * 进阶保护模块：每个领地一次性买断（价格见 config module-price），购买后可随时开关。
 */
public enum Module {

    EXPLOSION("防爆炸", Material.TNT, "阻止TNT/苦力怕/水晶等炸毁领地内方块"),
    MOB_GRIEF("防生物破坏", Material.ZOMBIE_HEAD, "阻止僵尸/末影人/凋灵等生物破坏领地内方块"),
    PVP("禁用PvP", Material.IRON_SWORD, "阻止领地内玩家互相伤害"),
    FIRE("防火焰蔓延", Material.FLINT_AND_STEEL, "阻止火焰在领地内蔓延或点燃方块");

    public final String display;
    public final Material icon;
    public final String desc;

    Module(String display, Material icon, String desc) {
        this.display = display;
        this.icon = icon;
        this.desc = desc;
    }

    public static Module byDisplay(String display) {
        for (Module m : values()) {
            if (m.display.equals(display)) return m;
        }
        return null;
    }
}
