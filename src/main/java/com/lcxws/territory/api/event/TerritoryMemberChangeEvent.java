package com.lcxws.territory.api.event;

import com.lcxws.territory.api.TerritoryInfo;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * 领地白名单 / 黑名单发生变化后触发（命令、GUI、API 三种入口都会触发；不可取消）。
 */
public class TerritoryMemberChangeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final TerritoryInfo territory;
    private final UUID player;
    private final boolean added;
    private final boolean blacklist;

    public TerritoryMemberChangeEvent(TerritoryInfo territory, UUID player, boolean added, boolean blacklist) {
        this.territory = territory;
        this.player = player;
        this.added = added;
        this.blacklist = blacklist;
    }

    /** 变更后的领地快照 */
    public TerritoryInfo getTerritory() {
        return territory;
    }

    /** 被操作的玩家 */
    public UUID getPlayer() {
        return player;
    }

    /** true = 加入名单；false = 移出名单 */
    public boolean isAdded() {
        return added;
    }

    /** true = 黑名单；false = 白名单 */
    public boolean isBlacklist() {
        return blacklist;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
