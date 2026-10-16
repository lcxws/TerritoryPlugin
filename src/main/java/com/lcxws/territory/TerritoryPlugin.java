package com.lcxws.territory;

import com.lcxws.territory.command.ResCommand;
import com.lcxws.territory.listener.GuiListener;
import com.lcxws.territory.listener.MovementListener;
import com.lcxws.territory.listener.ProtectionListener;
import com.lcxws.territory.manager.EconomyHook;
import com.lcxws.territory.manager.Messages;
import com.lcxws.territory.manager.TerritoryManager;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TerritoryPlugin extends JavaPlugin {

    private TerritoryManager territoryManager;
    private EconomyHook economy;
    private Messages messages;
    private ResCommand resCommand;
    private GuiListener guiListener;

    /** /res view 边界粒子任务：玩家 -> 任务 */
    private final Map<UUID, BukkitTask> viewTasks = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        messages = new Messages(this);
        territoryManager = new TerritoryManager(this);
        territoryManager.load();
        economy = new EconomyHook(this);

        Bukkit.getPluginManager().registerEvents(new ProtectionListener(this), this);
        Bukkit.getPluginManager().registerEvents(new MovementListener(this), this);
        guiListener = new GuiListener(this);
        Bukkit.getPluginManager().registerEvents(guiListener, this);
        Bukkit.getPluginManager().registerEvents(new SessionListener(), this);

        // EconomyPlugin / Vault 晚于本插件启动时自动接入
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onPluginEnable(PluginEnableEvent event) {
                String name = event.getPlugin().getName();
                if (name.equals(EconomyHook.PLUGIN_NAME) || name.equals(EconomyHook.VAULT_NAME)) {
                    economy.hook();
                }
            }
        }, this);

        resCommand = new ResCommand(this);
        getCommand("res").setExecutor(resCommand);
        getCommand("res").setTabCompleter(resCommand);

        long interval = Math.max(1, getConfig().getLong("autosave-seconds", 5)) * 20L;
        Bukkit.getScheduler().runTaskTimer(this, this::autosaveTick, interval, interval);

        getLogger().info("领地插件已启用。已加载 " + territoryManager.territoryCount()
                + " 个领地，启用世界: " + territoryManager.enabledWorlds());
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll((org.bukkit.plugin.Plugin) this);
        Bukkit.getScheduler().cancelTasks(this);
        if (territoryManager != null && economy != null) {
            territoryManager.saveSync();
        }
    }

    private void autosaveTick() {
        if (territoryManager.isDirty()) {
            territoryManager.saveAsync();
        }
    }

    public Map<UUID, BukkitTask> getViewTasks() {
        return viewTasks;
    }

    public GuiListener getGui() {
        return guiListener;
    }

    public TerritoryManager getTerritories() {
        return territoryManager;
    }

    public EconomyHook getEconomy() {
        return economy;
    }

    public Messages getMessages() {
        return messages;
    }

    /** 玩家进退服：维护名字缓存、清理粒子任务 */
    private class SessionListener implements Listener {
        @EventHandler
        public void onJoin(PlayerJoinEvent event) {
            Player p = event.getPlayer();
            territoryManager.cacheName(p.getUniqueId(), p.getName());
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
            BukkitTask task = viewTasks.remove(event.getPlayer().getUniqueId());
            if (task != null) task.cancel();
        }
    }
}
