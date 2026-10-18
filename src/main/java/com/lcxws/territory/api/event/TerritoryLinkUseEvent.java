package com.lcxws.territory.api.event;

import com.lcxws.territory.api.TerritoryInfo;
import com.lcxws.territory.api.TerritoryLinkInfo;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * 玩家通过交互链接触发目标方块之前触发。
 * 取消事件可以阻止本次开关门/活板门/栅栏门。
 */
public class TerritoryLinkUseEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final TerritoryInfo territory;
    private final TerritoryLinkInfo link;
    private final Location source;
    private final Location target;
    private boolean cancelled;

    public TerritoryLinkUseEvent(Player player, TerritoryInfo territory, TerritoryLinkInfo link,
                                 Location source, Location target) {
        this.player = player;
        this.territory = territory;
        this.link = link;
        this.source = source != null ? source.clone() : null;
        this.target = target != null ? target.clone() : null;
    }

    public Player getPlayer() {
        return player;
    }

    public TerritoryInfo getTerritory() {
        return territory;
    }

    public TerritoryLinkInfo getLink() {
        return link;
    }

    /** 被右键的源方块位置（副本） */
    public Location getSource() {
        return source != null ? source.clone() : null;
    }

    /** 被操作的目标方块位置（副本） */
    public Location getTarget() {
        return target != null ? target.clone() : null;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
