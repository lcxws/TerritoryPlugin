package com.lcxws.territory.storage;

import com.lcxws.territory.Module;
import com.lcxws.territory.Territory;
import com.lcxws.territory.TerritoryPlugin;
import com.lcxws.territory.manager.TerritoryManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 旧版（1.x YAML 分支）数据导入：仅当数据库为空且检测到 territories.yml 时使用一次。
 * 原文件不会被删除或改名，确认导入无误后可手动清理。
 */
public final class YamlImporter {

    private YamlImporter() {
    }

    /** 从 territories.yml 读取领地与名字缓存，返回成功导入的领地数量 */
    public static int importFrom(TerritoryPlugin plugin, File file,
                                 List<Territory> out, Map<UUID, String> names) {
        if (!file.exists()) return 0;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection namesSec = yaml.getConfigurationSection("names");
        if (namesSec != null) {
            for (String key : namesSec.getKeys(false)) {
                try {
                    names.putIfAbsent(UUID.fromString(key), namesSec.getString(key));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        ConfigurationSection secs = yaml.getConfigurationSection("territories");
        if (secs == null) return 0;
        int count = 0;
        for (String key : secs.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("跳过非法领地ID: " + key);
                continue;
            }
            String ownerStr = secs.getString(key + ".owner");
            String world = secs.getString(key + ".world");
            if (ownerStr == null || world == null) continue;
            Territory t;
            try {
                t = new Territory(id, UUID.fromString(ownerStr), world);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("跳过非法领地数据: " + key);
                continue;
            }
            t.setCreated(secs.getLong(key + ".created", System.currentTimeMillis()));
            for (String c : secs.getStringList(key + ".chunks")) {
                String[] parts = c.split(":");
                if (parts.length == 2) {
                    try {
                        t.getChunks().add(TerritoryManager.pack(Integer.parseInt(parts[0]),
                                Integer.parseInt(parts[1])));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (t.getChunks().isEmpty()) {
                plugin.getLogger().warning("领地 " + key + " 没有区块，跳过。");
                continue;
            }
            for (String u : secs.getStringList(key + ".whitelist")) {
                try {
                    t.getWhitelist().add(UUID.fromString(u));
                } catch (IllegalArgumentException ignored) {
                }
            }
            for (String u : secs.getStringList(key + ".blacklist")) {
                try {
                    t.getBlacklist().add(UUID.fromString(u));
                } catch (IllegalArgumentException ignored) {
                }
            }
            for (String m : secs.getStringList(key + ".purchased")) {
                try {
                    t.getPurchased().add(Module.valueOf(m));
                } catch (IllegalArgumentException ignored) {
                }
            }
            for (String m : secs.getStringList(key + ".enabled")) {
                try {
                    t.getEnabled().add(Module.valueOf(m));
                } catch (IllegalArgumentException ignored) {
                }
            }
            String tp = secs.getString(key + ".tp");
            if (tp != null) {
                String[] p = tp.split(",");
                if (p.length == 5) {
                    try {
                        World w = Bukkit.getWorld(world);
                        if (w != null) {
                            t.setTpPoint(new Location(w, Double.parseDouble(p[0]), Double.parseDouble(p[1]),
                                    Double.parseDouble(p[2]), Float.parseFloat(p[3]), Float.parseFloat(p[4])));
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            out.add(t);
            count++;
        }
        return count;
    }
}
