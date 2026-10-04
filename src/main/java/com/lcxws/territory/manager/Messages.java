package com.lcxws.territory.manager;

import com.lcxws.territory.TerritoryPlugin;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

/**
 * 消息工具：从 config.yml 的 messages 节读取，支持 & 颜色与 %key% 占位符。
 */
public class Messages {

    private final TerritoryPlugin plugin;

    public Messages(TerritoryPlugin plugin) {
        this.plugin = plugin;
    }

    public String raw(String key) {
        String msg = plugin.getConfig().getString("messages." + key);
        return msg != null ? msg : key;
    }

    public String format(String key, String... placeholders) {
        String msg = raw(key);
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            msg = msg.replace(placeholders[i], placeholders[i + 1]);
        }
        return ChatColor.translateAlternateColorCodes('&', msg);
    }

    public String prefixed(String key, String... placeholders) {
        String prefix = plugin.getConfig().getString("messages.prefix", "");
        return ChatColor.translateAlternateColorCodes('&', prefix) + format(key, placeholders);
    }

    public void send(CommandSender to, String key, String... placeholders) {
        to.sendMessage(prefixed(key, placeholders));
    }

    /** 不带前缀的原始行（用于 help 列表等） */
    public void sendPlain(CommandSender to, String key) {
        to.sendMessage(format(key));
    }
}
