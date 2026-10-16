package com.lcxws.territory.listener;

import com.lcxws.territory.Module;
import com.lcxws.territory.Territory;
import com.lcxws.territory.TerritoryPlugin;
import com.lcxws.territory.gui.Actions;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全 GUI 化领地管理：
 * /res 或 /res gui 打开主菜单，圈地/放弃/保护模块/白名单/黑名单/传送点/传送/粒子全部点选。
 * 白/黑名单加人支持聊天栏输入玩家名（输入"取消"退出，30秒超时）。
 */
public class GuiListener implements Listener {

    public static final String MAIN_TITLE = "§8领地菜单";
    public static final String MODULES_TITLE = "§8领地保护设置";
    public static final String WHITELIST_TITLE = "§8白名单管理";
    public static final String BLACKLIST_TITLE = "§8黑名单管理";
    public static final String TP_TITLE = "§8领地传送";

    private enum GuiType {MAIN, MODULES, WHITELIST, BLACKLIST, TPLIST}

    /** 模块槽位：从第2行第1格起，按模块数量自动排布（45格界面最多容纳20个模块） */
    private static final List<Integer> MODULE_SLOTS = buildModuleSlots();
    private static final int MODULES_BACK_SLOT = 40;
    private static final int MODULES_CLOSE_SLOT = 44;

    private static List<Integer> buildModuleSlots() {
        List<Integer> slots = new ArrayList<>();
        for (Module m : Module.values()) {
            slots.add(9 + m.ordinal());
        }
        return slots;
    }
    private static final long INPUT_TIMEOUT_MS = 30_000;

    private static class GuiSession {
        final GuiType type;
        final UUID territoryId;

        GuiSession(GuiType type, UUID territoryId) {
            this.type = type;
            this.territoryId = territoryId;
        }
    }

    private static class PendingInput {
        final UUID territoryId;
        final boolean blacklist;
        final long expireAt = System.currentTimeMillis() + INPUT_TIMEOUT_MS;

        PendingInput(UUID territoryId, boolean blacklist) {
            this.territoryId = territoryId;
            this.blacklist = blacklist;
        }
    }

    private final TerritoryPlugin plugin;
    private final Map<UUID, GuiSession> sessions = new HashMap<>();
    private final Map<UUID, PendingInput> pendingInputs = new ConcurrentHashMap<>();

    public GuiListener(TerritoryPlugin plugin) {
        this.plugin = plugin;
    }

    /* ========================= 主菜单 ========================= */

    public void openMain(Player p) {
        sessions.put(p.getUniqueId(), new GuiSession(GuiType.MAIN, null));
        Inventory inv = Bukkit.createInventory(null, 27, MAIN_TITLE);
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ", null);
        for (int i = 0; i < 27; i++) inv.setItem(i, filler);

        Territory here = plugin.getTerritories().getAt(p.getLocation());
        boolean owner = here != null && here.isOwner(p.getUniqueId());

        inv.setItem(4, buildInfoItem(p, here));
        inv.setItem(10, buildClaimItem(p, here));
        inv.setItem(11, owner
                ? item(Material.TNT, "§c放弃此区块",
                        Arrays.asList("§7放弃脚下区块", "§7退款: §6" + refundText(), "", "§e点击执行"))
                : item(Material.GRAY_DYE, "§8放弃此区块", List.of("§7需要站在自己的领地内")));
        inv.setItem(12, owner ? item(Material.SHIELD, "§6保护模块",
                        Arrays.asList("§7爆炸/生物/PvP/火焰", "§7每领地一次性购买，可随时开关", "", "§e点击打开"))
                : item(Material.GRAY_DYE, "§8保护模块", List.of("§7需要站在自己的领地内")));
        inv.setItem(13, owner ? item(Material.WRITABLE_BOOK, "§a白名单管理",
                        Arrays.asList("§7信任的玩家可完全使用领地", "", "§e点击打开"))
                : item(Material.GRAY_DYE, "§8白名单管理", List.of("§7需要站在自己的领地内")));
        inv.setItem(14, owner ? item(Material.RED_DYE, "§c黑名单管理",
                        Arrays.asList("§7黑名单玩家禁止进入领地", "", "§e点击打开"))
                : item(Material.GRAY_DYE, "§8黑名单管理", List.of("§7需要站在自己的领地内")));
        inv.setItem(15, owner ? item(Material.ENDER_PEARL, "§d设置传送点",
                        Arrays.asList("§7传送点设置为你当前所在位置", "", "§e点击执行"))
                : item(Material.GRAY_DYE, "§8设置传送点", List.of("§7需要站在自己的领地内")));
        inv.setItem(16, here != null ? item(Material.GLOWSTONE_DUST, "§b显示边界粒子",
                        Arrays.asList("§7显示当前领地边界 " + plugin.getConfig().getInt("view-particle-seconds", 30) + " 秒", "", "§e点击执行"))
                : item(Material.GRAY_DYE, "§8显示边界粒子", List.of("§7需要站在领地内")));
        inv.setItem(20, item(Material.COMPASS, "§a传送到领地",
                Arrays.asList("§7查看你可以传送的领地", "", "§e点击打开")));
        inv.setItem(22, item(Material.BARRIER, "§c关闭", null));
        if (plugin.getTerritories().isSuperAdmin(p.getUniqueId())) {
            inv.setItem(26, item(Material.COMMAND_BLOCK, "§4超级管理",
                    List.of("§7点击查看管理命令")));
        }
        p.openInventory(inv);
    }

