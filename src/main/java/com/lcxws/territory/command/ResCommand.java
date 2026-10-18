package com.lcxws.territory.command;

import com.lcxws.territory.BlockKey;
import com.lcxws.territory.InteractionLink;
import com.lcxws.territory.Module;
import com.lcxws.territory.Territory;
import com.lcxws.territory.TerritoryPlugin;
import com.lcxws.territory.gui.Actions;
import com.lcxws.territory.manager.TerritoryManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 领地主命令 /res（别名 /land）。
 * /res 或 /res gui：打开 GUI 主菜单。
 * 玩家子命令：claim unclaim info list tp settp trust untrust ban unban help
 * 管理子命令（仅 config 超管名单/控制台）：admin whoami|reload|list|info|delete|deletechunk
 */
public class ResCommand implements CommandExecutor, TabCompleter {

    private static final List<String> PLAYER_SUBS = List.of(
            "claim", "unclaim", "info", "list", "tp", "settp",
            "trust", "untrust", "ban", "unban", "gui", "view", "link", "admin");
    private static final List<String> ADMIN_SUBS = List.of(
            "whoami", "reload", "list", "info", "delete", "deletechunk");
    private static final List<String> LINK_SUBS = List.of(
            "add", "list", "remove", "mode", "enable", "disable", "cancel");

    private final TerritoryPlugin plugin;

    public ResCommand(TerritoryPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        TerritoryManager tm = plugin.getTerritories();
        String sub = args.length == 0 ? "gui" : args[0].toLowerCase();

        // 控制台：只允许 admin 子命令
        if (!(sender instanceof Player p)) {
            if (sub.equals("admin")) {
                return adminCommand(sender, null, args);
            }
            plugin.getMessages().send(sender, "admin-no-permission");
            return true;
        }

        // 权限节点门控（help 与未知子命令不设限；admin 走 isAdmin 单独判定）
        String node = permNode(sub);
        if (node != null && !p.hasPermission(node)) {
            plugin.getMessages().send(p, "no-permission");
            return true;
        }

        switch (sub) {
            case "gui" -> plugin.getGui().openMain(p); // /res 与 /res gui 都打开主菜单
            case "claim" -> Actions.claim(plugin, p);
            case "unclaim" -> Actions.unclaim(plugin, p);
            case "info" -> info(p, false);
            case "list" -> list(p);
            case "tp" -> tp(p, args);
            case "settp" -> Actions.setTp(plugin, p);
            case "trust" -> modifyList(p, args, true, false);
            case "untrust" -> modifyList(p, args, false, false);
            case "ban" -> modifyList(p, args, true, true);
            case "unban" -> modifyList(p, args, false, true);
            case "view" -> Actions.view(plugin, p);
            case "link" -> linkCommand(p, args);
            case "admin" -> adminCommand(sender, p, args);
            default -> help(p);
        }
        return true;
    }

    private void help(Player p) {
        for (String line : plugin.getConfig().getStringList("messages.help")) {
            p.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', line));
        }
    }

