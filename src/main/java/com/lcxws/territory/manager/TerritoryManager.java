package com.lcxws.territory.manager;

import com.lcxws.territory.BlockKey;
import com.lcxws.territory.InteractionLink;
import com.lcxws.territory.Module;
import com.lcxws.territory.Territory;
import com.lcxws.territory.TerritoryPlugin;
import com.lcxws.territory.api.TerritoryInfo;
import com.lcxws.territory.api.event.TerritoryClaimEvent;
import com.lcxws.territory.api.event.TerritoryDeleteEvent;
import com.lcxws.territory.api.event.TerritoryMemberChangeEvent;
import com.lcxws.territory.api.event.TerritoryUnclaimEvent;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 领地管理器：区块索引（O(1) 查询）、连通分量合并/拆分、YAML 持久化、异步批量保存。
 */
public class TerritoryManager {

    private final TerritoryPlugin plugin;

    /** 领地本体：id -> Territory */
    private final Map<UUID, Territory> territories = new HashMap<>();
    /** 区块索引：世界名 -> (打包区块坐标 -> 领地id)，保护检查的快速路径 */
    private final Map<String, Map<Long, UUID>> chunkIndex = new HashMap<>();
    /** 主人的区块集合：主人UUID -> 世界名 -> 打包区块坐标集合（合并/拆分的 BFS 数据源） */
    private final Map<UUID, Map<String, Set<Long>>> ownerChunks = new HashMap<>();
    /** UUID -> 最近已知名字（用于展示） */
    private final Map<UUID, String> nameCache = new HashMap<>();
    /** 启用领地的世界 */
    private Set<String> enabledWorlds = new HashSet<>();
    /** 源方块 -> 交互链接列表（同一源方块可能被多个领地各自绑定） */
    private final Map<BlockKey, List<InteractionLink>> linkIndex = new HashMap<>();
    /** 链接 ID -> 链接（GUI / API / 定时关门任务使用） */
    private final Map<UUID, InteractionLink> linksById = new HashMap<>();

    private volatile boolean dirty;
    private final File dataFile;
    private final Object ioLock = new Object();

