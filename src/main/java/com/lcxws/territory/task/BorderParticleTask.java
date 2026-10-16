package com.lcxws.territory.task;

import com.lcxws.territory.Territory;
import com.lcxws.territory.TerritoryPlugin;
import com.lcxws.territory.manager.TerritoryManager;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * /res view 的边界粒子任务：每 4 ticks 渲染一次，只发给执行命令的玩家本人，
 * 只渲染玩家附近若干区块的领地边界（横向一圈），持续 view-particle-seconds 秒。
 *
 * 版本兼容：该粒子在 1.20.5+ 的 Bukkit 枚举中改名（VILLAGER_HAPPY → HAPPY_VILLAGER），
 * 此处按名称反射解析两个名字，同一份 jar 可运行于 1.20 – 1.21.x。
 */
public class BorderParticleTask extends BukkitRunnable {

    /** 边界粒子：优先新名（1.20.5+），回退旧名（≤1.20.4）；都取不到则为 null（不渲染） */
    private static final Particle PARTICLE = resolveParticle();

    private final TerritoryPlugin plugin;
    private final Player player;
    private final Territory territory;
    private final int totalTicks;
    private int ticks;

    public BorderParticleTask(TerritoryPlugin plugin, Player player, Territory territory) {
        this.plugin = plugin;
        this.player = player;
        this.territory = territory;
        this.totalTicks = Math.max(1, plugin.getConfig().getInt("view-particle-seconds", 30)) * 20;
    }

    private static Particle resolveParticle() {
        for (String name : new String[]{"HAPPY_VILLAGER", "VILLAGER_HAPPY"}) {
            try {
                return Particle.valueOf(name);
            } catch (IllegalArgumentException ignored) {
                // 当前服务端没有这个名字，试下一个
            }
        }
        return null;
    }

    @Override
    public void run() {
        if (PARTICLE == null) {
            cancelAndCleanup();
            return;
        }
        if (!player.isOnline() || !player.getWorld().getName().equals(territory.getWorld())) {
            cancelAndCleanup();
            return;
        }
        ticks += 4;
        if (ticks >= totalTicks) {
            cancelAndCleanup();
            plugin.getMessages().send(player, "view-done");
            return;
        }
        render();
    }

    private void render() {
        int radius = Math.max(0, plugin.getConfig().getInt("view-radius-chunks", 2));
        int pcx = player.getLocation().getBlockX() >> 4;
        int pcz = player.getLocation().getBlockZ() >> 4;
        double y = Math.max(player.getLocation().getY() + 1, player.getWorld().getMinHeight() + 1);

        for (Long packed : territory.getChunks()) {
            int cx = TerritoryManager.unpackX(packed);
            int cz = TerritoryManager.unpackZ(packed);
            if (Math.abs(cx - pcx) > radius || Math.abs(cz - pcz) > radius) continue;
            double minX = cx * 16.0;
            double minZ = cz * 16.0;
            for (int i = 0; i <= 16; i++) {
                player.spawnParticle(PARTICLE, minX + i, y, minZ, 1, 0, 0, 0, 0);
                player.spawnParticle(PARTICLE, minX + i, y, minZ + 16, 1, 0, 0, 0, 0);
                player.spawnParticle(PARTICLE, minX, y, minZ + i, 1, 0, 0, 0, 0);
                player.spawnParticle(PARTICLE, minX + 16, y, minZ + i, 1, 0, 0, 0, 0);
            }
        }
    }

    private void cancelAndCleanup() {
        cancel();
        plugin.getViewTasks().remove(player.getUniqueId());
    }
}
