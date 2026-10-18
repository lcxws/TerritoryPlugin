# TerritoryPlugin API 使用指南

其他插件可以通过 **Bukkit ServicesManager**（或静态方法）读写领地数据，无需任何编译期依赖注入。

---

## 1. 接入配置

### plugin.yml（软依赖）

```yaml
softdepend:
  - Territory
```

### 编译依赖

把 TerritoryPlugin 的 release jar 加入你的工程 classpath（本插件未发布到 Maven 仓库）：

- Gradle：`compileOnly files('libs/TerritoryPlugin-1.7.0-beta.jar')`
- Maven：将 jar 用 `mvn install:install-file` 装入本地仓库后以 `provided` 引用

---

## 2. 获取 API 实例

```java
import com.lcxws.territory.api.TerritoryAPI;
import com.lcxws.territory.api.TerritoryInfo;
import org.bukkit.Bukkit;

public class MyPlugin extends JavaPlugin {
    private TerritoryAPI territory;

    @Override
    public void onEnable() {
        // 方式一：ServicesManager（推荐）
        territory = Bukkit.getServicesManager().load(TerritoryAPI.class);
        // 方式二：静态访问 —— TerritoryAPIProvider.get();

        if (territory == null) {
            getLogger().warning("TerritoryPlugin 未安装，领地相关功能不可用");
        }
    }
}
```

> 插件比 Territory 晚启用时也可以监听 `PluginEnableEvent` 后重新获取（沿用 EconomyPlugin 的写法）。

---

## 3. 常用示例

### 查询与判断

```java
import com.lcxws.territory.Module;

// 玩家脚下的领地
TerritoryInfo t = territory.getTerritoryAt(player.getLocation());
if (t != null && !t.isTrusted(player.getUniqueId())) {
    player.sendMessage("这里是 " + t.getOwnerName() + " 的领地！");
}

// 按区块查询
TerritoryInfo byChunk = territory.getTerritoryAt(world, chunkX, chunkZ);

// 玩家名下所有领地与区块总数
List<TerritoryInfo> list = territory.getTerritoriesOf(player.getUniqueId());
int chunks = territory.getChunkCount(player.getUniqueId());

// 模块状态（如：该领地是否开了禁用PvP）
boolean noPvp = territory.isModuleEnabled(t.getId(), Module.PVP);
```

### 查询交互链接

```java
import com.lcxws.territory.api.TerritoryLinkInfo;

List<TerritoryLinkInfo> links = territory.getLinks(t.getId());
for (TerritoryLinkInfo link : links) {
    boolean isPublic = link.isPublic();
    int autoClose = link.getAutoCloseSeconds();
    Location source = link.getSourceLocation(); // 世界未加载时可能为 null
    Location target = link.getTargetLocation();
}
```

### 遍历领地内的区块

```java
for (long packed : t.getChunks()) {
    int cx = TerritoryInfo.unpackChunkX(packed);
    int cz = TerritoryInfo.unpackChunkZ(packed);
    // ...
}
```

### 名单管理（与命令规则一致：黑名单优先）

```java
territory.addTrusted(t.getId(), player.getUniqueId());      // 加入白名单
territory.removeTrusted(t.getId(), player.getUniqueId());   // 移出白名单
territory.addBlacklisted(t.getId(), player.getUniqueId());  // 拉黑（会自动移出白名单）
territory.removeBlacklisted(t.getId(), player.getUniqueId());// 解除拉黑
// 返回值 = 是否实际发生变更；已存在/不存在等无效操作返回 false
```

### 管理操作（活动奖励、迁移工具等）

```java
// 为玩家免费圈一块地（不扣费、不检查数量上限；相邻自动合并）
TerritoryInfo claimed = territory.claimChunk(player.getUniqueId(), world, chunkX, chunkZ);
if (claimed == null) {
    // 区块已被占领 / 世界未启用领地
}

// 删除整个领地（无退款，会触发 TerritoryDeleteEvent）
territory.deleteTerritory(t.getId());
```

### 监听事件

```java
import com.lcxws.territory.api.event.*;

@EventHandler
public void onClaim(TerritoryClaimEvent e) {
    if (e.isNewTerritory()) {
        Bukkit.broadcastMessage(e.getTerritory().getOwnerName() + " 建立了新领地！");
    }
}

@EventHandler
public void onMember(TerritoryMemberChangeEvent e) {
    // e.isAdded()? 白名单/黑名单变更均可统一处理
}

@EventHandler
public void onUnclaim(TerritoryUnclaimEvent e) { /* 区块被放弃/被管理移除 */ }

@EventHandler
public void onDelete(TerritoryDeleteEvent e) { /* 领地被整体删除 */ }

@EventHandler
public void onLinkUse(TerritoryLinkUseEvent e) {
    // 交互链接执行开关门之前触发；可取消，阻止本次操作
    Player player = e.getPlayer();
    TerritoryLinkInfo link = e.getLink();
    if (/* 自定义条件 */ false) {
        e.setCancelled(true);
    }
}
```

事件在变更**生效后**触发（不可取消）；命令、GUI、API 三种入口均会触发，不会漏。
`TerritoryLinkUseEvent` 是例外：它在开关门**之前**触发，**可取消**。

---

## 4. 接口一览

### 查询

| 方法 | 说明 |
|------|------|
| `getTerritoryAt(Location)` | 位置所在领地，无则 null |
| `getTerritoryAt(World, int, int)` | 区块所属领地 |
| `getTerritory(UUID)` | 按领地 ID 查询 |
| `getTerritoriesOf(UUID)` | 玩家名下全部领地（按创建时间排序） |
| `getChunkCount(UUID)` | 玩家区块总数（跨世界） |
| `getTerritoryCount()` | 全服领地总数 |
| `getLinks(UUID)` | 某领地的交互链接列表（`TerritoryLinkInfo` 快照） |

### 判断 / 名单 / 管理

| 方法 | 说明 |
|------|------|
| `isOwner / isTrusted / isBlacklisted(id, player)` | 身份判断（黑名单优先） |
| `isModuleEnabled(id, Module)` | 模块是否开启 |
| `addTrusted / removeTrusted / addBlacklisted / removeBlacklisted` | 名单增删 |
| `claimChunk(owner, world, cx, cz)` | 管理员圈地（不扣费） |
| `deleteTerritory(id)` | 删除领地 |

### 事件

`TerritoryClaimEvent` / `TerritoryUnclaimEvent` / `TerritoryMemberChangeEvent` / `TerritoryDeleteEvent` /
`TerritoryLinkUseEvent`（可取消；包 `com.lcxws.territory.api.event`）

---

## 5. 注意事项

1. **主线程**：所有 API 方法与事件回调都应在主线程执行；
2. **快照语义**：`TerritoryInfo` 是不可变快照，改动它不会影响插件数据；变更请用 API 方法；
3. **自动落库**：通过 API 产生的变更与命令一样会被插件标记并异步写入存储（YAML / SQLite / MySQL）；
4. **空值防御**：Territory 未安装或未启用时 `load(...)` 返回 null，请始终判断；
5. 领地命令与别名：`/res`、`/land`。
