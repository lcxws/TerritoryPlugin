# TerritoryPlugin 领地插件

轻量级**区块制领地插件**，面向 **Bukkit 系服务端 1.20 ～ 1.21.x**（Spigot / Paper / Mohist 等）小人数服务器。
**全 GUI 化操作**：输入 `/res` 打开领地菜单，圈地、放弃、保护模块、白/黑名单、传送全部点选完成；
相邻区块自动合并；进阶保护通过 GUI 自行购买开启；
**OP 互不侵犯**——OP 不能动其他人的领地，只有 config 超管名单、显式授予的 `territory.bypass` 权限或控制台能越权。

> **🧪 beta 分支（1.7.0-beta）新增：交互链接**——把领地外的按钮/拉杆绑定到领地内的门，
> 无需拉红石线，支持公开/仅信任两种权限。详见下方[交互链接](#交互链接领地外的按钮控制领地内的门170-beta-新增)。

> **版本兼容**：1.20 ～ 1.21.x 全系列（含 1.20.5+ 的 API 改名适配），已在 1.21.1 API 上通过编译兼容性验证；不支持的：Folia（区域化调度）与 1.19 及更老版本。

> **📌 SQL 存储版（SQLite / MySQL）发布于 [`sql` 分支](https://github.com/lcxws/TerritoryPlugin/tree/sql)**
> （玩法、命令、GUI 与稳定版一致，仅存储方式不同；支持从本分支数据自动迁移）。
> 本 beta 分支的交互链接目前只在 YAML 版实现，SQL 分支待移植。

## 快速开始

1. 把 `TerritoryPlugin-1.7.0-beta.jar` 和经济插件（EconomyPlugin 或任意 Vault 兼容经济插件）一起放进服务端的 `plugins` 文件夹。
2. 首次启动后编辑 `plugins/Territory/config.yml`：
   - 把 `enabled-worlds` 改成你的**主世界名字**（默认 `world`，仅主世界启用领地）；
   - 想让自己越权管理的话，用 `/res admin whoami` 查询自己的 UUID 填进 `superadmins`。
3. 重启或执行 `/res admin reload`。
4. 游戏内输入 `/res` 即可开始使用（所有功能都有 GUI，命令仅作快捷方式保留）。

经济系统按以下优先级**运行时接入**（均为反射调用，无编译依赖）：

1. [EconomyPlugin](https://github.com/lcxws/EconomyPlugin) 的 ServicesManager 服务（`EconomyAPI`，优选）；
2. **Vault**（`net.milkbowl.vault.economy.Economy` 服务）—— 兼容 EssentialsX / CMI 等一切
   实现了 Vault 的经济插件。

两者都没有时保护功能不受影响，仅购买/退款不可用（会给出明确提示）。

## 从源码构建

环境要求：JDK 17。

```bash
./gradlew build
# 产物: build/libs/TerritoryPlugin-1.7.0-beta.jar
```

无需任何额外 jar 依赖（Mohist dev bundle 由 Gradle 自动下载；本分支数据存储为 YAML，无 SQL 驱动）。

## GUI 主菜单（/res）

| 图标 | 功能 | 说明 |
|------|------|------|
| 📗 信息 | 当前区块状态 | 无人认领 / 你的领地 / 他人领地，含模块概览 |
| 🟩 购买此区块 | 圈地 | 显示价格与余额；已被占用时变红 |
| 🧨 放弃此区块 | 退地 | 退还原价 50%（可配置）；仅领主可用 |
| 🛡 保护模块 | 进阶保护 | **11 种保护模块**购买/开关子界面（45格大界面） |
| 📝 白名单管理 | 成员 | **玩家头颅列表**，点头颅移除；➕按钮打开玩家选择界面，**点头像直接加入** |
| 🔴 黑名单管理 | 封禁 | 同上，拉黑/解封 |
| 🌐 设置传送点 | 传送 | 传送点=当前站立位置 |
| ✨ 边界粒子 | 可视化 | 30 秒边界粒子，仅自己可见 |
| 🧭 传送到领地 | 传送 | 领地罗盘列表，点击即传送 |
| 🔗 交互链接 | 远程开关门 | **领地外的按钮/拉杆 → 领地内的门**，公开/仅信任可切换 |

灰色的按钮表示当前状态下不可用（如不在自己领地内），并说明原因。
白/黑名单加人：点击 ➕ 打开玩家选择界面，**直接点击玩家头像即可加入**（在线玩家优先，其次为服务器已知玩家，支持分页）；从未进过服的玩家可用命令 `/res trust|ban <玩家名>` 添加。

## 交互链接：领地外的按钮控制领地内的门（1.7.0-beta 新增）

适合把“公共入口按钮”放在领地外，按下后由插件直接操作领地内的门/活板门/栅栏门，不需要拉红石线。

### 使用方式

1. 站在自己的领地内输入 `/res`，点击 **🔗 交互链接 → ➕ 新建绑定**。
2. 右键领地外的按钮或拉杆（源方块）。
3. 右键自己领地内的门、活板门或栅栏门（目标方块）。
4. 回到列表后：
   - **左键**：启用 / 停用；
   - **右键**：切换 公开 / 仅信任；
   - **Shift+左键**：删除。

选择过程中有 60 秒时限，潜行右键可取消。命令同样可用：

```
/res link add                          进入两步绑定模式
/res link list                         查看当前领地的链接
/res link remove <序号>                 删除
/res link mode <序号> public|trusted    切换权限
/res link enable|disable <序号>         启用/停用
/res link cancel                       取消当前选择
```

### 谁能使用

| 触发者 | 公开链接 | 仅信任链接 |
|--------|----------|------------|
| 领主 | ✅ | ✅ |
| 白名单成员 | ✅ | ✅ |
| 普通访客 | ✅ | ❌ |
| 黑名单成员 | ❌ 始终拒绝 | ❌ |
| 有 `territory.bypass` 的管理员 | ✅ | ✅ |

- 只有**领主**可以创建、删除、切换模式；白名单成员只能使用。
- 源方块必须在**荒野或自己名下的领地**内，不能绑定别人领地里的按钮；目标方块必须在自己的领地内，且与源方块同一世界。
- 公开链接会真正放人进门，但进门后仍然不能破坏、放置、开箱子；黑名单玩家仍然无法进入。
- 如果源方块位于领地内，访客按不到它，公开模式实际上只有信任成员能触发；公开按钮建议放在荒野。

### 开关门行为

- **按钮**：默认开门后 5 秒自动关闭（`default-auto-close-seconds` 可改）。自动关门模式下，每次按下只会开门并重新计时，不会把别人刚开的门关上；配置为 `0` 时改为开/关切换。
- **拉杆**：目标门状态跟随拉杆，拉杆开=门开，拉杆关=门关。
- **双开门**自动联动；铁门、铁活板门也可由链接打开。
- 目标门正被红石供电时会拒绝操作，避免和红石电路打架。
- 不支持箱子/熔炉等容器：远程开箱等于绕过整个领地保护体系，需要单独的权限与日志设计。

配置项（`config.yml`）：

```yaml
link:
  enabled: true
  max-per-territory: 8       # 每个领地最多链接数，-1 = 不限
  default-mode: TRUSTED      # 新建链接默认权限：TRUSTED / PUBLIC
  default-auto-close-seconds: 5
  cooldown-ms: 500
  max-distance: 0            # 源到目标的最大距离，0 = 不限
```

## 玩法与价格（均可在 config 修改）

| 项目 | 默认价格 | 说明 |
|------|---------|------|
| 购买区块 `/res claim` | 2500 | 脚下区块整高圈地，相邻区块自动合并成一个领地 |
| 放弃区块 `/res unclaim` | 退 50% | 拆分出的新领地不继承已购模块（防倒卖漏洞） |
| 进阶保护模块 `/res gui` | 500/个（可按模块单独定价） | 每领地一次性买断，之后随时开关 |

### 进阶保护模块（11 种，GUI 内点击购买/开关）

| 模块 | 效果 |
|------|------|
| 防爆炸 | TNT/苦力怕/水晶/床等无法炸毁领地内方块 |
| 防生物破坏 | 僵尸破门、末影人搬方块、凋灵冲撞等无效 |
| 禁用PvP | 领地内玩家互相伤害无效 |
| 防火焰蔓延 | 火无法在领地内蔓延/点燃 |
| 禁止刷怪 | 敌对生物无法在领地内生成（含刷怪笼/刷怪蛋） |
| 防雷击 | 闪电无法在领地内落地（无起火无伤害） |
| 防动物破坏 | 羊吃草、兔子啃作物等动物破坏无效 |
| 禁访客拾取 | 非白名单成员无法捡起领地内掉落物 |
| 禁访客丢弃 | 非白名单成员无法在领地内丢弃物品 |
| 禁访客传送 | 非白名单成员无法传送进入（步行不受影响） |
| 防树叶腐烂 | 树叶不自然消失（建筑用树叶保形） |

价格可在 `config.yml` 的 `module-prices` 按模块单独设置，未列出的用 `module-price` 兜底。

**基础保护（免费自带）**：非信任玩家在领地内不能破坏/放置方块（含床/门等多格放置跨界）、开箱子、
使用门/按钮/拉杆、与实体交互（物品展示框/村民/盔甲架/动物）、盔甲架取放装备、钓竿钓动物、
打船/矿车、放桶取桶、冰霜行者结冰、取走讲台上的书；漏斗/漏斗矿车不能隔着领地边界吸取领地内箱子；
外来的水/岩浆不能流进领地（向下流放行）；活塞无法跨边界推拉。

访客只保留两个只读交互：末影箱（私人空间）和讲台阅读。

## 设计参考（对照开源插件后加固）

事件防护覆盖面参考了几个开源领地插件的监听清单与行为设计（GriefPrevention / CrashClaim / Residence /
Landlord / Dominion），本仓库全部代码为原创实现，未复制任何第三方源码。

其中 GP 的"液体向下流放行、只拦流入"语义被直接采用；Landlord 缺失活塞防护的已知问题在我们实现中不存在。

## 开源协议

[GNU General Public License v3.0](LICENSE)（GPLv3） © 2026 lcxws

你可以自由使用、修改、再分发本项目；再分发衍生作品时需保留版权声明并以 GPLv3 协议开源。

## 权限三级模型

1. **领主**：全部权限，可管理名单、设传送点、买模块。
2. **白名单** `/res trust`：完全信任，等同领主使用（不能改名单）。
3. **访客**：只能看，什么都碰不了。

**黑名单** `/res ban`：禁止进入，且五个入口全部拦截：步行/跑图（区块边界推回）、**传送**（含末影珍珠、
 chorus 果实）、**骑载具闯入**（船/矿车/马，弹出并送回——这是对照的开源插件都没处理的绕过口）、
**重生**（床/锚点在黑名单领地内改为世界出生点）、**登入**（离线期间被拉黑，登录时被移出）。
拉黑时若在领地内会被立即移出，同时自动移出白名单。**黑名单优先**：即使同时在白名单里也无效。

## OP 互不侵犯

保护检查不看 OP——OP 在别人领地里就是个普通访客。
能越权的只有三种，且全部需要显式开启：

- `config.yml → superadmins` 中的 UUID（**默认为空**，需手动添加；可用 `superadmin-bypass-protection` 开关关闭其保护穿越）；
- `territory.bypass` 权限节点（**默认关闭**，用 LuckPerms 等权限插件显式授予才生效，慎用）；
- 服务器后台控制台（永远可以执行全部管理命令）。

超管命令：`/res admin <whoami|reload|list|info|delete|deletechunk>`，
`delete <玩家名>` 可整服清理某玩家全部领地（无退款）；
管理权限也可用 `territory.admin` 权限节点授予（默认关闭，与名单取并集）。

## 权限节点（LuckPerms 等权限插件）

所有玩家命令**默认开放**（`default: true`，不装权限插件时行为与以前完全一致），
可用 LuckPerms 等权限插件对特定玩家/组**显式拒绝或授予**：

| 权限节点 | 默认 | 作用 |
|----------|------|------|
| `territory.command.claim` | 允许 | /res claim 购买区块 |
| `territory.command.unclaim` | 允许 | /res unclaim 放弃区块 |
| `territory.command.gui` | 允许 | /res 打开领地菜单 |
| `territory.command.info` | 允许 | /res info 与 /res list |
| `territory.command.tp` | 允许 | /res tp 传送 |
| `territory.command.settp` | 允许 | /res settp 设置传送点 |
| `territory.command.trust` | 允许 | /res trust / untrust 管理白名单 |
| `territory.command.ban` | 允许 | /res ban / unban 管理黑名单 |
| `territory.command.view` | 允许 | /res view 边界粒子 |
| `territory.command.link` | 允许 | /res link 管理交互链接（远程开关门） |
| `territory.admin` | **拒绝** | /res admin 管理命令（等同 config 超管名单；OP 也不自动获得） |
| `territory.bypass` | **拒绝** | 无视领地保护（可在他人领地破坏/放置，**慎用**） |

```bash
# 示例（LuckPerms）
/lp group default permission set territory.command.claim false   # 禁止默认组买地（改由管理员发放）
/lp user Steve permission set territory.command.tp false         # 禁止某玩家传送
/lp group admin permission set territory.admin true              # 管理组获得管理命令
```

⚠️ 给某人 `territory.*` 通配符会**连带授予 bypass**（可在他人领地破坏），建议逐个节点授予。

## 命令总览（快捷方式，全部功能均可在 GUI 完成）

```
/res                  打开领地菜单 GUI        /res gui     同上
/res claim            购买脚下区块            /res unclaim 放弃脚下区块
/res info             当前领地信息            /res list    自己的领地列表
/res tp <编号>        传送到领地              /res settp   设置领地传送点
/res trust <玩家>     加入白名单              /res untrust 移出白名单
/res ban <玩家>       加入黑名单              /res unban   移出黑名单
/res view             边界粒子显示30秒
/res link ...         交互链接（远程开关门）
/res admin ...        超级管理命令
```
`/res` 与 `/land` 等效。

## 对外 API（其他插件接入）

其他插件可通过 Bukkit ServicesManager（或 `TerritoryAPIProvider.get()`）读写领地数据：

```java
TerritoryAPI api = Bukkit.getServicesManager().load(TerritoryAPI.class);
if (api != null) {
    TerritoryInfo t = api.getTerritoryAt(player.getLocation());
    if (t != null && !t.isTrusted(player.getUniqueId())) {
        player.sendMessage("这是 " + t.getOwnerName() + " 的领地！");
    }
}
```

- **查询**：`getTerritoryAt` / `getTerritory` / `getTerritoriesOf` / `getChunkCount` / `getTerritoryCount` / `getLinks`
- **判断**：`isOwner` / `isTrusted` / `isBlacklisted` / `isModuleEnabled`
- **名单**：`addTrusted` / `removeTrusted` / `addBlacklisted` / `removeBlacklisted`（黑名单优先规则与命令一致）
- **管理**：`claimChunk`（免费圈地，供活动/迁移工具）/ `deleteTerritory`
- **事件**：`TerritoryClaimEvent` / `TerritoryUnclaimEvent` / `TerritoryMemberChangeEvent` / `TerritoryDeleteEvent` /
  `TerritoryLinkUseEvent`（可取消，交互链接执行前触发）

接入方 `plugin.yml` 加 `softdepend: [Territory]`，编译依赖直接用本插件 jar。
完整文档与示例见 [API_USAGE_EXAMPLE.md](API_USAGE_EXAMPLE.md)。

## 性能设计（人少服/轻量化）

- **O(1) 快速路径**：所有保护检查先查 `世界→区块→领地` 的 HashMap 索引，无领地直接放行；
- **交互链接同样 O(1)**：右键时只查一次 `源方块坐标→链接` 的 HashMap，不扫描领地；
- **移动事件**只在跨区块边界时才做查找，转头/微动零开销；
- **YAML 单文件存储**（`territories.yml`），改动后约 5 秒异步批量写盘（主线程零 IO），关服强制同步保存；
- 无数据库、无网络依赖，插件本体约 50KB。

## 数据文件

`plugins/Territory/territories.yml`：领地、名单、模块、传送点与交互链接全部在此，可手动备份/编辑（改完 `/res admin reload` 前先停服）。
