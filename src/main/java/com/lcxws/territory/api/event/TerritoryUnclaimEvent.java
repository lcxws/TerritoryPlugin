package com.lcxws.territory.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * 区块被移出领地后触发（玩家放弃 / 管理删除区块；不可取消）。
 * 若该区块是领地的最后一块，随后还会触发 {@link TerritoryDeleteEvent}。
 */
public class TerritoryUnclaimEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID owner;
    private final String world;
    private final int chunkX;
    private final int chunkZ;

    public TerritoryUnclaimEvent(UUID owner, String world, int chunkX, int chunkZ) {
        this.owner = owner;
        this.world = world;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    /** 该区块原领主 */
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

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
