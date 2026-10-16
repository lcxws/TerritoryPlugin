package com.lcxws.territory.api;

import com.lcxws.territory.TerritoryPlugin;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;

/**
 * API 注册器：
 * - 插件启用时由 TerritoryPlugin 调用 {@link #register} 注册到 Bukkit ServicesManager；
 * - 其他插件用 {@code Bukkit.getServicesManager().load(TerritoryAPI.class)}
 *   或 {@link #get()} 获取实例。
 */
public final class TerritoryAPIProvider {

    private static volatile TerritoryAPI instance;

    private TerritoryAPIProvider() {
    }

    /**
     * @internal 由插件在启用时调用；外部插件请勿调用。
     */
    public static void register(TerritoryPlugin plugin, TerritoryAPI api) {
        instance = api;
        Bukkit.getServicesManager().register(TerritoryAPI.class, api, plugin, ServicePriority.Normal);
    }

    /**
     * @internal 由插件在停用时调用；外部插件请勿调用。
     */
    public static void unregister(TerritoryPlugin plugin) {
        TerritoryAPI api = instance;
        if (api != null) {
            Bukkit.getServicesManager().unregister(TerritoryAPI.class, api);
            instance = null;
        }
    }

    /** 获取 API 实例；插件未启用时返回 null */
    public static TerritoryAPI get() {
        return instance;
    }
}
