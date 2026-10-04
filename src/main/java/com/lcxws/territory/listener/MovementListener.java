package com.lcxws.territory.listener;

import com.lcxws.territory.Territory;
import com.lcxws.territory.TerritoryPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.spigotmc.event.player.PlayerSpawnLocationEvent;

/**
 * 移动监听：仅在玩家跨越区块边界时做一次索引查找（快速路径）。
 * 1. 黑名单禁入（移动/传送/载具/重生/登入全部拦截——骑船骑马绕不过去）
 * 2. 进出领地聊天提示
 *
 * 注：PlayerMoveEvent 在骑乘载具时不触发（载具移动的是实体本身），
 * 因此载具进入必须监听 VehicleMoveEvent，这是所有对照的开源领地插件都没处理的绕过口。
 */
public class MovementListener implements Listener {

    private final TerritoryPlugin plugin;

    public MovementListener(TerritoryPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;
        // 快速路径：同一区块内移动（含转头）直接返回
        if (sameChunk(from, to)) {
            return;
        }
        handleCross(event.getPlayer(), from, to, event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;
        // 禁访客传送模块：非信任成员无法传送进入领地（步行不受影响）
        Territory toT = plugin.getTerritories().getAt(to);
        if (toT != null && toT.moduleEnabled(com.lcxws.territory.Module.NO_TELEPORT)
                && !toT.isTrusted(event.getPlayer().getUniqueId())
                && !plugin.getTerritories().bypassProtection(event.getPlayer())) {
            event.setCancelled(true);
            plugin.getMessages().send(event.getPlayer(), "tp-blocked",
                    "%owner%", plugin.getTerritories().nameFor(toT.getOwner()));
            return;
        }
        handleCross(event.getPlayer(), from, to, event);
    }

    /** 骑乘载具（船/矿车/马等）跨区块：对玩家乘客做禁入与进出提示 */
    @EventHandler(ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || !chunkCrossed(from, to)) return;
        for (Entity passenger : event.getVehicle().getPassengers()) {
            if (!(passenger instanceof Player p)) continue;
            Territory fromT = plugin.getTerritories().getAt(from);
            Territory toT = plugin.getTerritories().getAt(to);
            if (fromT == toT) continue;

            if (toT != null && toT.isBlacklisted(p.getUniqueId())
                    && !plugin.getTerritories().bypassProtection(p)) {
                // 无法取消载具移动：把乘客弹出并送回跨入前位置
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (p.isOnline() && p.getVehicle() == event.getVehicle()) {
                        event.getVehicle().removePassenger(p);
                        p.teleport(from);
                    }
                });
                plugin.getMessages().send(p, "banned-entry");
                continue;
            }
            sendEnterExit(p, fromT, toT);
        }
    }

    /** 重生点在黑名单领地内（例如床在仇家领地旁）：改到世界出生点 */
    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Location loc = event.getRespawnLocation();
        Territory t = plugin.getTerritories().getAt(loc);
        if (t == null || !t.isBlacklisted(event.getPlayer().getUniqueId())
                || plugin.getTerritories().bypassProtection(event.getPlayer())) {
            return;
        }
        World world = loc.getWorld() != null ? loc.getWorld() : Bukkit.getWorlds().get(0);
        event.setRespawnLocation(world.getSpawnLocation());
        plugin.getMessages().send(event.getPlayer(), "banned-entry");
    }

    /** 登入位置在黑名单领地内（离线期间被拉黑）：改到世界出生点 */
    @EventHandler
    public void onSpawnLocation(PlayerSpawnLocationEvent event) {
        Location loc = event.getSpawnLocation();
        Territory t = plugin.getTerritories().getAt(loc);
        if (t == null || !t.isBlacklisted(event.getPlayer().getUniqueId())
                || plugin.getTerritories().bypassProtection(event.getPlayer())) {
            return;
        }
        World world = loc.getWorld() != null ? loc.getWorld() : Bukkit.getWorlds().get(0);
        event.setSpawnLocation(world.getSpawnLocation());
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> plugin.getMessages().send(p, "banned-entry"));
    }

    /* ========================= 公共 ========================= */

    private void handleCross(Player p, Location from, Location to, Cancellable event) {
        Territory fromT = plugin.getTerritories().getAt(from);
        Territory toT = plugin.getTerritories().getAt(to);
        if (fromT == toT) return;

        // 黑名单禁入（黑名单优先，即使被 trust 也无效）
        if (toT != null && toT.isBlacklisted(p.getUniqueId()) && !plugin.getTerritories().bypassProtection(p)) {
            event.setCancelled(true);
            plugin.getMessages().send(p, "banned-entry");
            return;
        }

        sendEnterExit(p, fromT, toT);
    }

    private void sendEnterExit(Player p, Territory fromT, Territory toT) {
        if (!plugin.getConfig().getBoolean("entry-exit-messages", true)) return;

        if (fromT != null) {
            plugin.getMessages().send(p, "left",
                    "%owner%", plugin.getTerritories().nameFor(fromT.getOwner()));
        }
        if (toT != null) {
            plugin.getMessages().send(p, "entered",
                    "%owner%", plugin.getTerritories().nameFor(toT.getOwner()));
        }
    }

    private static boolean sameChunk(Location a, Location b) {
        return a.getWorld() == b.getWorld()
                && (a.getBlockX() >> 4) == (b.getBlockX() >> 4)
                && (a.getBlockZ() >> 4) == (b.getBlockZ() >> 4);
    }

    private static boolean chunkCrossed(Location a, Location b) {
        return !sameChunk(a, b);
    }
}
