package com.lcxws.territory;

import org.bukkit.Location;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 一个领地 = 同一主人在同一世界内相互连通的一组区块（整高，全 Y 范围）。
 * 权限三级：黑名单（禁入禁交互）> 白名单（完全信任）> 普通访客（只能看）。
 */
public class Territory {

    private final UUID id;
    private final UUID owner;
    private final String world;
    private final Set<Long> chunks = new HashSet<>();
    private final Set<UUID> whitelist = new HashSet<>();
    private final Set<UUID> blacklist = new HashSet<>();
    private final Set<Module> purchased = EnumSet.noneOf(Module.class);
    private final Set<Module> enabled = EnumSet.noneOf(Module.class);
    private Location tpPoint;
    private long created;

    public Territory(UUID id, UUID owner, String world) {
        this.id = id;
        this.owner = owner;
        this.world = world;
        this.created = System.currentTimeMillis();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwner() {
        return owner;
    }

    public String getWorld() {
        return world;
    }

    public Set<Long> getChunks() {
        return chunks;
    }

    public Set<UUID> getWhitelist() {
        return whitelist;
    }

    public Set<UUID> getBlacklist() {
        return blacklist;
    }

    public Set<Module> getPurchased() {
        return purchased;
    }

    public Set<Module> getEnabled() {
        return enabled;
    }

    public Location getTpPoint() {
        return tpPoint;
    }

    public void setTpPoint(Location tpPoint) {
        this.tpPoint = tpPoint != null ? tpPoint.clone() : null;
    }

    public boolean hasTp() {
        return tpPoint != null;
    }

    public long getCreated() {
        return created;
    }

    public void setCreated(long created) {
        this.created = created;
    }

    public boolean isOwner(UUID uuid) {
        return owner.equals(uuid);
    }

    public boolean isBlacklisted(UUID uuid) {
        return blacklist.contains(uuid);
    }

    /** 白名单成员（黑名单优先，被拉黑即使仍在白名单也不算信任） */
    public boolean isTrusted(UUID uuid) {
        return isOwner(uuid) || (whitelist.contains(uuid) && !blacklist.contains(uuid));
    }

    public boolean moduleEnabled(Module m) {
        return purchased.contains(m) && enabled.contains(m);
    }
}
