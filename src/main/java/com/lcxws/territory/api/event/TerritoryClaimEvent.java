package com.lcxws.territory.api.event;

import com.lcxws.territory.api.TerritoryInfo;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * 区块被圈入领地后触发（含玩家购买与 API 圈地；不可取消，此时变更已生效）。
 */
public class TerritoryClaimEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID owner;
    private final TerritoryInfo territory;
    private final String world;
    private final int chunkX;
    private final int chunkZ;
    private final boolean newTerritory;

    public TerritoryClaimEvent(UUID owner, TerritoryInfo territory,
                               String world, int chunkX, int chunkZ, boolean newTerritory) {
        this.owner = owner;
        this.territory = territory;
        this.world = world;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.newTerritory = newTerritory;
    }

    /** 圈地后区块所属领地的最新快照 */
    public TerritoryInfo getTerritory() {
        return territory;
    }

    public UUID getOwner() {
        return owner;
    }

    public String getWorld() {
        return world;
    }

    public int getChunkX() {
        return chunkX;
    }

    public int getChunkZ() {
        return chunkZ;
    }

    /** true = 新创建了一个领地；false = 并入了领主已有的相邻领地 */
    public boolean isNewTerritory() {
        return newTerritory;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