    private void modifyList(Player p, String[] args, boolean add, boolean blacklist) {
        if (args.length < 2) {
            p.sendMessage("§c用法: /res " + (blacklist ? (add ? "ban" : "unban") : (add ? "trust" : "untrust")) + " <玩家名>");
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
        Actions.modifyList(plugin, p, t, args[1], add, blacklist);
    }

    /* ========================= 交互链接 ========================= */

    private void linkCommand(Player p, String[] args) {
        if (args.length < 2) {
            linkUsage(p);
            return;
        }
        String action = args[1].toLowerCase();
        if (action.equals("add")) {
            plugin.getLinkListener().beginSelection(p);
            return;
        }
        if (action.equals("cancel")) {
            plugin.getLinkListener().cancelSelection(p, true);
            return;
        }

        Territory t = linkTerritory(p);
        if (t == null) return;
        List<InteractionLink> links = t.getLinks();

        switch (action) {
            case "list" -> listLinks(p, t);
            case "remove" -> {
                int index = parseLinkIndex(p, args, links);
                if (index < 0) return;
                InteractionLink link = links.get(index);
                plugin.getTerritories().removeLink(link);
                plugin.getMessages().send(p, "link-removed", "%link%", formatLink(link));
            }
            case "mode" -> {
                if (args.length < 4) {
                    linkUsage(p);
                    return;
                }
                int index = parseLinkIndex(p, args, links);
                if (index < 0) return;
                String modeName = args[3].toLowerCase();
                InteractionLink.Mode mode = modeName.equals("public") ? InteractionLink.Mode.PUBLIC
                        : modeName.equals("trusted") ? InteractionLink.Mode.TRUSTED : null;
                if (mode == null) {
                    plugin.getMessages().send(p, "link-mode-invalid");
                    return;
                }
                InteractionLink link = links.get(index);
                link.setMode(mode);
                plugin.getTerritories().markDirty();
                plugin.getMessages().send(p, mode == InteractionLink.Mode.PUBLIC
                        ? "link-mode-public" : "link-mode-trusted");
                if (mode == InteractionLink.Mode.PUBLIC) warnPublic(p, link);
            }
            case "enable", "disable" -> {
                int index = parseLinkIndex(p, args, links);
                if (index < 0) return;
                InteractionLink link = links.get(index);
                link.setEnabled(action.equals("enable"));
                plugin.getTerritories().markDirty();
                plugin.getMessages().send(p, link.isEnabled() ? "link-enabled" : "link-disabled-state",
                        "%link%", formatLink(link));
            }
            default -> linkUsage(p);
        }
    }

    private Territory linkTerritory(Player p) {
        Territory t = plugin.getTerritories().getAt(p.getLocation());
        if (t == null) {
            plugin.getMessages().send(p, "not-in-territory");
            return null;
        }
        if (!t.isOwner(p.getUniqueId())) {
            plugin.getMessages().send(p, "not-owner");
            return null;
        }
        return t;
    }

    private int parseLinkIndex(Player p, String[] args, List<InteractionLink> links) {
        if (args.length < 3) {
            linkUsage(p);
            return -1;
        }
        int index;
        try {
            index = Integer.parseInt(args[2]);
        } catch (NumberFormatException ex) {
            plugin.getMessages().send(p, "link-bad-index");
            return -1;
        }
        if (index < 1 || index > links.size()) {
            plugin.getMessages().send(p, "link-bad-index");
            return -1;
        }
        return index - 1;
    }

    private void listLinks(Player p, Territory t) {
        List<InteractionLink> links = t.getLinks();
        if (links.isEmpty()) {
            plugin.getMessages().send(p, "link-none");
            return;
        }
        plugin.getMessages().sendPlain(p, "link-list-header");
        int i = 1;
        for (InteractionLink link : links) {
            plugin.getMessages().send(p, "link-list-item",
                    "%index%", String.valueOf(i++),
                    "%source%", formatKey(link.getSource()),
                    "%target%", formatKey(link.getTarget()),
                    "%mode%", link.getMode().display,
                    "%auto%", link.getAutoCloseSeconds() > 0 ? link.getAutoCloseSeconds() + "秒" : "手动",
                    "%status%", link.isEnabled() ? "启用" : "停用");
        }
    }

    private void warnPublic(Player p, InteractionLink link) {
        plugin.getMessages().send(p, "link-public-warn");
        Location loc = link.getSource().toLocation();
        if (loc != null && plugin.getTerritories().getAt(loc) != null) {
            plugin.getMessages().send(p, "link-public-source-claimed");
        }
    }

    private void linkUsage(Player p) {
        p.sendMessage("§6===== 交互链接 =====");
        p.sendMessage("§e/res link add §7- 右键外部按钮/拉杆，再右键领地内的门");
        p.sendMessage("§e/res link list §7- 查看当前领地的链接");
        p.sendMessage("§e/res link remove <序号> §7- 删除链接");
        p.sendMessage("§e/res link mode <序号> <public|trusted> §7- 切换权限");
        p.sendMessage("§e/res link enable|disable <序号> §7- 启用/停用");
        p.sendMessage("§e/res link cancel §7- 取消当前绑定选择");
    }

    private static String formatKey(BlockKey key) {
        return key.getWorld() + " " + key.getX() + "," + key.getY() + "," + key.getZ();
    }

    private static String formatLink(InteractionLink link) {
        return formatKey(link.getSource()) + " -> " + formatKey(link.getTarget());
    }

    /* ========================= 信息 / 列表 ========================= */

    private void info(Player p, boolean adminView) {
        Territory t = plugin.getTerritories().getAt(p.getLocation());
        if (t == null) {
            plugin.getMessages().send(p, "info-none");
            return;
        }
        TerritoryManager tm = plugin.getTerritories();
        plugin.getMessages().sendPlain(p, "info-header");
        row(p, "领地ID", t.getId().toString());
        row(p, "主人", tm.nameFor(t.getOwner()) + (adminView ? " (" + t.getOwner() + ")" : ""));
        row(p, "区块数", String.valueOf(t.getChunks().size()));
        long[] center = Actions.centerChunk(t);
        row(p, "中心区块", center[0] + ", " + center[1]);
        StringBuilder mods = new StringBuilder();
        for (Module m : Module.values()) {
            String status = t.getPurchased().contains(m)
                    ? (t.getEnabled().contains(m) ? "开启" : "关闭")
                    : "未购买";
            mods.append(m.display).append(":").append(status).append("  ");
        }
        row(p, "保护模块", mods.toString().trim());
        String identity = t.isOwner(p.getUniqueId()) ? "领主"
                : t.isBlacklisted(p.getUniqueId()) ? "黑名单"
                : t.isTrusted(p.getUniqueId()) ? "白名单" : "访客";
        row(p, "你的身份", identity);
        row(p, "传送点", t.hasTp() ? "已设置" : "未设置");
        if (adminView) {
            row(p, "白名单", uuidNames(t.getWhitelist()));
            row(p, "黑名单", uuidNames(t.getBlacklist()));
        }
    }

    private void row(Player p, String key, String value) {
        plugin.getMessages().send(p, "info-row", "%key%", key, "%value%", value);
    }

    private String uuidNames(Iterable<UUID> uuids) {
        if (uuids == null || !uuids.iterator().hasNext()) return "（空）";
        StringBuilder sb = new StringBuilder();
        for (UUID u : uuids) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(plugin.getTerritories().nameFor(u));
        }
        return sb.toString();
    }

