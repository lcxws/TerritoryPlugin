package com.lcxws.territory.listener;

import com.lcxws.territory.Module;
import com.lcxws.territory.Territory;
import com.lcxws.territory.TerritoryPlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.weather.LightningStrikeEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;

/**
 * 保护监听器（快速路径优先）：
 * 每个事件先做一次区块索引查找（O(1)），无领地或信任成员直接放行，
 * 仅对"他人领地内的非信任玩家"与"已购模块的环境威胁"做拦截。
 *
 * 权限三级：领主/白名单 > 访客（只能看：不能破坏/放置/开门/按钮/箱子/碰实体）。
 * OP 本身【不】越权，只有 config 超管名单内的玩家且开启 bypass 才无视保护。
 */
public class ProtectionListener implements Listener {

    private final TerritoryPlugin plugin;

    public ProtectionListener(TerritoryPlugin plugin) {
        this.plugin = plugin;
    }

    /* ========================= 公共判断 ========================= */

    private boolean trusted(Player p, Territory t) {
        return t.isTrusted(p.getUniqueId()) || plugin.getTerritories().bypassProtection(p);
    }

    private void deny(Player p, Territory t) {
        plugin.getMessages().send(p, "protect-denied",
                "%owner%", plugin.getTerritories().nameFor(t.getOwner()));
    }

    private Territory tAt(Location loc) {
        return plugin.getTerritories().getAt(loc);
    }

