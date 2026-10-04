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
 */
public class BorderParticleTask extends BukkitRunnable {

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

    @Override
    public void run() {
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
                player.spawnParticle(Particle.VILLAGER_HAPPY, minX + i, y, minZ, 1, 0, 0, 0, 0);
                player.spawnParticle(Particle.VILLAGER_HAPPY, minX + i, y, minZ + 16, 1, 0, 0, 0, 0);
                player.spawnParticle(Particle.VILLAGER_HAPPY, minX, y, minZ + i, 1, 0, 0, 0, 0);
                player.spawnParticle(Particle.VILLAGER_HAPPY, minX + 16, y, minZ + i, 1, 0, 0, 0, 0);
            }
        }
    }

    private void cancelAndCleanup() {
        cancel();
        plugin.getViewTasks().remove(player.getUniqueId());
    }
}
