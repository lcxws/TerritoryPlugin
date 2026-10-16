package com.lcxws.territory.api;

import com.lcxws.territory.Module;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.List;
import java.util.UUID;

/**
 * TerritoryPlugin 对外 API：供其他插件查询与操作领地数据。
 *
 * <h2>获取方式</h2>
 * <pre>
 *   // 方式一：Bukkit ServicesManager（推荐）
 *   TerritoryAPI api = Bukkit.getServicesManager().load(TerritoryAPI.class);
 *
 *   // 方式二：静态访问
 *   TerritoryAPI api = TerritoryAPIProvider.get();
 * </pre>
 * 插件未启用时返回 null，请始终做空值判断；接入方 plugin.yml 建议加
 * {@code softdepend: [Territory]}。
 *
 * <h2>注意事项</h2>
 * <ul>
 *   <li>所有方法必须在主线程调用（Bukkit 事件/命令线程）；</li>
 *   <li>返回值 {@link TerritoryInfo} 为<b>不可变快照</b>，改动不会影响插件数据；</li>
 *   <li>名单与领地变更会自动落库并触发对应事件（见 {@code com.lcxws.territory.api.event}）。</li>
 * </ul>
 */
public interface TerritoryAPI {

    /* ========================= 查询 ========================= */

    /** 该位置所在领地；无则返回 null */
    TerritoryInfo getTerritoryAt(Location location);

    /** 该区块所属领地；无则返回 null */
    TerritoryInfo getTerritoryAt(World world, int chunkX, int chunkZ);

    /** 按领地 ID 查询；不存在返回 null */
    TerritoryInfo getTerritory(UUID territoryId);

    /** 某玩家名下的全部领地（按创建时间排序） */
    List<TerritoryInfo> getTerritoriesOf(UUID owner);

    /** 某玩家名下的区块总数（跨世界合计） */
    int getChunkCount(UUID owner);

    /** 全服领地总数 */
    int getTerritoryCount();

    /* ========================= 身份判断 ========================= */

    /** 是否为领主 */
    boolean isOwner(UUID territoryId, UUID player);

    /** 是否被信任（领主或白名单；黑名单优先，被拉黑者即使还在白名单也不算） */
    boolean isTrusted(UUID territoryId, UUID player);

    /** 是否在黑名单 */
    boolean isBlacklisted(UUID territoryId, UUID player);

    /** 某保护模块是否处于开启状态（未购买视为 false） */
    boolean isModuleEnabled(UUID territoryId, Module module);

    /* ========================= 名单管理 ========================= */

    /**
     * 加入白名单。规则与命令一致：已拉黑者会被拒绝（黑名单优先）。
     *
     * @return 是否发生变更（已在白名单/已在黑名单/领地不存在时返回 false）
     */
    boolean addTrusted(UUID territoryId, UUID player);

    /** 移出白名单。@return 是否发生变更 */
    boolean removeTrusted(UUID territoryId, UUID player);

    /** 加入黑名单（若在白名单会自动移出）。@return 是否发生变更 */
    boolean addBlacklisted(UUID territoryId, UUID player);

    /** 移出黑名单。@return 是否发生变更 */
    boolean removeBlacklisted(UUID territoryId, UUID player);

    /* ========================= 管理操作 ========================= */

    /**
     * 以管理员名义为指定玩家圈入一个区块：不做经济扣费与数量上限检查
     * （供活动奖励、迁移工具等管理用途），相邻区块会照常自动合并。
     * 区块已被任何人占领、世界未启用领地或参数非法时返回 null。
     * 成功后触发 {@code TerritoryClaimEvent}。
     */
    TerritoryInfo claimChunk(UUID owner, World world, int chunkX, int chunkZ);

    /**
     * 删除整个领地（无退款，等效于管理命令；已购模块随之失效）。
     * 成功后触发 {@code TerritoryDeleteEvent}。
     *
     * @return 领地不存在时返回 false
     */
    boolean deleteTerritory(UUID territoryId);
}