    private static Player resolveAttacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) return p;
        return null;
    }

    private static boolean sameTerritory(Territory a, Territory b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.getId().equals(b.getId());
    }

    /* ========================= 方块 ========================= */

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player p = event.getPlayer();
        Territory t = tAt(event.getBlock().getLocation());
        if (t == null || trusted(p, t)) return;
        event.setCancelled(true);
        deny(p, t);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player p = event.getPlayer();
        Territory t = tAt(event.getBlock().getLocation());
        if (t == null || trusted(p, t)) return;
        event.setCancelled(true);
        deny(p, t);
    }

    /** 访客：不能踩压力板/踩农田（PHYSICAL），不能使用门/按钮/箱子等一切右键交互 */
    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.PHYSICAL) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        Player p = event.getPlayer();
        Territory t = tAt(block.getLocation());
        if (t == null || trusted(p, t)) return;
        // 右键会主手/副手各触发一次，副手直接忽略避免重复提示
        if (action == Action.RIGHT_CLICK_BLOCK && event.getHand() == EquipmentSlot.OFF_HAND) return;
        // 访客保留的只读交互：末影箱（私人空间）、讲台（阅读）
        if (action == Action.RIGHT_CLICK_BLOCK) {
            Material m = block.getType();
            if (m == Material.ENDER_CHEST || m == Material.LECTERN) return;
        }
        event.setCancelled(true);
        deny(p, t);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Player p = event.getPlayer();
        Territory t = tAt(event.getBlock().getLocation());
        if (t == null || trusted(p, t)) return;
        event.setCancelled(true);
        deny(p, t);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        Player p = event.getPlayer();
        Territory t = tAt(event.getBlock().getLocation());
        if (t == null || trusted(p, t)) return;
        event.setCancelled(true);
        deny(p, t);
    }

    /* ========================= 实体 ========================= */

    /** 禁PvP模块 + 基础的实体保护（动物/村民/盔甲架/物品展示框等不许访客伤害） */
    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Player attacker = resolveAttacker(event.getDamager());
        Entity victim = event.getEntity();
        if (victim instanceof Player) {
            if (attacker == null) return;
            Territory t = tAt(victim.getLocation());
            if (t != null && t.moduleEnabled(Module.PVP)) {
                event.setCancelled(true);
                plugin.getMessages().send(attacker, "pvp-denied");
            }
            return;
        }
        if (attacker == null) return;
        Territory t = tAt(victim.getLocation());
        if (t != null && !trusted(attacker, t)) {
            event.setCancelled(true);
            deny(attacker, t);
        }
    }

    /** 访客不能与领地内实体交互（物品展示框旋转、村民交易、喂食、上船等） */
    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Player p = event.getPlayer();
        Territory t = tAt(event.getRightClicked().getLocation());
        if (t == null || trusted(p, t)) return;
        event.setCancelled(true);
        deny(p, t);
    }

    /** 盔甲架摆放/调整（与上一条同样处理） */
    @EventHandler(ignoreCancelled = true)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        Player p = event.getPlayer();
        Territory t = tAt(event.getRightClicked().getLocation());
        if (t == null || trusted(p, t)) return;
        event.setCancelled(true);
        deny(p, t);
    }

    /** 盔甲架/船/矿车/末影水晶等实体放置 */
    @EventHandler(ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        Player p = event.getPlayer();
        if (p == null) return;
        Territory t = tAt(event.getBlock().getLocation());
        if (t == null || trusted(p, t)) return;
        event.setCancelled(true);
        deny(p, t);
    }

    @EventHandler(ignoreCancelled = true)
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        Player p = resolveAttacker(event.getAttacker());
        if (p == null) return;
        Territory t = tAt(event.getVehicle().getLocation());
        if (t == null || trusted(p, t)) return;
        event.setCancelled(true);
        deny(p, t);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        Player p = resolveAttacker(event.getRemover());
        if (p == null) return;
        Territory t = tAt(event.getEntity().getLocation());
        if (t == null || trusted(p, t)) return;
        event.setCancelled(true);
        deny(p, t);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        Player p = event.getPlayer();
        if (p == null) return;
        Territory t = tAt(event.getEntity().getLocation());
        if (t == null || trusted(p, t)) return;
        event.setCancelled(true);
        deny(p, t);
    }

    /* ========================= 进阶模块：防爆炸 ========================= */

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(b -> {
            Territory t = tAt(b.getLocation());
            return t != null && t.moduleEnabled(Module.EXPLOSION);
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(b -> {
            Territory t = tAt(b.getLocation());
            return t != null && t.moduleEnabled(Module.EXPLOSION);
        });
    }

    /* ========================= 进阶模块：防生物破坏 ========================= */

    /** 敌对生物破坏走防生物破坏模块，动物（羊吃草/兔子啃作物等）走防动物破坏模块 */
    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (event.getEntity() instanceof Player) return; // 玩家行为由基础保护处理
        Territory t = tAt(event.getBlock().getLocation());
        if (t == null) return;
        if (event.getEntity() instanceof Monster) {
            if (t.moduleEnabled(Module.MOB_GRIEF)) {
                event.setCancelled(true);
            }
        } else if (event.getEntity() instanceof Animals) {
            if (t.moduleEnabled(Module.ANIMAL_GRIEF)) {
                event.setCancelled(true);
            }
        }
    }

    /* ========================= 进阶模块：禁止刷怪 ========================= */

    /** 阻止敌对生物在领地内生成（自然生成/刷怪笼/刷怪蛋/增援等一律拦截） */
    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Monster)) return;
        Territory t = tAt(event.getLocation());
        if (t != null && t.moduleEnabled(Module.MOB_SPAWN)) {
            event.setCancelled(true);
        }
    }

    /* ========================= 进阶模块：防雷击 ========================= */

    @EventHandler(ignoreCancelled = true)
    public void onLightningStrike(LightningStrikeEvent event) {
        Territory t = tAt(event.getLightning().getLocation());
        if (t != null && t.moduleEnabled(Module.LIGHTNING)) {
            event.setCancelled(true);
        }
    }

    /* ========================= 进阶模块：禁访客拾取 / 禁访客丢弃 ========================= */

    /** 非白名单成员无法捡起领地内掉落物（静默拦截，防刷屏） */
    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        Territory t = tAt(event.getItem().getLocation());
        if (t == null || trusted(p, t)) return;
        event.setCancelled(true);
    }

    /** 非白名单成员无法在领地内丢弃物品（静默拦截） */
    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Player p = event.getPlayer();
        Territory t = tAt(event.getPlayer().getLocation());
        if (t == null || trusted(p, t)) return;
        event.setCancelled(true);
    }

    /* ========================= 进阶模块：防树叶腐烂 ========================= */

    @EventHandler(ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) {
        Territory t = tAt(event.getBlock().getLocation());
        if (t != null && t.moduleEnabled(Module.LEAF_DECAY)) {
            event.setCancelled(true);
        }
    }

    /* ========================= 进阶模块：防火焰蔓延 ========================= */

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        Territory t = tAt(event.getBlock().getLocation());
        if (t == null) return;
        Player igniter = event.getPlayer();
        if (igniter != null) {
            if (trusted(igniter, t)) return;
            event.setCancelled(true);
            deny(igniter, t);
            return;
        }
        // 环境火源（蔓延/岩浆/闪电/爆炸）由防火模块拦截
        if (t.moduleEnabled(Module.FIRE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFireSpread(BlockSpreadEvent event) {
        if (event.getNewState().getType() != Material.FIRE) return;
        Territory t = tAt(event.getBlock().getLocation());
        if (t != null && t.moduleEnabled(Module.FIRE)) {
            event.setCancelled(true);
        }
    }

    /* ========================= 活塞跨边界保护 ========================= */

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        Territory pistonT = tAt(event.getBlock().getLocation());
        for (Block b : event.getBlocks()) {
            if (!sameTerritory(pistonT, tAt(b.getLocation()))
                    || !sameTerritory(pistonT, tAt(b.getRelative(event.getDirection()).getLocation()))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        Territory pistonT = tAt(event.getBlock().getLocation());
        for (Block b : event.getBlocks()) {
            if (!sameTerritory(pistonT, tAt(b.getLocation()))
                    || !sameTerritory(pistonT, tAt(b.getRelative(event.getDirection()).getLocation()))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /* ========================= 以下漏洞向量来自对 GriefPrevention / CrashClaim 的对照 ========================= */

    /** 液体流动：只拦"跨界流入领地"（GP 同款语义：向下流放行，领地内流出不管） */
    @EventHandler(ignoreCancelled = true)
    public void onLiquidFlow(BlockFromToEvent event) {
        if (event.getFace() == BlockFace.DOWN) return;
        Territory fromT = tAt(event.getBlock().getLocation());
        Territory toT = tAt(event.getToBlock().getLocation());
        if (toT != null && !sameTerritory(fromT, toT)) {
            event.setCancelled(true);
        }
    }

    /** 访客可以阅读讲台上的书，但不能取走（GP 同款：取书需要访问信任） */
    @EventHandler(ignoreCancelled = true)
    public void onTakeLecternBook(PlayerTakeLecternBookEvent event) {
        Territory t = tAt(event.getLectern().getLocation());
        if (t == null || trusted(event.getPlayer(), t)) return;
        event.setCancelled(true);
        deny(event.getPlayer(), t);
    }

    /** 防漏斗隔墙吸取领地内箱子：来源容器在领地内而搬运方（漏斗/漏斗矿车）不在同一领地时取消。
     *  快速路径：来源容器不在任何领地内直接放行。 */
    @EventHandler(ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        Location sourceLoc = holderLocation(event.getSource().getHolder());
        if (sourceLoc == null) return;
        Territory sourceT = tAt(sourceLoc);
        if (sourceT == null) return;
        Location initLoc = holderLocation(event.getInitiator().getHolder());
        Territory initT = initLoc != null ? tAt(initLoc) : null;
        if (!sameTerritory(sourceT, initT)) {
            event.setCancelled(true);
        }
    }

    private static Location holderLocation(InventoryHolder holder) {
        if (holder instanceof BlockInventoryHolder b) return b.getBlock().getLocation();
        if (holder instanceof DoubleChest dc) return dc.getLocation();
        if (holder instanceof Entity e) return e.getLocation();
        return null;
    }

    /** 盔甲架直接取放装备（PlayerInteractAtEntityEvent 不一定覆盖） */
    @EventHandler(ignoreCancelled = true)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        Player p = event.getPlayer();
        Territory t = tAt(event.getRightClicked().getLocation());
        if (t == null || trusted(p, t)) return;
        event.setCancelled(true);
        deny(p, t);
    }

    /** 冰霜行者等玩家实体在领地内生成方块（雪傀儡造雪等无主行为不拦） */
    @EventHandler(ignoreCancelled = true)
    public void onEntityBlockForm(EntityBlockFormEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        Territory t = tAt(event.getBlock().getLocation());
        if (t == null || trusted(p, t)) return;
        event.setCancelled(true);
        deny(p, t);
    }

    /** 敌对生物触发压力板/绊线/踩农田，防生物破坏模块开启时拦截 */
    @EventHandler(ignoreCancelled = true)
    public void onEntityInteract(EntityInteractEvent event) {
        if (!(event.getEntity() instanceof Monster)) return;
        Territory t = tAt(event.getBlock().getLocation());
        if (t != null && t.moduleEnabled(Module.MOB_GRIEF)) {
            event.setCancelled(true);
        }
    }

    /** 钓竿钓走领地内的动物/盔甲架 */
    @EventHandler(ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_ENTITY) return;
        Entity caught = event.getCaught();
        if (caught == null) return;
        Player p = event.getPlayer();
        Territory t = tAt(caught.getLocation());
        if (t == null || trusted(p, t)) return;
        event.setCancelled(true);
        deny(p, t);
    }

    /** 床/门等多格放置：任一副格落在非信任领地内则取消 */
    @EventHandler(ignoreCancelled = true)
    public void onMultiPlace(BlockMultiPlaceEvent event) {
        Player p = event.getPlayer();
        for (org.bukkit.block.BlockState state : event.getReplacedBlockStates()) {
            Territory t = tAt(state.getLocation());
            if (t != null && !trusted(p, t)) {
                event.setCancelled(true);
                deny(p, t);
                return;
            }
        }
    }

    /** 载具伤害（打船/矿车掉血，打坏由 VehicleDestroyEvent 拦截） */
    @EventHandler(ignoreCancelled = true)
    public void onVehicleDamage(VehicleDamageEvent event) {
        Player p = resolveAttacker(event.getAttacker());
        if (p == null) return;
        Territory t = tAt(event.getVehicle().getLocation());
        if (t == null || trusted(p, t)) return;
        event.setCancelled(true);
        deny(p, t);
    }
}