    private void list(Player p) {
        List<Territory> list = plugin.getTerritories().territoriesOf(p.getUniqueId());
        if (list.isEmpty()) {
            plugin.getMessages().send(p, "list-none");
            return;
        }
        plugin.getMessages().sendPlain(p, "list-header");
        int i = 1;
        for (Territory t : list) {
            long[] c = Actions.centerChunk(t);
            StringBuilder mods = new StringBuilder();
            for (Module m : t.getPurchased()) {
                mods.append(m.display).append(t.getEnabled().contains(m) ? "✓" : "×").append(" ");
            }
            plugin.getMessages().send(p, "list-item",
                    "%index%", String.valueOf(i++),
                    "%chunks%", String.valueOf(t.getChunks().size()),
                    "%x%", String.valueOf(c[0]),
                    "%z%", String.valueOf(c[1]),
                    "%modules%", mods.length() == 0 ? "无" : mods.toString().trim());
        }
    }

    /* ========================= 传送 ========================= */

    private void tp(Player p, String[] args) {
        List<Territory> accessible = plugin.getTerritories().tpAccessibleOf(p.getUniqueId());
        if (args.length >= 2 && args[1].equalsIgnoreCase("list")) {
            if (accessible.isEmpty()) {
                plugin.getMessages().send(p, "tp-none");
                return;
            }
            plugin.getMessages().sendPlain(p, "tp-list-header");
            int i = 1;
            for (Territory t : accessible) {
                long[] c = Actions.centerChunk(t);
                plugin.getMessages().send(p, "tp-list-item",
                        "%index%", String.valueOf(i++),
                        "%owner%", plugin.getTerritories().nameFor(t.getOwner()),
                        "%chunks%", String.valueOf(t.getChunks().size()),
                        "%x%", String.valueOf(c[0]),
                        "%z%", String.valueOf(c[1]));
            }
            return;
        }
        if (accessible.isEmpty()) {
            plugin.getMessages().send(p, "tp-none");
            return;
        }
        if (args.length < 2) {
            if (accessible.size() == 1) {
                Actions.teleport(plugin, p, accessible.get(0));
            } else {
                plugin.getMessages().send(p, "tp-no-index");
            }
            return;
        }
        int index;
        try {
            index = Integer.parseInt(args[1]);
        } catch (NumberFormatException ex) {
            plugin.getMessages().send(p, "tp-bad-index");
            return;
        }
        if (index < 1 || index > accessible.size()) {
            plugin.getMessages().send(p, "tp-bad-index");
            return;
        }
        Actions.teleport(plugin, p, accessible.get(index - 1));
    }