    private String refundText() {
        double price = plugin.getConfig().getDouble("chunk-price", 2500);
        double ratio = Math.max(0, Math.min(1, plugin.getConfig().getDouble("refund-ratio", 0.5)));
        return plugin.getEconomy().format(Math.round(price * ratio));
    }

    private ItemStack buildInfoItem(Player p, Territory here) {
        if (here == null) {
            return item(Material.PAPER, "§e当前区块: 无人认领",
                    Arrays.asList("§7站在想要的区块内点击", "§e购买此区块 §7即可圈地"));
        }
        if (here.isOwner(p.getUniqueId())) {
            return item(Material.BOOK, "§a你的领地", Arrays.asList(
                    "§7区块数: §f" + here.getChunks().size(),
                    "§7主人: §f你",
                    moduleSummary(here)));
        }
        String identity = here.isBlacklisted(p.getUniqueId()) ? "§c黑名单"
                : here.isTrusted(p.getUniqueId()) ? "§a白名单" : "§7访客";
        return item(Material.BOOK, "§e" + plugin.getTerritories().nameFor(here.getOwner()) + " 的领地",
                Arrays.asList(
                        "§7区块数: §f" + here.getChunks().size(),
                        "§7你的身份: " + identity,
                        moduleSummary(here)));
    }

    private String moduleSummary(Territory t) {
        if (t.getPurchased().isEmpty()) return "§7模块: §8未购买";
        StringBuilder sb = new StringBuilder("§7模块: ");
        for (Module m : Module.values()) {
            if (!t.getPurchased().contains(m)) continue;
            sb.append(t.getEnabled().contains(m) ? "§a" : "§7").append(m.display).append(" ");
        }
        return sb.toString().trim();
    }

    private ItemStack buildClaimItem(Player p, Territory here) {
        double price = plugin.getConfig().getDouble("chunk-price", 2500);
        if (here == null) {
            return item(Material.EMERALD_BLOCK, "§a购买此区块", Arrays.asList(
                    "§7价格: §6" + plugin.getEconomy().format(price),
                    "§7余额: §f" + plugin.getEconomy().format(plugin.getEconomy().balance(p)),
                    "", "§e点击购买，相邻区块自动合并"));
        }
        if (here.isOwner(p.getUniqueId())) {
            return item(Material.LIME_DYE, "§a已是你的领地", List.of("§7无需重复购买"));
        }
        return item(Material.RED_DYE, "§c已被占用",
                List.of("§7主人: §f" + plugin.getTerritories().nameFor(here.getOwner())));
    }

    /* ========================= 保护模块 GUI ========================= */

    public void openModules(Player p, Territory t) {
        sessions.put(p.getUniqueId(), new GuiSession(GuiType.MODULES, t.getId()));
        Inventory inv = Bukkit.createInventory(null, 45, MODULES_TITLE);
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ", null);
        for (int i = 0; i < 45; i++) inv.setItem(i, filler);
        inv.setItem(4, buildInfoItem(p, t));
        for (int i = 0; i < Module.values().length; i++) {
            inv.setItem(MODULE_SLOTS.get(i), buildModuleItem(t, Module.values()[i]));
        }
        inv.setItem(MODULES_BACK_SLOT, item(Material.ARROW, "§e返回主菜单", null));
        inv.setItem(MODULES_CLOSE_SLOT, item(Material.BARRIER, "§c关闭", null));
        p.openInventory(inv);
    }

