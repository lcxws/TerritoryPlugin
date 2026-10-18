package com.lcxws.territory.listener;

import com.lcxws.territory.BlockKey;
import com.lcxws.territory.InteractionLink;
import com.lcxws.territory.Territory;
import com.lcxws.territory.TerritoryPlugin;
import com.lcxws.territory.api.TerritoryInfo;
import com.lcxws.territory.api.TerritoryLinkInfo;
import com.lcxws.territory.api.event.TerritoryLinkUseEvent;
import com.lcxws.territory.manager.TerritoryManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Gate;
import org.bukkit.block.data.type.Switch;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 交互链接：领主用“外部按钮/拉杆”控制领地内的门。
 *
 * <p>事件顺序很关键：
 * <ul>
 *   <li>选择模式监听器在 LOWEST 取消点击，避免绑定过程中误开真正的门；</li>
 *   <li>触发监听器在 HIGHEST 且 ignoreCancelled=true，排在 ProtectionListener 之后。
 *       因此源方块若在别人领地内且访客无权点击，链接不会绕过领地保护。</li>
 * </ul>
 */
public class LinkListener implements Listener {

    private static final long SELECTION_TIMEOUT_MS = 60_000L;
    private static final BlockFace[] HORIZONTAL = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private final TerritoryPlugin plugin;
    /** 玩家 -> 正在进行的绑定选择 */
    private final Map<UUID, Pending> pending = new HashMap<>();
    /** 玩家 -> (链接 -> 上次使用时间)，防止按钮/门被高频点击 */
    private final Map<UUID, Map<UUID, Long>> cooldowns = new HashMap<>();
    /** 链接 -> 自动关门任务 */
    private final Map<UUID, BukkitTask> closeTasks = new HashMap<>();

    public LinkListener(TerritoryPlugin plugin) {
        this.plugin = plugin;
    }

    private static final class Pending {
        final boolean fromGui;
        BlockKey source;
        boolean leverSource;
        long expiresAt;
        BukkitTask timeout;

        Pending(boolean fromGui) {
            this.fromGui = fromGui;
        }
    }

    /* ========================= 绑定选择 ========================= */

    /** 命令入口：不自动重开 GUI */
    public boolean beginSelection(Player p) {
        return beginSelection(p, false);
    }

