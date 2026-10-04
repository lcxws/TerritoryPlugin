package com.lcxws.territory;

import org.bukkit.Material;

/**
 * 进阶保护模块：每个领地一次性买断（价格可按模块在 config module-prices 配置，
 * 未配置的用 module-price 兜底），购买后可随时开关。
 */
public enum Module {

    EXPLOSION("防爆炸", Material.TNT, "阻止TNT/苦力怕/水晶等炸毁领地内方块"),
    MOB_GRIEF("防生物破坏", Material.ZOMBIE_HEAD, "阻止僵尸/末影人/凋灵等敌对生物破坏领地内方块"),
    PVP("禁用PvP", Material.IRON_SWORD, "阻止领地内玩家互相伤害"),
    FIRE("防火焰蔓延", Material.FLINT_AND_STEEL, "阻止火焰在领地内蔓延或点燃方块"),
    MOB_SPAWN("禁止刷怪", Material.ZOMBIE_SPAWN_EGG, "阻止所有敌对生物在领地内生成（含刷怪笼/刷怪蛋）"),
    LIGHTNING("防雷击", Material.LIGHTNING_ROD, "阻止闪电在领地内落地（无起火无伤害）"),
    ANIMAL_GRIEF("防动物破坏", Material.WHEAT, "阻止羊吃草、兔子啃作物等动物破坏方块"),
    NO_PICKUP("禁访客拾取", Material.GOLD_INGOT, "非白名单成员无法捡起领地内的掉落物"),
    NO_DROP("禁访客丢弃", Material.SNOWBALL, "非白名单成员无法在领地内丢弃物品"),
    NO_TELEPORT("禁访客传送", Material.ENDER_EYE, "非白名单成员无法传送进入领地（末影珍珠/TP均拦截）"),
    LEAF_DECAY("防树叶腐烂", Material.OAK_LEAVES, "阻止领地内树叶自然腐烂消失");

    public final String display;
    public final Material icon;
    public final String desc;

    Module(String display, Material icon, String desc) {
        this.display = display;
        this.icon = icon;
        this.desc = desc;
    }

    /** 模块价格：module-prices.<NAME> 优先，未配置回退 module-price */
    public double price(TerritoryPlugin plugin) {
        double fallback = plugin.getConfig().getDouble("module-price", 500);
        return plugin.getConfig().getDouble("module-prices." + name(), fallback);
    }

    public static Module byDisplay(String display) {
        for (Module m : values()) {
            if (m.display.equals(display)) return m;
        }
        return null;
    }
}
