package com.lcxws.territory.gui;

import com.lcxws.territory.Territory;
import com.lcxws.territory.TerritoryPlugin;
import com.lcxws.territory.manager.TerritoryManager;
import com.lcxws.territory.task.BorderParticleTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * 领地动作层：命令 /res 与 GUI 共用的业务逻辑，保证两条入口行为一致。
 */
public final class Actions {

    private Actions() {
    }

    /* ========================= 圈地 / 放弃 ========================= */

    public static void claim(TerritoryPlugin plugin, Player p) {
        if (!p.hasPermission("territory.command.claim")) {
            plugin.getMessages().send(p, "no-permission");
            return;
        }
        String err = plugin.getTerritories().claim(p);
        if (err == null) {
            double price = plugin.getConfig().getDouble("chunk-price", 2500);
            plugin.getMessages().send(p, "claim-success",
                    "%cost%", plugin.getEconomy().format(price),
                    "%balance%", plugin.getEconomy().format(plugin.getEconomy().balance(p)));
            return;
        }
        sendResult(plugin, p, err);
    }

    public static void unclaim(TerritoryPlugin plugin, Player p) {
        if (!p.hasPermission("territory.command.unclaim")) {
            plugin.getMessages().send(p, "no-permission");
            return;
        }
        String err = plugin.getTerritories().unclaim(p);
        if (err != null && err.startsWith("unclaim-success:")) {
            String refund = err.substring(err.indexOf(':') + 1);
            plugin.getMessages().send(p, "unclaim-success",
                    "%refund%", refund,
                    "%balance%", plugin.getEconomy().format(plugin.getEconomy().balance(p)));
            return;
        }
        sendResult(plugin, p, err);
    }

    public static void sendResult(TerritoryPlugin plugin, Player p, String err) {
        if (err == null) return;
        int idx = err.indexOf(':');
        String key = idx >= 0 ? err.substring(0, idx) : err;
        String param = idx >= 0 ? err.substring(idx + 1) : null;
        switch (key) {
            case "owned-by-other" -> plugin.getMessages().send(p, key, "%owner%", param);
            case "not-enough-money" -> plugin.getMessages().send(p, key, "%need%", param);
            case "max-chunks" -> plugin.getMessages().send(p, key, "%max%", param);
            default -> plugin.getMessages().send(p, key);
        }
    }

    /* ========================= 名单管理 ========================= */

    /** 命令与 GUI 共用的黑白名单增删（含解析玩家名、自目标检查、拉黑弹出）；
     *  数据变更与事件统一走 TerritoryManager.changeMember（与对外 API 同一套规则）。 */
    public static void modifyList(TerritoryPlugin plugin, Player actor, Territory t,
                                  String targetName, boolean add, boolean blacklist) {
        if (!actor.hasPermission(blacklist ? "territory.command.ban" : "territory.command.trust")) {
            plugin.getMessages().send(actor, "no-permission");
            return;
        }
        TerritoryManager tm = plugin.getTerritories();
        UUID target = tm.resolveUUID(targetName);
        if (target == null) {
            plugin.getMessages().send(actor, "player-not-found", "%name%", targetName);
            return;
        }
        if (target.equals(actor.getUniqueId()) && !tm.isAdmin(actor)) {
            plugin.getMessages().send(actor, "cannot-target-self");
            return;
        }
        String resolvedName = tm.nameFor(target);
        boolean wasTrusted = blacklist && add && t.getWhitelist().contains(target);

        if (!tm.changeMember(t, target, add, blacklist)) {
            // 提示与旧行为逐字一致
            if (blacklist) {
                plugin.getMessages().send(actor, add ? "trust-already" : "ban-not-found");
            } else {
                plugin.getMessages().send(actor, add
                        ? (t.isBlacklisted(target) ? "trust-is-banned" : "trust-already")
                        : "trust-not-found");
            }
            return;
        }

        if (blacklist) {
            if (add) {
                plugin.getMessages().send(actor, "ban-add", "%player%", resolvedName);
                if (wasTrusted) {
                    plugin.getMessages().send(actor, "ban-was-trusted");
                }
                ejectIfInside(plugin, t, target);
            } else {
                plugin.getMessages().send(actor, "ban-remove", "%player%", resolvedName);
            }
        } else {
            plugin.getMessages().send(actor, add ? "trust-add" : "trust-remove",
                    "%player%", resolvedName);
        }
    }