    private ItemStack buildModuleItem(Territory t, Module m) {
        double price = m.price(plugin);
        String status;
        List<String> lore = new ArrayList<>();
        lore.add("§7" + m.desc);
        lore.add("");
        if (t.getPurchased().contains(m)) {
            status = t.getEnabled().contains(m) ? "§a✔ 已开启" : "§e○ 已关闭";
            lore.add("§7点击切换开关（无需再次付费）");
        } else {
            status = "§c✘ 未购买";
            lore.add("§7点击购买: §6" + plugin.getEconomy().format(price) + "§7（每个领地一次性）");
        }
        lore.add(0, "§7状态: " + status);
        return item(m.icon, "§6" + m.display, lore);
    }

    /* ========================= 白名单 / 黑名单 GUI ========================= */

    public void openMembers(Player p, Territory t, boolean blacklist) {
        sessions.put(p.getUniqueId(), new GuiSession(blacklist ? GuiType.BLACKLIST : GuiType.WHITELIST, t.getId()));
        Inventory inv = Bukkit.createInventory(null, 36, blacklist ? BLACKLIST_TITLE : WHITELIST_TITLE);
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ", null);
        for (int i = 0; i < 9; i++) inv.setItem(i, filler);

        inv.setItem(4, blacklist
                ? item(Material.RED_DYE, "§c➕ 添加黑名单", Arrays.asList("§7点击后在聊天栏输入玩家名", "§7输入 取消 可退出"))
                : item(Material.LIME_DYE, "§a➕ 添加白名单", Arrays.asList("§7点击后在聊天栏输入玩家名", "§7输入 取消 可退出")));
        inv.setItem(8, item(Material.ARROW, "§e返回主菜单", null));

        List<UUID> members = new ArrayList<>(blacklist ? t.getBlacklist() : t.getWhitelist());
        members.sort(Comparator.comparing(u -> plugin.getTerritories().nameFor(u), String.CASE_INSENSITIVE_ORDER));
        for (int i = 0; i < members.size() && 9 + i < 36; i++) {
            UUID u = members.get(i);
            OfflinePlayer off = Bukkit.getOfflinePlayer(u);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            if (meta != null) {
                meta.setOwningPlayer(off);
                meta.setDisplayName("§f" + plugin.getTerritories().nameFor(u));
                meta.setLore(List.of(blacklist ? "§c禁止进入领地" : "§a完全信任", "", "§e点击移除"));
                head.setItemMeta(meta);
            }
            inv.setItem(9 + i, head);
        }
        p.openInventory(inv);
    }

    /* ========================= 传送列表 GUI ========================= */

    public void openTpList(Player p) {
        sessions.put(p.getUniqueId(), new GuiSession(GuiType.TPLIST, null));
        List<Territory> accessible = plugin.getTerritories().tpAccessibleOf(p.getUniqueId());
        int size = Math.max(27, ((accessible.size() + 8) / 9 + 1) * 9);
        Inventory inv = Bukkit.createInventory(null, size, TP_TITLE);
        for (int i = 0; i < accessible.size(); i++) {
            Territory t = accessible.get(i);
            long[] c = Actions.centerChunk(t);
            List<String> lore = new ArrayList<>();
            lore.add("§7主人: §f" + plugin.getTerritories().nameFor(t.getOwner()));
            lore.add("§7区块数: §f" + t.getChunks().size());
            lore.add("§7中心: §f" + c[0] + ", " + c[1]);
            lore.add(t.hasTp() ? "§7传送点: §a已设置" : "§7传送点: §8未设置(传到中心地表)");
            lore.add("");
            lore.add("§e点击传送");
            inv.setItem(i, item(Material.COMPASS, "§f#" + (i + 1) + " " + plugin.getTerritories().nameFor(t.getOwner()) + " 的领地", lore));
        }
        inv.setItem(size - 1, item(Material.ARROW, "§e返回主菜单", null));
        p.openInventory(inv);
    }