    public TerritoryManager(TerritoryPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "territories.yml");
        reloadWorlds();
    }

    public void reloadWorlds() {
        enabledWorlds = new HashSet<>(plugin.getConfig().getStringList("enabled-worlds"));
    }

    public boolean isWorldEnabled(String world) {
        return enabledWorlds.contains(world);
    }

    /* ========================= 坐标工具 ========================= */

    public static long pack(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public static int unpackX(long packed) {
        return (int) (packed >> 32);
    }

    public static int unpackZ(long packed) {
        return (int) (packed & 0xFFFFFFFFL);
    }

    /* ========================= 查询（快速路径） ========================= */

    public Territory getAt(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        return getAt(loc.getWorld().getName(), loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
    }

    public Territory getAt(Chunk chunk) {
        return getAt(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
    }

    public Territory getAt(String world, int cx, int cz) {
        Map<Long, UUID> idx = chunkIndex.get(world);
        if (idx == null) return null;
        UUID id = idx.get(pack(cx, cz));
        return id != null ? territories.get(id) : null;
    }

    public Territory get(UUID id) {
        return territories.get(id);
    }

    public int territoryCount() {
        return territories.size();
    }

    public List<Territory> territoriesOf(UUID owner) {
        List<Territory> list = new ArrayList<>();
        for (Territory t : territories.values()) {
            if (t.getOwner().equals(owner)) list.add(t);
        }
        list.sort((a, b) -> Long.compare(a.getCreated(), b.getCreated()));
        return list;
    }

    /** 领主或白名单成员可以传送的领地 */
    public List<Territory> tpAccessibleOf(UUID uuid) {
        List<Territory> list = new ArrayList<>();
        for (Territory t : territories.values()) {
            if (t.isTrusted(uuid)) list.add(t);
        }
        list.sort((a, b) -> Long.compare(a.getCreated(), b.getCreated()));
        return list;
    }

    public int countChunksOf(UUID owner) {
        Map<String, Set<Long>> byWorld = ownerChunks.get(owner);
        if (byWorld == null) return 0;
        int n = 0;
        for (Set<Long> s : byWorld.values()) n += s.size();
        return n;
    }

    /* ========================= 购买 / 放弃 ========================= */

    /** 购买脚下区块。成功返回 null，失败返回已向玩家发送的提示后的 true（此处用错误 key 列表区分） */
    public String claim(Player player) {
        String world = player.getWorld().getName();
        if (!isWorldEnabled(world)) return "world-disabled";

        Territory existing = getAt(player.getLocation());
        if (existing != null) {
            return existing.isOwner(player.getUniqueId()) ? "already-own" : "owned-by-other:"
                    + nameFor(existing.getOwner());
        }

        EconomyHook eco = plugin.getEconomy();
        if (!eco.available()) return "no-economy";

        double price = plugin.getConfig().getDouble("chunk-price", 2500);
        int max = plugin.getConfig().getInt("max-chunks-per-player", -1);
        if (max >= 0 && countChunksOf(player.getUniqueId()) >= max) {
            return "max-chunks:" + max;
        }
        if (!eco.has(player, price)) {
            return "not-enough-money:" + eco.format(price);
        }
        if (!eco.take(player, price)) {
            return "not-enough-money:" + eco.format(price);
        }

        addChunk(player.getUniqueId(), world,
                player.getLocation().getBlockX() >> 4, player.getLocation().getBlockZ() >> 4);
        return null;
    }

    /** 放弃脚下区块。返回 null 成功；带 ":" 的 key 表示附带参数 */
    public String unclaim(Player player) {
        String world = player.getWorld().getName();
        Territory t = getAt(player.getLocation());
        if (t == null) return "unclaim-no-territory";
        if (!t.isOwner(player.getUniqueId()) && !isAdmin(player)) {
            return "unclaim-not-owner";
        }

        double price = plugin.getConfig().getDouble("chunk-price", 2500);
        double ratio = Math.max(0, Math.min(1, plugin.getConfig().getDouble("refund-ratio", 0.5)));
        long refund = Math.round(price * ratio);
        if (refund > 0) plugin.getEconomy().give(player, refund);

        UUID ownerId = t.getOwner();
        long packed = pack(player.getLocation().getBlockX() >> 4, player.getLocation().getBlockZ() >> 4);
        Set<Long> set = ownerChunks(ownerId, world);
        set.remove(packed);
        int chunksBefore = t.getChunks().size();
        boolean hadModules = !t.getPurchased().isEmpty();
        Bukkit.getPluginManager().callEvent(new TerritoryUnclaimEvent(ownerId, world,
                unpackX(packed), unpackZ(packed)));
        recompute(world, ownerId);
        markDirty();

        // 拆分提示：原来是一个连通体，现在变成多个则说明发生了拆分
        int compsAfter = countComponents(ownerId, world);
        if (chunksBefore > 1 && compsAfter > 1 && hadModules) {
            player.sendMessage(plugin.getMessages().prefixed("split-modules-note"));
        }
        // 领地被完全拆除：已购模块随之失效，须提示
        if (chunksBefore == 1 && hadModules) {
            player.sendMessage(plugin.getMessages().prefixed("modules-lost-note"));
        }
        return "unclaim-success:" + plugin.getEconomy().format(refund);
    }

    private Set<Long> ownerChunks(UUID owner, String world) {
        return ownerChunks.computeIfAbsent(owner, k -> new HashMap<>())
                .computeIfAbsent(world, k -> new HashSet<>());
    }

    private int countComponents(UUID owner, String world) {
        Set<Long> chunks = ownerChunks(owner, world);
        Set<Long> visited = new HashSet<>();
        int comps = 0;
        for (Long c : chunks) {
            if (!visited.add(c)) continue;
            comps++;
            Deque<Long> stack = new ArrayDeque<>();
            stack.push(c);
            while (!stack.isEmpty()) {
                long cur = stack.pop();
                int cx = unpackX(cur), cz = unpackZ(cur);
                long[] neighbors = {pack(cx + 1, cz), pack(cx - 1, cz), pack(cx, cz + 1), pack(cx, cz - 1)};
                for (long n : neighbors) {
                    if (chunks.contains(n) && visited.add(n)) stack.push(n);
                }
            }
        }
        return comps;
    }

    /**
     * 依据主人的区块集合重建该主人在该世界的领地（处理合并与拆分）。
     * 合并：白/黑名单与已购模块并集。拆分：模块与传送点留在主分量（含传送点或最大者），
     * 拆出的新领地继承名单但不继承模块（防止“先买模块再拆分”的漏洞）。
     */
    private void recompute(String world, UUID owner) {
        Set<Long> chunks = ownerChunks(owner, world);

        // 1. 连通分量
        List<Set<Long>> comps = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        for (Long c : chunks) {
            if (!visited.add(c)) continue;
            Set<Long> comp = new HashSet<>();
            Deque<Long> stack = new ArrayDeque<>();
            stack.push(c);
            while (!stack.isEmpty()) {
                long cur = stack.pop();
                comp.add(cur);
                int cx = unpackX(cur), cz = unpackZ(cur);
                long[] neighbors = {pack(cx + 1, cz), pack(cx - 1, cz), pack(cx, cz + 1), pack(cx, cz - 1)};
                for (long n : neighbors) {
                    if (chunks.contains(n) && visited.add(n)) stack.push(n);
                }
            }
            comps.add(comp);
        }

        // 2. 收集旧领地：直接扫描该主人在该世界的全部领地。
        //    不能走区块索引反查——放弃最后一片区块时区块集合为空，反查会漏掉旧领地，
        //    导致"放弃成功但领地仍在"的幽灵领地；直接扫描还能顺带自愈索引不同步。
        Set<Territory> old = new HashSet<>();
        for (Territory t : territories.values()) {
            if (t.getOwner().equals(owner) && t.getWorld().equals(world)) old.add(t);
        }
        // 链接先摘出来，等新区块归属确定后按目标区块重新挂回对应领地。
        // 这样拆分时链接会跟着目标区块走，放弃目标区块时链接会被丢弃。
        List<InteractionLink> oldLinks = new ArrayList<>();
        for (Territory t : old) oldLinks.addAll(t.getLinks());
        Map<Long, UUID> idx = chunkIndex.get(world);

        // 3. 为每个旧领地指定“归属分量”：优先含传送点的分量，否则含区块最多的分量
        Map<UUID, Integer> home = new HashMap<>();
        for (Territory t : old) {
            int target = -1;
            if (t.hasTp() && world.equals(t.getTpPoint().getWorld().getName())) {
                long tp = pack(t.getTpPoint().getBlockX() >> 4, t.getTpPoint().getBlockZ() >> 4);
                for (int i = 0; i < comps.size(); i++) {
                    if (comps.get(i).contains(tp)) {
                        target = i;
                        break;
                    }
                }
            }
            if (target < 0) {
                long best = -1;
                for (int i = 0; i < comps.size(); i++) {
                    long cnt = 0;
                    for (Long c : comps.get(i)) if (t.getChunks().contains(c)) cnt++;
                    if (cnt > best) {
                        best = cnt;
                        target = i;
                    }
                }
            }
            if (target >= 0) home.put(t.getId(), target);
        }

        // 4. 清掉旧索引，重建分量领地
        if (idx != null) {
            for (Territory t : old) {
                for (Long c : t.getChunks()) idx.remove(c);
            }
        } else {
            idx = chunkIndex.computeIfAbsent(world, k -> new HashMap<>());
        }
        for (Territory t : old) {
            if (home.get(t.getId()) == null) {
                territories.remove(t.getId());
                fireDeleteEvent(t);
            }
        }

        for (int i = 0; i < comps.size(); i++) {
            Set<Long> comp = comps.get(i);
            // 与该分量有区块交集的旧领地（用于并集名单）
            List<Territory> overlapping = new ArrayList<>();
            for (Territory t : old) {
                for (Long c : comp) {
                    if (t.getChunks().contains(c)) {
                        overlapping.add(t);
                        break;
                    }
                }
            }
            // 该分量的代表领地：home 指向此分量的第一个旧领地
            Territory rep = null;
            for (Territory t : old) {
                if (home.get(t.getId()) != null && home.get(t.getId()) == i) {
                    rep = t;
                    break;
                }
            }
            if (rep == null) {
                rep = new Territory(UUID.randomUUID(), owner, world);
            } else {
                rep.getChunks().clear();
                rep.getWhitelist().clear();
                rep.getBlacklist().clear();
                rep.getPurchased().clear();
                rep.getEnabled().clear();
                rep.getLinks().clear();
            }
            for (Territory t : overlapping) {
                rep.getWhitelist().addAll(t.getWhitelist());
                rep.getBlacklist().addAll(t.getBlacklist());
            }
            // 模块只从“归属此分量”的旧领地继承
            for (Territory t : old) {
                if (home.get(t.getId()) != null && home.get(t.getId()) == i) {
                    rep.getPurchased().addAll(t.getPurchased());
                    rep.getEnabled().addAll(t.getEnabled());
                }
            }
            rep.getChunks().addAll(comp);
            // 传送点必须位于该领地内：放弃/拆分后若传送点所在区块已不在领地中，清空传送点
            if (rep.hasTp()) {
                Location tp = rep.getTpPoint();
                if (!comp.contains(pack(tp.getBlockX() >> 4, tp.getBlockZ() >> 4))) {
                    rep.setTpPoint(null);
                }
            }
            territories.put(rep.getId(), rep);
            for (Long c : comp) idx.put(c, rep.getId());
        }

        // 5. 链接按目标区块的新归属重新挂载；目标已不在该主人任何领地内则丢弃
        for (InteractionLink link : oldLinks) {
            BlockKey target = link.getTarget();
            Territory targetT = getAt(target.getWorld(), target.getChunkX(), target.getChunkZ());
            if (targetT == null || !targetT.getOwner().equals(owner)) continue;
            link.setTerritoryId(targetT.getId());
            targetT.getLinks().add(link);
        }
        rebuildLinkIndex();
    }

    /** 删除整个领地（管理命令用，无退款） */
    public void deleteTerritory(Territory t) {
        Map<String, Set<Long>> byWorld = ownerChunks.get(t.getOwner());
        if (byWorld != null) {
            Set<Long> set = byWorld.get(t.getWorld());
            if (set != null) set.removeAll(t.getChunks());
        }
        Map<Long, UUID> idx = chunkIndex.get(t.getWorld());
        if (idx != null) {
            for (Long c : t.getChunks()) idx.remove(c);
        }
        removeLinksOf(t);
        territories.remove(t.getId());
        markDirty();
        fireDeleteEvent(t);
    }

    /* ========================= 交互链接 ========================= */

    /** 右键源方块时待处理的链接；没有链接返回空列表 */
    public List<InteractionLink> linksAt(BlockKey source) {
        List<InteractionLink> list = linkIndex.get(source);
        return list != null ? Collections.unmodifiableList(list) : Collections.emptyList();
    }

    public List<InteractionLink> linksOf(Territory t) {
        return t != null ? Collections.unmodifiableList(t.getLinks()) : Collections.emptyList();
    }

    public InteractionLink linkById(UUID id) {
        return linksById.get(id);
    }

    public int linkCount(Territory t) {
        return t != null ? t.getLinks().size() : 0;
    }

    /** 新增链接；同一领地内同一个源方块只允许一条（防止重复触发） */
    public boolean addLink(Territory t, InteractionLink link) {
        if (t == null || link == null) return false;
        for (InteractionLink existing : t.getLinks()) {
            if (existing.getSource().equals(link.getSource())) return false;
        }
        link.setTerritoryId(t.getId());
        t.getLinks().add(link);
        indexLink(link);
        markDirty();
        return true;
    }

    public boolean removeLink(InteractionLink link) {
        if (link == null) return false;
        Territory t = territories.get(link.getTerritoryId());
        if (t != null) t.getLinks().remove(link);
        unindexLink(link);
        markDirty();
        return t != null;
    }

    public void removeLinksOf(Territory t) {
        if (t == null) return;
        for (InteractionLink link : new ArrayList<>(t.getLinks())) {
            unindexLink(link);
        }
        t.getLinks().clear();
        markDirty();
    }

    /** 目标方块被破坏时删除所有指向它的链接 */
    public void removeLinksTargeting(BlockKey target) {
        if (target == null) return;
        List<InteractionLink> remove = new ArrayList<>();
        for (InteractionLink link : linksById.values()) {
            if (link.getTarget().equals(target)) remove.add(link);
        }
        for (InteractionLink link : remove) {
            removeLink(link);
        }
    }

    /** 重建索引：载入数据、领地合并/拆分/删除后调用 */
    public void rebuildLinkIndex() {
        linkIndex.clear();
        linksById.clear();
        for (Territory t : territories.values()) {
            for (InteractionLink link : t.getLinks()) {
                link.setTerritoryId(t.getId());
                indexLink(link);
            }
        }
    }

    private void indexLink(InteractionLink link) {
        if (link == null || link.getSource() == null) return;
        linksById.put(link.getId(), link);
        linkIndex.computeIfAbsent(link.getSource(), k -> new ArrayList<>()).add(link);
    }

    private void unindexLink(InteractionLink link) {
        if (link == null) return;
        linksById.remove(link.getId(), link);
        List<InteractionLink> list = linkIndex.get(link.getSource());
        if (list != null) {
            list.remove(link);
            if (list.isEmpty()) linkIndex.remove(link.getSource());
        }
    }

    /** 管理命令：移除领地中的单个区块（无退款，走合并/拆分逻辑） */
    public void adminRemoveChunk(Territory t, long packed) {
        ownerChunks(t.getOwner(), t.getWorld()).remove(packed);
        Bukkit.getPluginManager().callEvent(new TerritoryUnclaimEvent(t.getOwner(), t.getWorld(),
                unpackX(packed), unpackZ(packed)));
        recompute(t.getWorld(), t.getOwner());
        markDirty();
    }

    /* ========================= API 支持（命令 / GUI / 对外 API 共用） ========================= */

    /**
     * 名单变更核心：纯数据操作 + 脏标记 + 事件（黑名单优先规则与命令完全一致）。
     * 不发送消息、不弹出玩家，由调用方决定提示。
     *
     * @return 是否实际发生变更（已在名单 / 不在名单等无效操作返回 false）
     */
    public boolean changeMember(Territory t, UUID player, boolean add, boolean blacklist) {
        if (t == null || player == null) return false;
        if (blacklist) {
            if (add) {
                if (t.getBlacklist().contains(player)) return false;
                t.getWhitelist().remove(player);
                t.getBlacklist().add(player);
            } else {
                if (!t.getBlacklist().remove(player)) return false;
            }
        } else {
            if (add) {
                if (t.getBlacklist().contains(player) || !t.getWhitelist().add(player)) return false;
            } else {
                if (!t.getWhitelist().remove(player)) return false;
            }
        }
        markDirty();
        Bukkit.getPluginManager().callEvent(new TerritoryMemberChangeEvent(
                TerritoryInfo.of(t, nameFor(t.getOwner())), player, add, blacklist));
        return true;
    }

    /**
     * 圈入一个区块：不做经济与数量限制检查（调用方负责），区块已被任何人占领时返回 null。
     * 相邻自动合并照常生效；成功后触发 TerritoryClaimEvent。
     */
    public Territory addChunk(UUID owner, String world, int chunkX, int chunkZ) {
        if (owner == null || world == null) return null;
        if (getAt(world, chunkX, chunkZ) != null) return null;
        Set<UUID> before = new HashSet<>();
        for (Territory t : territories.values()) {
            if (t.getOwner().equals(owner) && t.getWorld().equals(world)) before.add(t.getId());
        }
        ownerChunks(owner, world).add(pack(chunkX, chunkZ));
        recompute(world, owner);
        markDirty();
        Territory result = getAt(world, chunkX, chunkZ);
        if (result != null) {
            Bukkit.getPluginManager().callEvent(new TerritoryClaimEvent(owner,
                    TerritoryInfo.of(result, nameFor(owner)), world, chunkX, chunkZ,
                    !before.contains(result.getId())));
        }
        return result;
    }

    /** 领地对象被移除时触发删除事件（快照为移除前的状态） */
    private void fireDeleteEvent(Territory t) {
        Bukkit.getPluginManager().callEvent(new TerritoryDeleteEvent(
                TerritoryInfo.of(t, nameFor(t.getOwner()))));
    }

    /* ========================= 超级管理员 / 权限节点 ========================= */

    /** 权限节点：管理命令（等同 config 超管名单，默认关闭） */
    public static final String PERM_ADMIN = "territory.admin";
    /** 权限节点：无视领地保护（显式授予才生效，慎用） */
    public static final String PERM_BYPASS = "territory.bypass";

    public boolean isSuperAdmin(UUID uuid) {
        return plugin.getConfig().getStringList("superadmins").contains(uuid.toString());
    }

    /** 是否拥有管理权限：config 超管名单，或持有 territory.admin 权限节点（OP 本身无效） */
    public boolean isAdmin(Player player) {
        return player != null && (isSuperAdmin(player.getUniqueId())
                || player.hasPermission(PERM_ADMIN));
    }

    /**
     * 保护检查中的越权：
     * - territory.bypass 权限节点：显式授予即生效（不受 config 开关影响，慎用）；
     * - config 超管名单：仅在开启 superadmin-bypass-protection 时生效。
     * OP 本身【不】越权。
     */
    public boolean bypassProtection(Player player) {
        if (player == null) return false;
        if (player.hasPermission(PERM_BYPASS)) return true;
        return plugin.getConfig().getBoolean("superadmin-bypass-protection", true)
                && isSuperAdmin(player.getUniqueId());
    }

    /* ========================= 名字缓存 ========================= */

    public String nameFor(UUID uuid) {
        String name = nameCache.get(uuid);
        if (name != null) return name;
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            nameCache.put(uuid, online.getName());
            return online.getName();
        }
        OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
        name = off.getName();
        if (name != null) {
            nameCache.put(uuid, name);
            return name;
        }
        return "未知";
    }

    /** 通过名字解析 UUID：在线玩家 -> 名字缓存 ->（弃用API兜底） */
    @SuppressWarnings("deprecation")
    public UUID resolveUUID(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        for (Map.Entry<UUID, String> e : nameCache.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        }
        OfflinePlayer off = Bukkit.getOfflinePlayer(name);
        return off.getName() != null || off.hasPlayedBefore() ? off.getUniqueId() : null;
    }

    public void cacheName(UUID uuid, String name) {
        String old = nameCache.put(uuid, name);
        if (!name.equals(old)) markDirty();
    }

    /** 已知玩家 UUID 快照（含离线；用于 GUI 头像选择） */
    public Set<UUID> knownPlayerIds() {
        return new HashSet<>(nameCache.keySet());
    }

    /* ========================= 持久化 ========================= */

    public boolean isDirty() {
        return dirty;
    }

    public void markDirty() {
        dirty = true;
    }

    /** 序列化当前数据（在主线程调用，避免并发修改） */
    private String serialize() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Territory t : territories.values()) {
            String base = "territories." + t.getId();
            yaml.set(base + ".owner", t.getOwner().toString());
            yaml.set(base + ".owner-name", nameFor(t.getOwner()));
            yaml.set(base + ".world", t.getWorld());
            List<String> chunkList = new ArrayList<>();
            for (Long c : t.getChunks()) {
                chunkList.add(unpackX(c) + ":" + unpackZ(c));
            }
            yaml.set(base + ".chunks", chunkList);
            List<String> wl = new ArrayList<>();
            for (UUID u : t.getWhitelist()) wl.add(u.toString());
            yaml.set(base + ".whitelist", wl);
            List<String> bl = new ArrayList<>();
            for (UUID u : t.getBlacklist()) bl.add(u.toString());
            yaml.set(base + ".blacklist", bl);
            List<String> pur = new ArrayList<>();
            for (Module m : t.getPurchased()) pur.add(m.name());
            yaml.set(base + ".purchased", pur);
            List<String> en = new ArrayList<>();
            for (Module m : t.getEnabled()) en.add(m.name());
            yaml.set(base + ".enabled", en);
            List<Map<String, Object>> links = new ArrayList<>();
            for (InteractionLink link : t.getLinks()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", link.getId().toString());
                row.put("source", link.getSource().serialize());
                row.put("target", link.getTarget().serialize());
                row.put("mode", link.getMode().name());
                row.put("auto-close", link.getAutoCloseSeconds());
                row.put("enabled", link.isEnabled());
                row.put("created", link.getCreatedAt());
                links.add(row);
            }
            yaml.set(base + ".links", links);
            if (t.hasTp()) {
                Location tp = t.getTpPoint();
                yaml.set(base + ".tp", tp.getX() + "," + tp.getY() + "," + tp.getZ()
                        + "," + tp.getYaw() + "," + tp.getPitch());
            }
            yaml.set(base + ".created", t.getCreated());
        }
        for (Map.Entry<UUID, String> e : nameCache.entrySet()) {
            yaml.set("names." + e.getKey(), e.getValue());
        }
        return yaml.saveToString();
    }

    private void writeToFile(String data) {
        synchronized (ioLock) {
            try {
                Path target = dataFile.toPath();
                Path tmp = dataFile.toPath().resolveSibling("territories.yml.tmp");
                Files.write(tmp, data.getBytes(StandardCharsets.UTF_8));
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                plugin.getLogger().severe("保存领地数据失败: " + ex.getMessage());
            }
        }
    }

    /** 异步延迟保存：主线程做序列化快照，异步写盘 */
    public void saveAsync() {
        dirty = false;
        String data = serialize();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> writeToFile(data));
    }

    /** 关服时的同步保存 */
    public void saveSync() {
        dirty = false;
        writeToFile(serialize());
    }

    public void load() {
        territories.clear();
        chunkIndex.clear();
        ownerChunks.clear();
        nameCache.clear();
        linkIndex.clear();
        linksById.clear();
        if (!dataFile.exists()) return;

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection names = yaml.getConfigurationSection("names");
        if (names != null) {
            for (String key : names.getKeys(false)) {
                try {
                    nameCache.put(UUID.fromString(key), names.getString(key));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        ConfigurationSection secs = yaml.getConfigurationSection("territories");
        if (secs == null) return;
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
                        t.getChunks().add(pack(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
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
            for (Map<?, ?> raw : secs.getMapList(key + ".links")) {
                BlockKey source = BlockKey.parse(asString(raw.get("source")));
                BlockKey target = BlockKey.parse(asString(raw.get("target")));
                if (source == null || target == null) continue;
                if (!world.equals(source.getWorld()) || !world.equals(target.getWorld())) continue;
                if (!t.getChunks().contains(pack(target.getChunkX(), target.getChunkZ()))) continue;
                UUID linkId;
                try {
                    String rawId = asString(raw.get("id"));
                    linkId = rawId != null ? UUID.fromString(rawId) : UUID.randomUUID();
                } catch (IllegalArgumentException ex) {
                    linkId = UUID.randomUUID();
                }
                InteractionLink.Mode mode = InteractionLink.Mode.byName(asString(raw.get("mode")));
                int autoClose = asInt(raw.get("auto-close"), 0);
                boolean enabled = asBoolean(raw.get("enabled"), true);
                long created = asLong(raw.get("created"), System.currentTimeMillis());
                t.getLinks().add(new InteractionLink(linkId, id, source, target,
                        mode, autoClose, enabled, created));
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
            territories.put(id, t);
            Map<Long, UUID> idx = chunkIndex.computeIfAbsent(world, k -> new HashMap<>());
            for (Long c : t.getChunks()) idx.put(c, id);
            ownerChunks.computeIfAbsent(t.getOwner(), k -> new HashMap<>())
                    .computeIfAbsent(world, k -> new HashSet<>())
                    .addAll(t.getChunks());
        }
        rebuildLinkIndex();
    }

    private static String asString(Object value) {
        return value != null ? value.toString() : null;
    }

    private static int asInt(Object value, int def) {
        if (value instanceof Number n) return n.intValue();
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    private static long asLong(Object value, long def) {
        if (value instanceof Number n) return n.longValue();
        if (value != null) {
            try {
                return Long.parseLong(value.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    private static boolean asBoolean(Object value, boolean def) {
        if (value instanceof Boolean b) return b;
        if (value != null) {
            String s = value.toString();
            if (s.equalsIgnoreCase("true")) return true;
            if (s.equalsIgnoreCase("false")) return false;
        }
        return def;
    }

    public Set<String> enabledWorlds() {
        return Collections.unmodifiableSet(enabledWorlds);
    }
}
