package com.lcxws.territory.api.event;

import com.lcxws.territory.api.TerritoryInfo;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * 领地整体被删除后触发（玩家放弃最后一块 / 管理删除 / API 删除；不可取消）。
 * 携带的 {@link TerritoryInfo} 为该领地被删除前的状态快照。
 */
public class TerritoryDeleteEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final TerritoryInfo territory;

    public TerritoryDeleteEvent(TerritoryInfo territory) {
        this.territory = territory;
    }

    /** 被删除领地（删除前状态）的快照 */
    public TerritoryInfo getTerritory() {
        return territory;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
