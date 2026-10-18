# Enderman Carry Control · 末影人搬运控制

适用于 **Minecraft Java 26.2 + Fabric** 的纯服务端 Mod。分别控制末影人能拿起哪些方块、能放下哪些方块，支持箱子式 GUI 和指令即时修改并持久保存。玩家客户端无需安装。

## 安装

1. 服务端使用 Java 25 或更新版本、Minecraft **26.2**、Fabric Loader **0.19.5+**。
2. 将 Release 中的 `enderman-carry-control-1.1.0+mc26.2.jar` 放入服务端 `mods/`，升级时移除旧版本 JAR。
3. 将 **26.2 对应的 Fabric API 0.161.0+** 也放入 `mods/`。
4. 重启服务端。首次启动生成 `config/enderman-carry-control.json`，初始行为与原版一致。

下载：[Mod Release](https://github.com/AconHol/enderman-carry-control/releases/latest) · [Fabric](https://fabricmc.net/use/server/) · [Fabric API](https://modrinth.com/mod/fabric-api/versions?g=26.2)

## 箱子式配置界面（1.1.0 新增）

游戏内执行 **`/ecc gui`** 或 `/endermancarry gui`，打开原版六行箱子式界面。需要 OP 2 级权限，客户端无需安装任何 Mod 或资源包。

- 点击“拿起规则”或“放下规则”，分别选择原版、白名单、黑名单、全部允许、全部禁止。当前选项带绿色勾选和闪光。
- 点击“选择方块”或“选择方块标签”，翻页浏览并点击条目加入名单；已选条目再次点击即可移除。
- 点击“编辑当前名单”查看已添加的方块和标签，点击条目移除。
- **点击下方背包中的方块**即可将它加入当前名单，也可点击“添加主手方块”。实际物品不会被移动或消耗。
- 所有修改立即生效并保存。添加名单不会自动切换模式，使用名单时需选择白名单或黑名单。
- “清空当前名单”和“恢复全部原版规则”会打开确认页面，点击绿色确认或红色取消。
- 可在界面中重新加载手动修改的配置文件；加载失败时保留当前规则。
- 浏览页面支持上一页/下一页，右键翻页按钮可跳转 10 页。底部可切换拿起/放下、查看名单、切换方块/标签、返回主页或关闭。

快速打开指定配置，或按方块 ID 关键字筛选：

```mcfunction
/ecc gui pickup
/ecc gui place
/ecc gui pickup stone
/ecc gui pickup minecraft:oak
```

搜索不区分大小写，匹配 ID 的命名空间及路径；浏览页面的望远镜按钮可清除筛选。多个管理员同时编辑，或通过指令修改配置时，已打开的界面会自动刷新；收到旧页面的点击时会先刷新页面，避免误操作。权限被撤销后，已打开的界面也不能继续修改。

界面内的物品都是按钮：普通点击操作配置，Shift、数字键、拖拽、双击、丢弃和创造模式复制不会转移按钮或背包物品。GUI 指令只能由游戏内玩家执行；控制台和 RCON 继续使用下文的文本指令。

## 指令

主指令 `/endermancarry`，简写 `/ecc`。需要 **OP 2 级**；除 GUI 外，控制台和 RCON 可使用。控制台中省略开头的 `/`。所有修改立即生效、自动写入配置，重启后保留。

| 指令 | 用途 |
| --- | --- |
| `/ecc` 或 `/ecc status` | 查看拿起、放下规则及名单 |
| `/ecc help` | 查看帮助 |
| `/ecc gui [pickup或place] [关键词]` | 打开箱子式配置界面，可指定规则和筛选方块 |
| `/ecc pickup mode <模式>` | 设置拿起模式 |
| `/ecc place mode <模式>` | 设置放下模式 |
| `/ecc <pickup或place> add <方块ID或#标签>` | 添加名单条目 |
| `/ecc <pickup或place> remove <方块ID或#标签>` | 移除名单条目 |
| `/ecc <pickup或place> clear` | 清空该名单，保留当前模式 |
| `/ecc <pickup或place> list` | 查看规则及名单 |
| `/ecc check <方块ID>` | 检查指定方块是否通过拿起、放下规则 |
| `/ecc reload` | 重载手动修改的 JSON 配置 |
| `/ecc reset` | 清空两个名单并恢复原版模式 |

方块 ID 可简写为 `stone`，会自动保存成 `minecraft:stone`。支持 `modid:block` 和 `#namespace:path/to/tag`。标签取当前数据包的实际内容，数据包重载后自动按新内容匹配；空标签或尚未定义的标签不会匹配任何方块。指令提供 Tab 补全及中文反馈。

### 模式

| 模式 | 拿起 `pickup` | 放下 `place` |
| --- | --- | --- |
| `vanilla`（默认） | 原版 `minecraft:enderman_holdable` 标签 | 原版规则允许的放下行为 |
| `whitelist` | 仅名单内方块，可扩展原版可搬运范围 | 仅允许放下名单内的已持有方块 |
| `blacklist` | 从原版可拿起范围中排除名单 | 禁止放下名单内的已持有方块 |
| `all` | 所有方块类型，受下文数据保护限制 | 所有已持有方块类型 |
| `none` | 禁止拿起 | 禁止放下，保持手中原有方块 |

名单仅用于 `whitelist` 和 `blacklist` 模式。添加、删除名单不会自动改变模式。**`blacklist` 不会扩大原版可拿起范围**；要让末影人拿起石头等额外方块，使用 `whitelist` 或 `all`。

### 示例

禁止末影人拿起任何方块，其他行为保持原版：

```mcfunction
/ecc pickup mode none
```

禁止拿起草方块，其余原版可搬运方块保持原样：

```mcfunction
/ecc pickup add minecraft:grass_block
/ecc pickup mode blacklist
```

仅允许拿起泥土和石头，并且仅能放下泥土：

```mcfunction
/ecc pickup clear
/ecc pickup add minecraft:dirt
/ecc pickup add minecraft:stone
/ecc pickup mode whitelist
/ecc place clear
/ecc place add minecraft:dirt
/ecc place mode whitelist
```

允许搬运所有原版可搬运方块，再加上原木：

```mcfunction
/ecc pickup clear
/ecc pickup add #minecraft:enderman_holdable
/ecc pickup add #minecraft:logs
/ecc pickup mode whitelist
```

恢复默认：

```mcfunction
/ecc reset
```

## 配置文件

`config/enderman-carry-control.json`：

```json
{
  "schemaVersion": 1,
  "pickup": {
    "mode": "vanilla",
    "blocks": []
  },
  "place": {
    "mode": "vanilla",
    "blocks": []
  }
}
```

JSON 中的 ID 须包含命名空间；标签以 `#` 开头。修改文件后执行 `/ecc reload`。模式、结构、ID 或方块不存在时会报错，当前已生效的规则保持不变，文件也不会被覆盖。启动时遇到无效配置会明确报错并拒绝加载，避免静默恢复原版而让末影人意外搬走方块。指令保存先写临时文件再替换，保存失败时不应用修改。

## 行为边界

- 只干预末影人“拿起时的方块类型判定”和“放下时的类型许可判定”。不替换末影人的整个 AI。
- 不调整瞬移、仇恨、攻击、凝视、寻路、受水伤害、生成、掉落或原版搬运概率及距离。
- 保留原版对游戏规则、视线、目标位置、支撑面、碰撞和方块可存活性的检查；名单许可不会强制搬运或强行放置。
- `check` 只报告类型许可，不承诺指定时刻一定搬运。
- 放下规则判断的是**手中拿着的方块**，不是目标位置下方的方块。
- 禁止放下时，末影人继续持有原方块；不删除、不自动转换或生成掉落物。
- 原版末影人只保存 `BlockState`，不保存方块实体数据。扩展可拿起范围时会排除空气和带方块实体的方块，例如箱子、熔炉、潜影盒，以防库存等数据丢失。默认 `vanilla` 模式完整遵循原版及数据包标签。
- 这里的“物品”指世界中的方块，不新增捡起地上掉落物的行为。
- 配置对该服务端所有维度和所有末影人生效。该版本为独立服务端 Mod，不在客户端加载。

## 构建与测试

需要 JDK 25。项目固定 Minecraft 26.2、Fabric Loader 0.19.5、Fabric API 0.161.0+26.2、Fabric Loom 1.18.4 和 Gradle 9.7.1。

```powershell
.\gradlew.bat build --console=plain
```

```sh
./gradlew build --console=plain
```

`build` 会运行配置/规则单元测试和真实服务器 GameTest 集成测试。测试专用代码不会打入发行 JAR。产物位于 `build/libs/`，安装不带 `-sources` 的 JAR。测试服务器使用临时世界；测试配置不用于生产服。

构建优先使用阿里云镜像获取通用 Maven 依赖；Fabric 和 Minecraft 文件使用官方源。Gradle Wrapper 使用官方发行地址，开发机也可复用已安装的 Gradle 9.7.1。

GitHub Actions 会在推送和 PR 时运行同样的构建与测试。

## 许可证

MIT，见 [LICENSE](LICENSE)。Minecraft、Fabric 及其依赖不随此 Mod 重新分发。本项目非 Mojang/Microsoft 官方项目。