    /* ========================= 点击分发 ========================= */

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        String title = event.getView().getTitle();
        if (!isOurGui(title)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;

        GuiSession s = sessions.get(p.getUniqueId());
        if (s == null) return;

        int slot = event.getSlot();
        switch (s.type) {
            case MAIN -> mainClick(p, slot);
            case MODULES -> modulesClick(p, s, slot);
            case WHITELIST, BLACKLIST -> membersClick(p, s, slot, s.type == GuiType.BLACKLIST);
            case TPLIST -> tpListClick(p, slot);
        }
    }

    private void mainClick(Player p, int slot) {
        Territory here = plugin.getTerritories().getAt(p.getLocation());
        boolean owner = here != null && here.isOwner(p.getUniqueId());
        switch (slot) {
            case 10 -> {
                if (here == null) Actions.claim(plugin, p);
                else if (!here.isOwner(p.getUniqueId())) {
                    plugin.getMessages().send(p, "owned-by-other",
                            "%owner%", plugin.getTerritories().nameFor(here.getOwner()));
                    return;
                } else {
                    plugin.getMessages().send(p, "already-own");
                    return;
                }
                refresh(p);
            }
            case 11 -> {
                if (!owner) return;
                Actions.unclaim(plugin, p);
                refresh(p);
            }
            case 12 -> {
                if (!owner) return;
                openNextTick(p, () -> openModules(p, here));
            }
            case 13 -> {
                if (!owner) return;
                openNextTick(p, () -> openMembers(p, here, false));
            }
            case 14 -> {
                if (!owner) return;
                openNextTick(p, () -> openMembers(p, here, true));
            }
            case 15 -> {
                if (!owner) return;
                Actions.setTp(plugin, p);
                refresh(p);
            }
            case 16 -> {
                if (here != null) Actions.view(plugin, p);
            }
            case 20 -> openNextTick(p, () -> openTpList(p));
            case 22 -> p.closeInventory();
            case 26 -> {
                if (plugin.getTerritories().isSuperAdmin(p.getUniqueId())) {
                    p.sendMessage("§6管理命令: /res admin <whoami|reload|list|info|delete|deletechunk>");
                }
            }
            default -> {
            }
        }
    }

    private void modulesClick(Player p, GuiSession s, int slot) {
        Territory t = plugin.getTerritories().get(s.territoryId);
        if (t == null) {
            openNextTick(p, () -> openMain(p));
            return;
        }
        if (slot == MODULES_BACK_SLOT) {
            openNextTick(p, () -> openMain(p));
            return;
        }
        if (slot == MODULES_CLOSE_SLOT) {
            p.closeInventory();
            return;
        }
        Module m = moduleAtSlot(slot);
        if (m == null) return;
        Inventory inv = eventTopInventory(p);
        if (inv == null) return;
        handleModuleAction(p, t, m, inv);
    }

