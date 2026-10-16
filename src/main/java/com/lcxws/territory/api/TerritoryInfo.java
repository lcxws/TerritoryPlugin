package com.lcxws.territory.api;

import com.lcxws.territory.Module;
import com.lcxws.territory.Territory;
import org.bukkit.Location;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 领地的不可变快照：对外暴露的数据载体，任何修改都不会影响插件内部数据。
 * 由 API 查询方法返回，也可从事件中获取。
 */
public final class TerritoryInfo {

    private final UUID id;
    private final UUID owner;
    private final String ownerName;
    private final String world;
    private final long created;
    private final Set<Long> chunks;
    private final Set<UUID> whitelist;
    private final Set<UUID> blacklist;
    private final Set<Module> purchased;
    private final Set<Module> enabled;
    private final Location tp;

    private TerritoryInfo(UUID id, UUID owner, String ownerName, String world, long created,
                          Set<Long> chunks, Set<UUID> whitelist, Set<UUID> blacklist,
                          Set<Module> purchased, Set<Module> enabled, Location tp) {
        this.id = id;
        this.owner = owner;
        this.ownerName = ownerName;
        this.world = world;
        this.created = created;
        this.chunks = chunks;
        this.whitelist = whitelist;
        this.blacklist = blacklist;
        this.purchased = purchased;
        this.enabled = enabled;
        this.tp = tp;
    }

    /**
     * @internal 供插件内部构造快照；外部插件请勿调用。
     */
    public static TerritoryInfo of(Territory t, String ownerName) {
        return new TerritoryInfo(
                t.getId(),
                t.getOwner(),
                ownerName,
                t.getWorld(),
                t.getCreated(),
                Collections.unmodifiableSet(new HashSet<>(t.getChunks())),
                Collections.unmodifiableSet(new HashSet<>(t.getWhitelist())),
                Collections.unmodifiableSet(new HashSet<>(t.getBlacklist())),
                Collections.unmodifiableSet(EnumSet.copyOf(t.getPurchased())),
                Collections.unmodifiableSet(EnumSet.copyOf(t.getEnabled())),
                t.hasTp() ? t.getTpPoint().clone() : null);
    }

    /* ========================= 基础信息 ========================= */

    public UUID getId() {
        return id;
    }

    public UUID getOwner() {
        return owner;
    }

    /** 领主名（保存时的快照；玩家从未登录过可能为"未知"） */
    public String getOwnerName() {
        return ownerName;
    }

    /** 所属世界名（一个领地只属于一个世界） */
    public String getWorld() {
        return world;
    }

    /** 创建时间戳（毫秒） */
    public long getCreated() {
        return created;
    }

    public int getChunkCount() {
        return chunks.size();
    }

    /** 是否包含某个区块 */
    public boolean contains(int chunkX, int chunkZ) {
        return chunks.contains(pack(chunkX, chunkZ));
    }

    /**
     * 全部区块（打包坐标：高 32 位为 x，低 32 位为 z）。
     * 可用 {@link #unpackChunkX(long)} / {@link #unpackChunkZ(long)} 解包。
     */
    public Set<Long> getChunks() {
        return chunks;
    }

    /* ========================= 模块 ========================= */

    /** 已购买的模块 */
    public Set<Module> getPurchasedModules() {
        return purchased;
    }

    /** 已开启（且已购买）的模块 */
    public Set<Module> getEnabledModules() {
        return enabled;
    }

    /** 某模块是否处于开启状态（未购买视为 false） */
    public boolean isModuleEnabled(Module module) {
        return enabled.contains(module);
    }

    /* ========================= 名单 ========================= */

    /** 白名单成员（UUID） */
    public Set<UUID> getWhitelist() {
        return whitelist;
    }

    /** 黑名单成员（UUID） */
    public Set<UUID> getBlacklist() {
        return blacklist;
    }

    /** 是否为领主 */
    public boolean isOwner(UUID player) {
        return owner.equals(player);
    }

    /** 是否被信任（领主或白名单；黑名单优先，被拉黑者即使仍在白名单也不算） */
    public boolean isTrusted(UUID player) {
        return owner.equals(player) || (whitelist.contains(player) && !blacklist.contains(player));
    }

    /** 是否在黑名单 */
    public boolean isBlacklisted(UUID player) {
        return blacklist.contains(player);
    }

    /* ========================= 传送点 ========================= */

    public boolean hasTeleportPoint() {
        return tp != null;
    }

    /** 领地传送点（副本）；未设置返回 null */
    public Location getTeleportPoint() {
        return tp != null ? tp.clone() : null;
    }

    /* ========================= 打包坐标工具 ========================= */

    public static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public static int unpackChunkX(long packed) {
        return (int) (packed >> 32);
    }

    public static int unpackChunkZ(long packed) {
        return (int) packed;
    }
}
