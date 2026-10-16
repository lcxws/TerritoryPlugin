# TerritoryPlugin 领地插件

轻量级**区块制领地插件**，面向 **Bukkit 系服务端 1.20 ～ 1.21.x**（Spigot / Paper / Mohist 等）小人数服务器。
**全 GUI 化操作**：输入 `/res` 打开领地菜单，圈地、放弃、保护模块、白/黑名单、传送全部点选完成；
相邻区块自动合并；进阶保护通过 GUI 自行购买开启；
**OP 互不侵犯**——OP 不能动其他人的领地，只有 config 里的超级管理员名单和控制台能越权。

> **版本兼容**：1.20 ～ 1.21.x 全系列（含 1.20.5+ 的 API 改名适配），已在 1.21.1 API 上通过编译兼容性验证；不支持的：Folia（区域化调度）与 1.19 及更老版本。

> **📌 SQL 存储版（SQLite / MySQL）已发布于 [`sql` 分支](https://github.com/lcxws/TerritoryPlugin/tree/sql)**
> （玩法、命令、GUI 与本分支完全一致，仅存储方式不同；支持从本分支数据自动迁移）。

## 快速开始

1. 把 `TerritoryPlugin-1.4.1.jar` 和经济插件（EconomyPlugin 或任意 Vault 兼容经济插件）一起放进服务端的 `plugins` 文件夹。
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
# 产物: build/libs/TerritoryPlugin-1.4.1.jar
```

无需任何额外 jar 依赖（Mohist dev bundle 由 Gradle 自动下载；本分支数据存储为 YAML，无 SQL 驱动）。

## GUI 主菜单（/res）

| 图标 | 功能 | 说明 |
|------|------|------|
| 📗 信息 | 当前区块状态 | 无人认领 / 你的领地 / 他人领地，含模块概览 |
| 🟩 购买此区块 | 圈地 | 显示价格与余额；已被占用时变红 |
| 🧨 放弃此区块 | 退地 | 退还原价 50%（可配置）；仅领主可用 |
| 🛡 保护模块 | 进阶保护 | **11 种保护模块**购买/开关子界面（45格大界面） |
| 📝 白名单管理 | 成员 | **玩家头颅列表**，点头颅移除；➕按钮进入聊天栏输入玩家名 |
| 🔴 黑名单管理 | 封禁 | 同上，拉黑/解封 |
| 🌐 设置传送点 | 传送 | 传送点=当前站立位置 |
| ✨ 边界粒子 | 可视化 | 30 秒边界粒子，仅自己可见 |
| 🧭 传送到领地 | 传送 | 领地罗盘列表，点击即传送 |

灰色的按钮表示当前状态下不可用（如不在自己领地内），并说明原因。
白/黑名单加人：点击 ➕ 后在**聊天栏输入玩家名**（输入"取消"退出，30 秒超时），无需背命令。

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

保护检查完全不检查 OP/权限节点——OP 在别人领地里就是个普通访客。
唯一例外：

- `config.yml → superadmins` 中的 UUID（**默认为空**，需手动添加）；
- 服务器后台控制台永远可以执行全部管理命令。

超管命令：`/res admin <whoami|reload|list|info|delete|deletechunk>`，
`delete <玩家名>` 可整服清理某玩家全部领地（无退款）。

## 命令总览（快捷方式，全部功能均可在 GUI 完成）

```
/res                  打开领地菜单 GUI        /res gui     同上
/res claim            购买脚下区块            /res unclaim 放弃脚下区块
/res info             当前领地信息            /res list    自己的领地列表
/res tp <编号>        传送到领地              /res settp   设置领地传送点
/res trust <玩家>     加入白名单              /res untrust 移出白名单
/res ban <玩家>       加入黑名单              /res unban   移出黑名单
/res view             边界粒子显示30秒
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

- **查询**：`getTerritoryAt` / `getTerritory` / `getTerritoriesOf` / `getChunkCount` / `getTerritoryCount`
- **判断**：`isOwner` / `isTrusted` / `isBlacklisted` / `isModuleEnabled`
- **名单**：`addTrusted` / `removeTrusted` / `addBlacklisted` / `removeBlacklisted`（黑名单优先规则与命令一致）
- **管理**：`claimChunk`（免费圈地，供活动/迁移工具）/ `deleteTerritory`
- **事件**：`TerritoryClaimEvent` / `TerritoryUnclaimEvent` / `TerritoryMemberChangeEvent` / `TerritoryDeleteEvent`（命令、GUI、API 三种入口都会触发）

接入方 `plugin.yml` 加 `softdepend: [Territory]`，编译依赖直接用本插件 jar。
完整文档与示例见 [API_USAGE_EXAMPLE.md](API_USAGE_EXAMPLE.md)。

## 性能设计（人少服/轻量化）

- **O(1) 快速路径**：所有保护检查先查 `世界→区块→领地` 的 HashMap 索引，无领地直接放行；
- **移动事件**只在跨区块边界时才做查找，转头/微动零开销；
- **YAML 单文件存储**（`territories.yml`），改动后约 5 秒异步批量写盘（主线程零 IO），关服强制同步保存；
- 无数据库、无网络依赖，插件本体约 50KB。

## 数据文件

`plugins/Territory/territories.yml`：领地、名单、模块、传送点全部在此，可手动备份/编辑（改完 `/res admin reload` 前先停服）。