    /** 拉黑时若目标正站在领地内，下一 tick 移出到世界出生点 */
    private static void ejectIfInside(TerritoryPlugin plugin, Territory t, UUID target) {
        Player online = Bukkit.getPlayer(target);
        if (online == null) return;
        Territory at = plugin.getTerritories().getAt(online.getLocation());
        if (at == null || !at.getId().equals(t.getId())) return;
        World world = Bukkit.getWorld(t.getWorld());
        Location spawn = world != null ? world.getSpawnLocation() : online.getLocation();
        Bukkit.getScheduler().runTask(plugin, () -> {
            online.teleport(spawn);
            plugin.getMessages().send(online, "ban-ejected");
        });
    }

    /* ========================= 传送 ========================= */

    public static void setTp(TerritoryPlugin plugin, Player p) {
        if (!p.hasPermission("territory.command.settp")) {
            plugin.getMessages().send(p, "no-permission");
            return;
        }
        Territory t = plugin.getTerritories().getAt(p.getLocation());
        if (t == null) {
            plugin.getMessages().send(p, "not-in-territory");
            return;
        }
        if (!t.isOwner(p.getUniqueId()) && !plugin.getTerritories().isAdmin(p)) {
            plugin.getMessages().send(p, "not-owner");
            return;
        }
        t.setTpPoint(p.getLocation());
        plugin.getTerritories().markDirty(t);
        plugin.getMessages().send(p, "settp-success");
    }

    public static void teleport(TerritoryPlugin plugin, Player p, Territory t) {
        if (!p.hasPermission("territory.command.tp")) {
            plugin.getMessages().send(p, "no-permission");
            return;
        }
        Location target = t.getTpPoint();
        if (target == null) {
            long[] c = centerChunk(t);
            World world = Bukkit.getWorld(t.getWorld());
            if (world == null) {
                plugin.getMessages().send(p, "tp-none");
                return;
            }
            long packed = TerritoryManager.pack((int) c[0], (int) c[1]);
            int cx, cz;
            if (t.getChunks().contains(packed)) {
                cx = (int) c[0];
                cz = (int) c[1];
            } else {
                long best = -1;
                double bestDist = Double.MAX_VALUE;
                for (Long ch : t.getChunks()) {
                    double d = Math.pow(TerritoryManager.unpackX(ch) - c[0], 2)
                            + Math.pow(TerritoryManager.unpackZ(ch) - c[1], 2);
                    if (d < bestDist) {
                        bestDist = d;
                        best = ch;
                    }
                }
                cx = TerritoryManager.unpackX(best);
                cz = TerritoryManager.unpackZ(best);
            }
            Block highest = world.getHighestBlockAt(cx * 16 + 8, cz * 16 + 8);
            target = highest.getLocation().add(0.5, 1.0, 0.5);
        }
        p.teleport(target);
        plugin.getMessages().send(p, "tp-success",
                "%owner%", plugin.getTerritories().nameFor(t.getOwner()));
    }

    /** 领地的"中心区块"（所有区块坐标平均，四舍五入） */
    public static long[] centerChunk(Territory t) {
        long sx = 0, sz = 0;
        for (Long packed : t.getChunks()) {
            sx += TerritoryManager.unpackX(packed);
            sz += TerritoryManager.unpackZ(packed);
        }
        int n = t.getChunks().size();
        return new long[]{Math.round((double) sx / n), Math.round((double) sz / n)};
    }

    /* ========================= 边界粒子 ========================= */

    public static void view(TerritoryPlugin plugin, Player p) {
        if (!p.hasPermission("territory.command.view")) {
            plugin.getMessages().send(p, "no-permission");
            return;
        }
        Territory t = plugin.getTerritories().getAt(p.getLocation());
        if (t == null) {
            plugin.getMessages().send(p, "view-none");
            return;
        }
        var existing = plugin.getViewTasks().remove(p.getUniqueId());
        if (existing != null) existing.cancel();
        int seconds = Math.max(1, plugin.getConfig().getInt("view-particle-seconds", 30));
        BorderParticleTask task = new BorderParticleTask(plugin, p, t);
        org.bukkit.scheduler.BukkitTask handle = task.runTaskTimer(plugin, 0L, 4L);
        plugin.getViewTasks().put(p.getUniqueId(), handle);
        plugin.getMessages().send(p, "view-start", "%seconds%", String.valueOf(seconds));
    }
}
