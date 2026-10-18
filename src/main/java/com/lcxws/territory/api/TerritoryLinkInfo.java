package com.lcxws.territory.api;

import com.lcxws.territory.InteractionLink;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

/**
 * 交互链接的不可变快照，供对外 API 查询使用。
 * 源方块允许位于领地外；目标方块必定位于链接所属领地内。
 */
public final class TerritoryLinkInfo {

    private final UUID id;
    private final UUID territoryId;
    private final String sourceWorld;
    private final int sourceX;
    private final int sourceY;
    private final int sourceZ;
    private final String targetWorld;
    private final int targetX;
    private final int targetY;
    private final int targetZ;
    private final boolean publicLink;
    private final int autoCloseSeconds;
    private final boolean enabled;
    private final long createdAt;

    private TerritoryLinkInfo(UUID id, UUID territoryId,
                              String sourceWorld, int sourceX, int sourceY, int sourceZ,
                              String targetWorld, int targetX, int targetY, int targetZ,
                              boolean publicLink, int autoCloseSeconds, boolean enabled, long createdAt) {
        this.id = id;
        this.territoryId = territoryId;
        this.sourceWorld = sourceWorld;
        this.sourceX = sourceX;
        this.sourceY = sourceY;
        this.sourceZ = sourceZ;
        this.targetWorld = targetWorld;
        this.targetX = targetX;
        this.targetY = targetY;
        this.targetZ = targetZ;
        this.publicLink = publicLink;
        this.autoCloseSeconds = autoCloseSeconds;
        this.enabled = enabled;
        this.createdAt = createdAt;
    }

    /**
     * @internal 供插件内部构造快照；外部插件请勿调用。
     */
    public static TerritoryLinkInfo of(InteractionLink link) {
        return new TerritoryLinkInfo(
                link.getId(),
                link.getTerritoryId(),
                link.getSource().getWorld(),
                link.getSource().getX(),
                link.getSource().getY(),
                link.getSource().getZ(),
                link.getTarget().getWorld(),
                link.getTarget().getX(),
                link.getTarget().getY(),
                link.getTarget().getZ(),
                link.isPublic(),
                link.getAutoCloseSeconds(),
                link.isEnabled(),
                link.getCreatedAt());
    }

    public UUID getId() {
        return id;
    }

    public UUID getTerritoryId() {
        return territoryId;
    }

    public String getSourceWorld() {
        return sourceWorld;
    }

    public int getSourceX() {
        return sourceX;
    }

    public int getSourceY() {
        return sourceY;
    }

    public int getSourceZ() {
        return sourceZ;
    }

    public String getTargetWorld() {
        return targetWorld;
    }

    public int getTargetX() {
        return targetX;
    }

    public int getTargetY() {
        return targetY;
    }

    public int getTargetZ() {
        return targetZ;
    }

    /** 源方块位置；世界未加载时返回 null */
    public Location getSourceLocation() {
        World w = Bukkit.getWorld(sourceWorld);
        return w != null ? new Location(w, sourceX, sourceY, sourceZ) : null;
    }

    /** 目标方块位置；世界未加载时返回 null */
    public Location getTargetLocation() {
        World w = Bukkit.getWorld(targetWorld);
        return w != null ? new Location(w, targetX, targetY, targetZ) : null;
    }

    /** true = 公开链接（任何未被拉黑的玩家都能用）；false = 仅领主/白名单 */
    public boolean isPublic() {
        return publicLink;
    }

    /** 按钮触发后自动关门秒数；0 = 不自动关 */
    public int getAutoCloseSeconds() {
        return autoCloseSeconds;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long getCreatedAt() {
        return createdAt;
    }
}