    /**
     * 进入两步绑定模式：先右键源按钮/拉杆，再右键领地内目标门。
     *
     * @param fromGui 完成后是否自动重新打开链接列表
     */
    public boolean beginSelection(Player p, boolean fromGui) {
        if (!plugin.getConfig().getBoolean("link.enabled", true)) {
            plugin.getMessages().send(p, "link-disabled");
            return false;
        }
        if (!p.hasPermission("territory.command.link")) {
            plugin.getMessages().send(p, "no-permission");
            return false;
        }
        Territory here = plugin.getTerritories().getAt(p.getLocation());
        if (here == null || !here.isOwner(p.getUniqueId())) {
            plugin.getMessages().send(p, "link-need-own-territory");
            return false;
        }

        cancelSelection(p, false);
        Pending pend = new Pending(fromGui);
        pending.put(p.getUniqueId(), pend);
        scheduleTimeout(p, pend);

        plugin.getMessages().send(p, "link-source-prompt");
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.2f);
        return true;
    }

    public void cancelSelection(Player p, boolean notify) {
        Pending pend = pending.remove(p.getUniqueId());
        if (pend != null && pend.timeout != null) pend.timeout.cancel();
        if (notify) plugin.getMessages().send(p, "link-cancelled");
    }

    public boolean isSelecting(Player p) {
        return pending.containsKey(p.getUniqueId());
    }

    private void scheduleTimeout(Player p, Pending pend) {
        if (pend.timeout != null) pend.timeout.cancel();
        pend.expiresAt = System.currentTimeMillis() + SELECTION_TIMEOUT_MS;
        pend.timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Pending current = pending.get(p.getUniqueId());
            if (current == pend) {
                pending.remove(p.getUniqueId());
                if (p.isOnline()) plugin.getMessages().send(p, "link-timeout");
            }
        }, SELECTION_TIMEOUT_MS / 50L);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSelectInteract(PlayerInteractEvent event) {
        Pending pend = pending.get(event.getPlayer().getUniqueId());
        if (pend == null) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        // 选择期间连副手交互也取消，避免一次右键开两次门
        event.setCancelled(true);
        if (event.getHand() == EquipmentSlot.OFF_HAND) return;

        Player p = event.getPlayer();
        Block block = event.getClickedBlock();
        if (block == null) return;

        if (p.isSneaking()) {
            cancelSelection(p, true);
            return;
        }
        if (System.currentTimeMillis() > pend.expiresAt) {
            cancelSelection(p, false);
            plugin.getMessages().send(p, "link-timeout");
            return;
        }

        if (pend.source == null) {
            selectSource(p, pend, block);
        } else {
            selectTarget(p, pend, block);
        }
    }

    private void selectSource(Player p, Pending pend, Block block) {
        if (!isTriggerBlock(block)) {
            plugin.getMessages().send(p, "link-source-invalid");
            return;
        }
        if (!plugin.getTerritories().isWorldEnabled(block.getWorld().getName())) {
            plugin.getMessages().send(p, "world-disabled");
            return;
        }
        Territory sourceTerritory = plugin.getTerritories().getAt(block.getLocation());
        if (sourceTerritory != null && !sourceTerritory.getOwner().equals(p.getUniqueId())) {
            plugin.getMessages().send(p, "link-source-foreign");
            return;
        }

        pend.source = BlockKey.of(block);
        pend.leverSource = block.getType() == Material.LEVER;
        scheduleTimeout(p, pend);
        plugin.getMessages().send(p, "link-target-prompt");
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.0f);
    }

    private void selectTarget(Player p, Pending pend, Block block) {
        if (!isTargetBlock(block)) {
            plugin.getMessages().send(p, "link-target-invalid");
            return;
        }
        Territory targetTerritory = plugin.getTerritories().getAt(block.getLocation());
        if (targetTerritory == null || !targetTerritory.isOwner(p.getUniqueId())) {
            plugin.getMessages().send(p, "link-target-not-own");
            return;
        }

        BlockKey target = BlockKey.of(block);
        if (!pend.source.getWorld().equals(target.getWorld())) {
            plugin.getMessages().send(p, "link-cross-world");
            return;
        }
        if (pend.source.equals(target)) {
            plugin.getMessages().send(p, "link-same-block");
            return;
        }
        int max = plugin.getConfig().getInt("link.max-per-territory", 8);
        if (max >= 0 && plugin.getTerritories().linkCount(targetTerritory) >= max) {
            plugin.getMessages().send(p, "link-limit", "%max%", String.valueOf(max));
            return;
        }
        for (InteractionLink existing : targetTerritory.getLinks()) {
            if (existing.getSource().equals(pend.source)) {
                plugin.getMessages().send(p, "link-duplicate");
                return;
            }
        }
        int maxDistance = plugin.getConfig().getInt("link.max-distance", 0);
        if (maxDistance > 0) {
            Location sourceLoc = pend.source.toLocation();
            Location targetLoc = target.toLocation();
            if (sourceLoc != null && targetLoc != null && sourceLoc.distance(targetLoc) > maxDistance) {
                plugin.getMessages().send(p, "link-too-far", "%max%", String.valueOf(maxDistance));
                return;
            }
        }

        InteractionLink.Mode mode = InteractionLink.Mode.byName(
                plugin.getConfig().getString("link.default-mode", "PUBLIC"));
        int autoClose = pend.leverSource ? 0
                : Math.max(0, plugin.getConfig().getInt("link.default-auto-close-seconds", 5));
        InteractionLink link = InteractionLink.create(targetTerritory.getId(), pend.source, target, mode, autoClose);
        if (!plugin.getTerritories().addLink(targetTerritory, link)) {
            plugin.getMessages().send(p, "link-duplicate");
            return;
        }

        cancelSelection(p, false);
        plugin.getMessages().send(p, "link-created",
                "%source%", formatKey(pend.source), "%target%", formatKey(target));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.6f);

        if (pend.fromGui) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) plugin.getGui().openLinks(p, targetTerritory);
            });
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        Pending pend = pending.remove(p.getUniqueId());
        if (pend != null && pend.timeout != null) pend.timeout.cancel();
        cooldowns.remove(p.getUniqueId());
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        if (pending.containsKey(event.getPlayer().getUniqueId())) {
            cancelSelection(event.getPlayer(), false);
            plugin.getMessages().send(event.getPlayer(), "link-cross-world");
        }
    }

    /* ========================= 触发链接 ========================= */

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUse(PlayerInteractEvent event) {
        if (!plugin.getConfig().getBoolean("link.enabled", true)) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() == EquipmentSlot.OFF_HAND) return;
        Block source = event.getClickedBlock();
        if (source == null) return;

        List<InteractionLink> links = plugin.getTerritories().linksAt(BlockKey.of(source));
        if (links.isEmpty()) return;

        Player p = event.getPlayer();
        for (InteractionLink link : new ArrayList<>(links)) {
            useLink(p, source, link);
        }
    }

    /** 方块被破坏时清理源/目标链接；爆炸等无 BlockBreakEvent 的情况由使用时惰性校验兜底 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        BlockKey key = BlockKey.of(block);
        for (InteractionLink link : new ArrayList<>(plugin.getTerritories().linksAt(key))) {
            plugin.getTerritories().removeLink(link);
        }
        plugin.getTerritories().removeLinksTargeting(key);
        // 链接可能记录的是门的另一半
        if (block.getBlockData() instanceof Door door) {
            Block other = door.getHalf() == Bisected.Half.TOP
                    ? block.getRelative(BlockFace.DOWN)
                    : block.getRelative(BlockFace.UP);
            plugin.getTerritories().removeLinksTargeting(BlockKey.of(other));
        }
    }

    private void useLink(Player p, Block source, InteractionLink link) {
        TerritoryManager tm = plugin.getTerritories();
        if (!link.isEnabled()) return;
        if (!isTriggerBlock(source)) {
            tm.removeLink(link);
            return;
        }

        Territory territory = tm.get(link.getTerritoryId());
        if (territory == null) {
            tm.removeLink(link);
            return;
        }
        BlockKey targetKey = link.getTarget();
        Territory targetTerritory = tm.getAt(targetKey.getWorld(),
                targetKey.getChunkX(), targetKey.getChunkZ());
        if (targetTerritory == null || !targetTerritory.getId().equals(territory.getId())) {
            tm.removeLink(link);
            logInvalid(link);
            return;
        }

        boolean bypass = tm.bypassProtection(p);
        // 源方块若在别人领地内，即使点击者被那个领地信任，也不能越过目标领主来触发
        Territory sourceTerritory = tm.getAt(source.getLocation());
        if (!bypass && sourceTerritory != null
                && !sourceTerritory.getOwner().equals(territory.getOwner())) {
            plugin.getMessages().send(p, "link-source-foreign-use");
            return;
        }
        if (!bypass && territory.isBlacklisted(p.getUniqueId())) {
            plugin.getMessages().send(p, "link-blacklisted");
            return;
        }
        if (!bypass && !link.isPublic() && !territory.isTrusted(p.getUniqueId())) {
            plugin.getMessages().send(p, "link-trusted-only");
            return;
        }

        long now = System.currentTimeMillis();
        int cooldownMs = Math.max(0, plugin.getConfig().getInt("link.cooldown-ms", 500));
        if (cooldownMs > 0) {
            Map<UUID, Long> perLink = cooldowns.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
            Long last = perLink.get(link.getId());
            if (last != null && now - last < cooldownMs) return;
            perLink.put(link.getId(), now);
        }

        Location targetLoc = targetKey.toLocation();
        if (targetLoc == null) {
            tm.removeLink(link);
            logInvalid(link);
            plugin.getMessages().send(p, "link-invalid-removed");
            return;
        }
        Block target = targetLoc.getBlock();
        if (!isTargetBlock(target)) {
            tm.removeLink(link);
            logInvalid(link);
            plugin.getMessages().send(p, "link-invalid-removed");
            return;
        }
        Block powerCheck = target;
        BlockData targetData = target.getBlockData();
        if (targetData instanceof Door door && door.getHalf() == Bisected.Half.TOP) {
            powerCheck = target.getRelative(BlockFace.DOWN);
        }
        if (powerCheck.isBlockPowered()) {
            plugin.getMessages().send(p, "link-powered");
            return;
        }

        TerritoryLinkUseEvent useEvent = new TerritoryLinkUseEvent(
                p,
                TerritoryInfo.of(territory, tm.nameFor(territory.getOwner())),
                TerritoryLinkInfo.of(link),
                source.getLocation(),
                target.getLocation());
        Bukkit.getPluginManager().callEvent(useEvent);
        if (useEvent.isCancelled()) return;

        boolean lever = source.getType() == Material.LEVER;
        boolean desired;
        if (lever) {
            BlockData sourceData = source.getBlockData();
            desired = sourceData instanceof Switch sw ? !sw.isPowered() : !isOpen(target);
        } else if (link.getAutoCloseSeconds() > 0) {
            // 自动关门模式下，按钮只负责“开门并重新计时”，不会把别人刚开的门关上
            desired = true;
        } else {
            desired = !isOpen(target);
        }

        boolean changed = setTargetOpen(target, desired);
        if (changed) {
            playOpenSound(target, desired);
        }
        if (!lever) {
            if (desired && link.getAutoCloseSeconds() > 0) {
                scheduleClose(link, targetKey, link.getAutoCloseSeconds());
            } else {
                cancelClose(link.getId());
            }
        } else {
            cancelClose(link.getId());
        }
    }

    private void logInvalid(InteractionLink link) {
        plugin.getLogger().info("交互链接目标失效，已自动删除: "
                + link.getSource().serialize() + " -> " + link.getTarget().serialize());
    }

    /* ========================= 目标方块动作 ========================= */

    private static boolean isTriggerBlock(Block block) {
        Material m = block.getType();
        return m == Material.LEVER || m.name().endsWith("_BUTTON");
    }

    private static boolean isTargetBlock(Block block) {
        BlockData data = block.getBlockData();
        return data instanceof Door || data instanceof TrapDoor || data instanceof Gate;
    }

    private static boolean isOpen(Block block) {
        BlockData data = block.getBlockData();
        if (data instanceof Door door && door.getHalf() == Bisected.Half.TOP) {
            data = block.getRelative(BlockFace.DOWN).getBlockData();
        }
        return data instanceof Openable openable && openable.isOpen();
    }

    private static boolean setTargetOpen(Block target, boolean open) {
        BlockData data = target.getBlockData();
        if (data instanceof Door) return setDoorOpen(target, open);
        if (data instanceof TrapDoor || data instanceof Gate) return applyOpen(target, open);
        return false;
    }

    private static boolean applyOpen(Block block, boolean open) {
        BlockData data = block.getBlockData();
        if (!(data instanceof Openable openable)) return false;
        if (openable.isOpen() == open) return false;
        openable.setOpen(open);
        block.setBlockData(data, false);
        return true;
    }

    /** 门需要同时设置上下两半；相邻的双开门也一起开关 */
    private static boolean setDoorOpen(Block block, boolean open) {
        Block lower = block;
        BlockData data = block.getBlockData();
        if (data instanceof Door door && door.getHalf() == Bisected.Half.TOP) {
            lower = block.getRelative(BlockFace.DOWN);
            data = lower.getBlockData();
        }
        if (!(data instanceof Door base)) return false;

        boolean changed = applyOpen(lower, open);
        Block upper = lower.getRelative(BlockFace.UP);
        if (upper.getBlockData() instanceof Door) changed |= applyOpen(upper, open);

        for (BlockFace face : HORIZONTAL) {
            Block neighbor = lower.getRelative(face);
            BlockData neighborData = neighbor.getBlockData();
            if (neighborData instanceof Door other
                    && other.getHalf() == Bisected.Half.BOTTOM
                    && neighbor.getType() == lower.getType()
                    && other.getFacing() == base.getFacing()
                    && other.getHinge() != base.getHinge()) {
                changed |= applyOpen(neighbor, open);
                Block neighborUpper = neighbor.getRelative(BlockFace.UP);
                if (neighborUpper.getBlockData() instanceof Door) changed |= applyOpen(neighborUpper, open);
                break;
            }
        }
        return changed;
    }

    private static void playOpenSound(Block target, boolean open) {
        BlockData data = target.getBlockData();
        Sound sound;
        if (data instanceof Door) {
            boolean iron = target.getType() == Material.IRON_DOOR;
            sound = iron
                    ? (open ? Sound.BLOCK_IRON_DOOR_OPEN : Sound.BLOCK_IRON_DOOR_CLOSE)
                    : (open ? Sound.BLOCK_WOODEN_DOOR_OPEN : Sound.BLOCK_WOODEN_DOOR_CLOSE);
        } else if (data instanceof TrapDoor) {
            boolean iron = target.getType() == Material.IRON_TRAPDOOR;
            sound = iron
                    ? (open ? Sound.BLOCK_IRON_TRAPDOOR_OPEN : Sound.BLOCK_IRON_TRAPDOOR_CLOSE)
                    : (open ? Sound.BLOCK_WOODEN_TRAPDOOR_OPEN : Sound.BLOCK_WOODEN_TRAPDOOR_CLOSE);
        } else if (data instanceof Gate) {
            sound = open ? Sound.BLOCK_FENCE_GATE_OPEN : Sound.BLOCK_FENCE_GATE_CLOSE;
        } else {
            return;
        }
        target.getWorld().playSound(target.getLocation(), sound, 1.0f, 1.0f);
    }

    private void scheduleClose(InteractionLink link, BlockKey targetKey, int seconds) {
        cancelClose(link.getId());
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            closeTasks.remove(link.getId());
            if (plugin.getTerritories().linkById(link.getId()) == null) return;
            Location loc = targetKey.toLocation();
            if (loc == null) return;
            Block block = loc.getBlock();
            if (!isTargetBlock(block) || !isOpen(block)) return;
            if (setTargetOpen(block, false)) playOpenSound(block, false);
        }, Math.max(1, seconds) * 20L);
        closeTasks.put(link.getId(), task);
    }

    private void cancelClose(UUID linkId) {
        BukkitTask task = closeTasks.remove(linkId);
        if (task != null) task.cancel();
    }

    private static String formatKey(BlockKey key) {
        return key.getWorld() + " " + key.getX() + "," + key.getY() + "," + key.getZ();
    }
}