    /** 模块购买/开关：来源可以是主菜单打开的模块 GUI */
    private void handleModuleAction(Player p, Territory t, Module m, Inventory inv) {
        double price = m.price(plugin);
        if (t.getPurchased().contains(m)) {
            if (t.getEnabled().contains(m)) {
                t.getEnabled().remove(m);
                plugin.getMessages().send(p, "module-toggle-off", "%module%", m.display);
            } else {
                t.getEnabled().add(m);
                plugin.getMessages().send(p, "module-toggle-on", "%module%", m.display);
            }
            plugin.getTerritories().markDirty(t);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.4f);
            inv.setItem(slotOf(m), buildModuleItem(t, m));
            inv.setItem(4, buildInfoItem(p, t));
            return;
        }
        if (!plugin.getEconomy().available()) {
            plugin.getMessages().send(p, "no-economy");
            return;
        }
        if (!plugin.getEconomy().has(p, price) || !plugin.getEconomy().take(p, price)) {
            plugin.getMessages().send(p, "not-enough-money", "%need%", plugin.getEconomy().format(price));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1f);
            return;
        }
        t.getPurchased().add(m);
        t.getEnabled().add(m);
        plugin.getTerritories().markDirty(t);
        plugin.getMessages().send(p, "module-buy-success",
                "%module%", m.display, "%price%", plugin.getEconomy().format(price));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.9f, 1.2f);
        inv.setItem(slotOf(m), buildModuleItem(t, m));
        inv.setItem(4, buildInfoItem(p, t));
    }

    private void membersClick(Player p, GuiSession s, int slot, boolean blacklist) {
        Territory t = plugin.getTerritories().get(s.territoryId);
        if (t == null) {
            openNextTick(p, () -> openMain(p));
            return;
        }
        if (slot == 8) {
            openNextTick(p, () -> openMain(p));
            return;
        }
        if (slot == 4) {
            pendingInputs.put(p.getUniqueId(), new PendingInput(t.getId(), blacklist));
            p.closeInventory();
            plugin.getMessages().send(p, "gui-input-prompt");
            return;
        }
        if (slot < 9) return;
        List<UUID> members = new ArrayList<>(blacklist ? t.getBlacklist() : t.getWhitelist());
        members.sort(Comparator.comparing(u -> plugin.getTerritories().nameFor(u), String.CASE_INSENSITIVE_ORDER));
        int index = slot - 9;
        if (index >= members.size()) return;
        UUID target = members.get(index);
        Actions.modifyList(plugin, p, t, plugin.getTerritories().nameFor(target), false, blacklist);
        openNextTick(p, () -> openMembers(p, t, blacklist));
    }

    private void tpListClick(Player p, int slot) {
        List<Territory> accessible = plugin.getTerritories().tpAccessibleOf(p.getUniqueId());
        if (slot >= accessible.size()) {
            if (slot == accessible.size()) {
                // 返回按钮所在槽位
            }
            openNextTick(p, () -> openMain(p));
            return;
        }
        Territory t = accessible.get(slot);
        p.closeInventory();
        Actions.teleport(plugin, p, t);
    }

    /* ========================= 聊天栏输入 ========================= */

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        PendingInput pi = pendingInputs.get(event.getPlayer().getUniqueId());
        if (pi == null) return;
        if (System.currentTimeMillis() > pi.expireAt) {
            pendingInputs.remove(event.getPlayer().getUniqueId());
            return; // 超时，当普通聊天处理
        }
        pendingInputs.remove(event.getPlayer().getUniqueId());
        event.setCancelled(true);
        Player p = event.getPlayer();
        String name = event.getMessage().trim();
        if (name.equalsIgnoreCase("取消")) {
            plugin.getMessages().send(p, "gui-input-cancelled");
            Bukkit.getScheduler().runTask(plugin, () -> {
                Territory t = plugin.getTerritories().get(pi.territoryId);
                if (t != null) openMembers(p, t, pi.blacklist);
                else openMain(p);
            });
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            Territory t = plugin.getTerritories().get(pi.territoryId);
            if (t == null) {
                plugin.getMessages().send(p, "not-in-territory");
                return;
            }
            Actions.modifyList(plugin, p, t, name, true, pi.blacklist);
            openMembers(p, t, pi.blacklist);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
        pendingInputs.remove(event.getPlayer().getUniqueId());
    }

    /* ========================= 拖拽与通用 ========================= */

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (isOurGui(event.getView().getTitle()) && event.getView().getTopInventory() != null) {
            int size = event.getView().getTopInventory().getSize();
            for (int raw : event.getRawSlots()) {
                if (raw < size) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    private boolean isOurGui(String title) {
        return MAIN_TITLE.equals(title) || MODULES_TITLE.equals(title)
                || WHITELIST_TITLE.equals(title) || BLACKLIST_TITLE.equals(title)
                || TP_TITLE.equals(title);
    }

    /** 操作后刷新主菜单（下一 tick 重开，避免在点击事件内直接 openInventory 造成闪烁） */
    private void refresh(Player p) {
        openNextTick(p, () -> openMain(p));
    }

    private void openNextTick(Player p, Runnable open) {
        Bukkit.getScheduler().runTask(plugin, open);
    }

    private Inventory eventTopInventory(Player p) {
        if (p.getOpenInventory() != null) return p.getOpenInventory().getTopInventory();
        return null;
    }

    private Module moduleAtSlot(int slot) {
        int index = MODULE_SLOTS.indexOf(slot);
        Module[] values = Module.values();
        if (index >= 0 && index < values.length) return values[index];
        return null;
    }

    private int slotOf(Module m) {
        return MODULE_SLOTS.get(m.ordinal());
    }

    private ItemStack item(Material mat, String name, List<String> lore) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null) meta.setLore(lore);
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