    /* ========================= 管理命令 ========================= */

    private boolean adminCommand(CommandSender sender, Player p, String[] args) {
        TerritoryManager tm = plugin.getTerritories();
        // 控制台始终允许；游戏内需 config 超管名单或 territory.admin 权限节点
        if (p != null && !tm.isAdmin(p)) {
            plugin.getMessages().send(sender, "admin-no-permission");
            return true;
        }
        String sub = args.length >= 2 ? args[1].toLowerCase() : "help";

        switch (sub) {
            case "whoami" -> {
                if (p != null) {
                    plugin.getMessages().send(sender, "admin-whoami", "%uuid%", p.getUniqueId().toString());
                } else {
                    sender.sendMessage("控制台无需查询 UUID。");
                }
            }
            case "reload" -> {
                plugin.reloadConfig();
                tm.reloadWorlds();
                plugin.getMessages().send(sender, "admin-reloaded");
            }
            case "list" -> {
                if (args.length >= 3) {
                    UUID target = tm.resolveUUID(args[2]);
                    if (target == null) {
                        plugin.getMessages().send(sender, "player-not-found", "%name%", args[2]);
                        return true;
                    }
                    List<Territory> list = tm.territoriesOf(target);
                    sender.sendMessage("玩家 " + args[2] + " 共 " + list.size() + " 个领地 / "
                            + tm.countChunksOf(target) + " 个区块。");
                    for (Territory t : list) {
                        sender.sendMessage("  - " + t.getId() + " 区块数=" + t.getChunks().size()
                                + " 模块=" + t.getPurchased().size());
                    }
                } else {
                    sender.sendMessage("当前共有 " + tm.territoryCount() + " 个领地。");
                }
            }
            case "info" -> {
                if (p != null) info(p, true);
                else sender.sendMessage("请在游戏内使用（需要站在目标领地内）。");
            }
            case "delete" -> {
                if (args.length >= 3) {
                    UUID target = tm.resolveUUID(args[2]);
                    if (target == null) {
                        plugin.getMessages().send(sender, "player-not-found", "%name%", args[2]);
                        return true;
                    }
                    List<Territory> list = tm.territoriesOf(target);
                    int n = list.size();
                    for (Territory t : list) tm.deleteTerritory(t);
                    plugin.getMessages().send(sender, "admin-deleted-all",
                            "%player%", args[2], "%count%", String.valueOf(n));
                } else if (p != null) {
                    Territory t = tm.getAt(p.getLocation());
                    if (t == null) {
                        plugin.getMessages().send(p, "not-in-territory");
                        return true;
                    }
                    int chunks = t.getChunks().size();
                    tm.deleteTerritory(t);
                    plugin.getMessages().send(sender, "admin-deleted", "%chunks%", String.valueOf(chunks));
                } else {
                    sender.sendMessage("用法: /res admin delete <玩家名>（控制台），或游戏内站在领地内直接执行。");
                }
            }
            case "deletechunk" -> {
                if (p == null) {
                    sender.sendMessage("请在游戏内站在目标区块上执行。");
                    return true;
                }
                Territory t = tm.getAt(p.getLocation());
                if (t == null) {
                    plugin.getMessages().send(p, "not-in-territory");
                    return true;
                }
                long packed = TerritoryManager.pack(p.getLocation().getBlockX() >> 4,
                        p.getLocation().getBlockZ() >> 4);
                tm.adminRemoveChunk(t, packed);
                Territory remain = tm.get(t.getId());
                plugin.getMessages().send(sender, "admin-deleted-chunk",
                        "%chunks%", String.valueOf(remain != null ? remain.getChunks().size() : 0));
            }
            default -> sender.sendMessage("用法: /res admin <whoami|reload|list|info|delete|deletechunk>");
        }
        return true;
    }

