package com.lcxws.territory.api;

import com.lcxws.territory.Module;
import com.lcxws.territory.Territory;
import com.lcxws.territory.TerritoryPlugin;
import com.lcxws.territory.manager.TerritoryManager;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * @internal {@link TerritoryAPI} 的默认实现，由插件在启用时注册，外部插件请通过
 * {@link TerritoryAPIProvider} 或 ServicesManager 获取接口，不要直接引用本类。
 */
public final class TerritoryAPIImpl implements TerritoryAPI {

    private final TerritoryPlugin plugin;

    public TerritoryAPIImpl(TerritoryPlugin plugin) {
        this.plugin = plugin;
    }

    private TerritoryManager tm() {
        return plugin.getTerritories();
    }

    private Territory raw(UUID territoryId) {
        return territoryId == null ? null : tm().get(territoryId);
    }

    private TerritoryInfo wrap(Territory t) {
        return t == null ? null : TerritoryInfo.of(t, tm().nameFor(t.getOwner()));
    }

    /* ========================= 查询 ========================= */

    @Override
    public TerritoryInfo getTerritoryAt(Location location) {
        return wrap(tm().getAt(location));
    }

    @Override
    public TerritoryInfo getTerritoryAt(World world, int chunkX, int chunkZ) {
        return world == null ? null : wrap(tm().getAt(world.getName(), chunkX, chunkZ));
    }

    @Override
    public TerritoryInfo getTerritory(UUID territoryId) {
        return wrap(raw(territoryId));
    }

    @Override
    public List<TerritoryInfo> getTerritoriesOf(UUID owner) {
        List<TerritoryInfo> list = new ArrayList<>();
        if (owner == null) return list;
        for (Territory t : tm().territoriesOf(owner)) {
            list.add(wrap(t));
        }
        return list;
    }

    @Override
    public int getChunkCount(UUID owner) {
        return owner == null ? 0 : tm().countChunksOf(owner);
    }

    @Override
    public int getTerritoryCount() {
        return tm().territoryCount();
    }

    /* ========================= 身份判断 ========================= */

    @Override
    public boolean isOwner(UUID territoryId, UUID player) {
        Territory t = raw(territoryId);
        return t != null && player != null && t.isOwner(player);
    }

    @Override
    public boolean isTrusted(UUID territoryId, UUID player) {
        Territory t = raw(territoryId);
        return t != null && player != null && t.isTrusted(player);
    }

    @Override
    public boolean isBlacklisted(UUID territoryId, UUID player) {
        Territory t = raw(territoryId);
        return t != null && player != null && t.isBlacklisted(player);
    }

    @Override
    public boolean isModuleEnabled(UUID territoryId, Module module) {
        Territory t = raw(territoryId);
        return t != null && module != null && t.moduleEnabled(module);
    }

    /* ========================= 名单管理 ========================= */

    @Override
    public boolean addTrusted(UUID territoryId, UUID player) {
        return tm().changeMember(raw(territoryId), player, true, false);
    }

    @Override
    public boolean removeTrusted(UUID territoryId, UUID player) {
        return tm().changeMember(raw(territoryId), player, false, false);
    }

    @Override
    public boolean addBlacklisted(UUID territoryId, UUID player) {
        return tm().changeMember(raw(territoryId), player, true, true);
    }

    @Override
    public boolean removeBlacklisted(UUID territoryId, UUID player) {
        return tm().changeMember(raw(territoryId), player, false, true);
    }

    /* ========================= 管理操作 ========================= */

    @Override
    public TerritoryInfo claimChunk(UUID owner, World world, int chunkX, int chunkZ) {
        if (owner == null || world == null) return null;
        if (!tm().isWorldEnabled(world.getName())) return null;
        return wrap(tm().addChunk(owner, world.getName(), chunkX, chunkZ));
    }

    @Override
    public boolean deleteTerritory(UUID territoryId) {
        Territory t = raw(territoryId);
        if (t == null) return false;
        tm().deleteTerritory(t);
        return true;
    }
}
