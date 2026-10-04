package com.lcxws.territory.manager;

import com.lcxws.territory.TerritoryPlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * EconomyPlugin 接入：通过 ServicesManager 取得 EconomyAPI 实例后以反射调用。
 * 反射接入使本仓库不依赖 EconomyPlugin 的 jar 即可编译（运行时仍由服务器提供实现）。
 */
public class EconomyHook {

    public static final String PLUGIN_NAME = "EconomyPlugin";

    private final TerritoryPlugin plugin;
    private Object api;
    private Method mGetVersion, mGetBalance, mHasBalance, mWithdraw, mDeposit, mFormat;

    public EconomyHook(TerritoryPlugin plugin) {
        this.plugin = plugin;
        hook();
    }

    /** 尝试接入（可在插件启用事件中重复调用） */
    public void hook() {
        if (api != null) return;
        Plugin eco = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        if (eco == null || !eco.isEnabled()) return;
        try {
            Class<?> apiClass = Class.forName("com.mohistmc.economy.api.EconomyAPI",
                    true, eco.getClass().getClassLoader());
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(apiClass);
            if (rsp == null) return;
            api = rsp.getProvider();
            mGetVersion = apiClass.getMethod("getVersion");
            mGetBalance = apiClass.getMethod("getBalance", OfflinePlayer.class);
            mHasBalance = apiClass.getMethod("hasBalance", OfflinePlayer.class, double.class);
            mWithdraw = apiClass.getMethod("withdraw", OfflinePlayer.class, double.class);
            mDeposit = apiClass.getMethod("deposit", OfflinePlayer.class, double.class);
            mFormat = apiClass.getMethod("format", double.class);
            plugin.getLogger().info("已接入 EconomyPlugin v" + version() + "，领地经济功能可用。");
        } catch (ClassNotFoundException | NoSuchMethodException | LinkageError ex) {
            plugin.getLogger().warning("EconomyPlugin 已安装但 API 不可用或不兼容: " + ex);
            api = null;
        }
    }

    public void unhook() {
        api = null;
    }

    public boolean available() {
        return api != null;
    }

    private Object call(Method m, Object... args) {
        try {
            return m.invoke(api, args);
        } catch (IllegalAccessException | InvocationTargetException ex) {
            plugin.getLogger().warning("EconomyAPI 调用失败: " + ex);
            return null;
        }
    }

    public String version() {
        Object r = call(mGetVersion);
        return r != null ? r.toString() : "?";
    }

    /** 扣款（余额不足返回 false） */
    public boolean take(OfflinePlayer player, double amount) {
        if (api == null || amount <= 0) return false;
        Object r = call(mWithdraw, player, amount);
        return r instanceof Boolean b && b;
    }

    public void give(OfflinePlayer player, double amount) {
        if (api != null && amount > 0) {
            call(mDeposit, player, amount);
        }
    }

    public boolean has(OfflinePlayer player, double amount) {
        if (api == null) return false;
        Object r = call(mHasBalance, player, amount);
        return r instanceof Boolean b && b;
    }

    public double balance(OfflinePlayer player) {
        Object r = call(mGetBalance, player);
        return r instanceof Number n ? n.doubleValue() : 0;
    }

    public String format(double amount) {
        if (api == null) return String.valueOf(amount);
        Object r = call(mFormat, amount);
        return r != null ? r.toString() : String.valueOf(amount);
    }
}
