package com.lcxws.territory.manager;

import com.lcxws.territory.TerritoryPlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * 经济接入（双后端，均为运行时反射，无需任何编译依赖）：
 *   1. EconomyPlugin（com.mohistmc.economy.api.EconomyAPI 服务，优选）；
 *   2. Vault（net.milkbowl.vault.economy.Economy 服务）—— 兼容 EssentialsX / CMI 等一切
 *      实现了 Vault 的经济插件。
 * 两者都没有时经济功能不可用（购买/退款会给出提示），保护功能不受影响。
 */
public class EconomyHook {

    public static final String PLUGIN_NAME = "EconomyPlugin";
    public static final String VAULT_NAME = "Vault";

    private final TerritoryPlugin plugin;

    /** EconomyPlugin 后端 */
    private Object api;
    private Method mGetVersion, mGetBalance, mHasBalance, mWithdraw, mDeposit, mFormat;

    /** Vault 后端 */
    private Object vault;
    private Method vGetBalance, vHas, vWithdraw, vDeposit, vFormat, vGetName;
    private Field vSuccess;

    public EconomyHook(TerritoryPlugin plugin) {
        this.plugin = plugin;
        hook();
    }

    /** 尝试接入（可在插件启用事件中重复调用，后启用的经济插件会被自动接入） */
    public void hook() {
        if (api == null) hookEconomyPlugin();
        if (api == null && vault == null) hookVault();
    }

    public void unhook() {
        api = null;
        vault = null;
    }

    public boolean available() {
        return api != null || vault != null;
    }

    /* ========================= EconomyPlugin 后端（优选） ========================= */

    private void hookEconomyPlugin() {
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
            plugin.getLogger().info("已接入 EconomyPlugin v" + String.valueOf(call(mGetVersion))
                    + "，领地经济功能可用。");
        } catch (ClassNotFoundException | NoSuchMethodException | LinkageError ex) {
            plugin.getLogger().warning("EconomyPlugin 已安装但 API 不可用或不兼容: " + ex);
            api = null;
        }
    }

    /* ========================= Vault 后端（兜底） ========================= */

    private void hookVault() {
        Plugin vaultPlugin = Bukkit.getPluginManager().getPlugin(VAULT_NAME);
        if (vaultPlugin == null || !vaultPlugin.isEnabled()) return;
        try {
            Class<?> ecoClass = Class.forName("net.milkbowl.vault.economy.Economy",
                    true, vaultPlugin.getClass().getClassLoader());
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(ecoClass);
            if (rsp == null) return;
            vault = rsp.getProvider();
            vGetBalance = ecoClass.getMethod("getBalance", OfflinePlayer.class);
            vHas = ecoClass.getMethod("has", OfflinePlayer.class, double.class);
            vWithdraw = ecoClass.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            vDeposit = ecoClass.getMethod("depositPlayer", OfflinePlayer.class, double.class);
            vFormat = ecoClass.getMethod("format", double.class);
            vGetName = ecoClass.getMethod("getName");
            Class<?> respClass = Class.forName("net.milkbowl.vault.economy.EconomyResponse",
                    true, vaultPlugin.getClass().getClassLoader());
            vSuccess = respClass.getField("transactionSuccess");
            plugin.getLogger().info("已接入 Vault 经济（" + vaultName() + "），领地经济功能可用。");
        } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException | LinkageError ex) {
            plugin.getLogger().warning("Vault 已安装但经济服务不可用: " + ex);
            vault = null;
        }
    }

    private String vaultName() {
        Object n = callVault(vGetName);
        return n != null ? n.toString() : "未知";
    }

    /* ========================= 反射调用工具 ========================= */

    private Object call(Method m, Object... args) {
        if (api == null || m == null) return null;
        try {
            return m.invoke(api, args);
        } catch (IllegalAccessException | InvocationTargetException ex) {
            plugin.getLogger().warning("EconomyAPI 调用失败: " + ex);
            return null;
        }
    }

    private Object callVault(Method m, Object... args) {
        if (vault == null || m == null) return null;
        try {
            return m.invoke(vault, args);
        } catch (IllegalAccessException | InvocationTargetException ex) {
            plugin.getLogger().warning("Vault 调用失败: " + ex);
            return null;
        }
    }

    /** EconomyResponse.transactionSuccess（公开字段） */
    private boolean vaultSuccess(Object response) {
        if (response == null || vSuccess == null) return false;
        try {
            return vSuccess.getBoolean(response);
        } catch (IllegalAccessException ex) {
            return false;
        }
    }

    /* ========================= 对外 API ========================= */

    /** 扣款（余额不足返回 false） */
    public boolean take(OfflinePlayer player, double amount) {
        if (amount <= 0) return false;
        if (api != null) {
            Object r = call(mWithdraw, player, amount);
            return r instanceof Boolean b && b;
        }
        if (vault != null) {
            return vaultSuccess(callVault(vWithdraw, player, amount));
        }
        return false;
    }

    public void give(OfflinePlayer player, double amount) {
        if (amount <= 0) return;
        if (api != null) {
            call(mDeposit, player, amount);
            return;
        }
        if (vault != null) {
            callVault(vDeposit, player, amount);
        }
    }

    public boolean has(OfflinePlayer player, double amount) {
        if (api != null) {
            Object r = call(mHasBalance, player, amount);
            return r instanceof Boolean b && b;
        }
        if (vault != null) {
            Object r = callVault(vHas, player, amount);
            return r instanceof Boolean b && b;
        }
        return false;
    }

    public double balance(OfflinePlayer player) {
        if (api != null) {
            Object r = call(mGetBalance, player);
            return r instanceof Number n ? n.doubleValue() : 0;
        }
        if (vault != null) {
            Object r = callVault(vGetBalance, player);
            return r instanceof Number n ? n.doubleValue() : 0;
        }
        return 0;
    }

    public String format(double amount) {
        if (api != null) {
            Object r = call(mFormat, amount);
            return r != null ? r.toString() : String.valueOf(amount);
        }
        if (vault != null) {
            Object r = callVault(vFormat, amount);
            return r != null ? r.toString() : String.valueOf(amount);
        }
        return String.valueOf(amount);
    }
}