    /* ========================= 权限节点 ========================= */

    /** 子命令 → 权限节点；help 与 admin 返回 null（admin 由 isAdmin 单独判定） */
    private static String permNode(String sub) {
        return switch (sub) {
            case "claim" -> "territory.command.claim";
            case "unclaim" -> "territory.command.unclaim";
            case "info", "list" -> "territory.command.info";
            case "tp" -> "territory.command.tp";
            case "settp" -> "territory.command.settp";
            case "trust", "untrust" -> "territory.command.trust";
            case "ban", "unban" -> "territory.command.ban";
            case "gui" -> "territory.command.gui";
            case "view" -> "territory.command.view";
            case "link" -> "territory.command.link";
            default -> null;
        };
    }

    /* ========================= Tab 补全 ========================= */

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            Player player = sender instanceof Player pl ? pl : null;
            for (String s : PLAYER_SUBS) {
                if (!s.startsWith(prefix)) continue;
                if (player != null) {
                    String node = permNode(s);
                    if (node != null && !player.hasPermission(node)) continue;
                    if (s.equals("admin") && !plugin.getTerritories().isAdmin(player)) continue;
                }
                result.add(s);
            }
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("trust") || sub.equals("untrust") || sub.equals("ban") || sub.equals("unban")) {
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (online.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                        result.add(online.getName());
                    }
                }
            } else if (sub.equals("tp")) {
                if ("list".startsWith(args[1].toLowerCase())) result.add("list");
            } else if (sub.equals("link")) {
                for (String s : LINK_SUBS) {
                    if (s.startsWith(args[1].toLowerCase())) result.add(s);
                }
            } else if (sub.equals("admin")) {
                for (String s : ADMIN_SUBS) {
                    if (s.startsWith(args[1].toLowerCase())) result.add(s);
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("link")
                && !args[1].equalsIgnoreCase("add") && !args[1].equalsIgnoreCase("list")
                && !args[1].equalsIgnoreCase("cancel")) {
            Player player = sender instanceof Player pl ? pl : null;
            if (player != null) {
                Territory t = plugin.getTerritories().getAt(player.getLocation());
                if (t != null) {
                    for (int i = 1; i <= t.getLinks().size(); i++) {
                        result.add(String.valueOf(i));
                    }
                }
            }
        } else if (args.length == 4 && args[0].equalsIgnoreCase("link")
                && args[1].equalsIgnoreCase("mode")) {
            for (String s : List.of("public", "trusted")) {
                if (s.startsWith(args[3].toLowerCase())) result.add(s);
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("admin")
                && (args[1].equalsIgnoreCase("list") || args[1].equalsIgnoreCase("delete"))) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase().startsWith(args[2].toLowerCase())) {
                    result.add(online.getName());
                }
            }
        }
        return result;
    }
}
