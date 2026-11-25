# 配置参考

完整默认值与注释见 [config.yml](../src/main/resources/config.yml)，玩家可见文本见 [messages.yml](../src/main/resources/messages.yml)。部署、迁移和重载见[运维指南](operations.md)。

## 顶层

```yaml
server-id: "survival-1"          # 本服唯一 ID
local:
  display-name: "<gradient:#56CCF2:#2F80ED>生存服</gradient>"
```

## redis

```yaml
redis:
  host: "127.0.0.1"
  port: 6379
  username: ""
  password: ""
  database: 0
  ssl: false
  connection-timeout-ms: 3000
  socket-timeout-ms: 3000
  prefix: "hammer:cross"         # 所有服务器一致
```

## 功能开关

```yaml
sync:
  chat: true
  tab: true
  join: true
  quit: true
  completion: true
  motd: true
  dm: true
```

## presence（在线状态 / 心跳）

```yaml
presence:
  heartbeat-seconds: 10     # 心跳周期（同时会刷新本服所有玩家的 presence 时间戳）
  reconcile-seconds: 5      # 定期从 Redis 校准内存快照
  stale-seconds: 30         # 超过该时间未刷新视为失效（至少为心跳的 3 倍）
```

## chat / join-quit / tab

```yaml
chat:
  format-local: true        # 本服聊天也套用本服模板（关闭则本服只显示 "玩家: 内容"）
  max-length: 512
  override-vanilla: true    # 完全接管公共聊天：取消原生广播，由插件发送本地 + 跨服，避免重复
  log-to-console: true      # 每台服务器控制台记录完整聊天内容：[聊天][来源服] 玩家: 内容

  link:                     # 链接解析（类似 TrChat）
    enabled: true
    color: "#58A6FF"
    underline: true
    open-in-browser: true
    hover: "<gray>点击打开链接</gray>"

  item:                     # 聊天物品展示（TrChat 兼容）
    enabled: true
    keys: ["%i%", "%i", "%item%", "%item", "[i]", "[item]", "【i】", "【item】"]
    format: "<aqua>[<item>]</aqua>"
    hover: true             # 悬浮显示完整物品信息
    cross-server: true      # 物品数据随聊天同步到其它服
    max-serialized-bytes: 8192
    max-items-per-message: 8
    empty-text: "<gray>[空手]</gray>"

join-quit:
  broadcast-local: true     # 本服玩家的加入/退出也按模板在本服广播
  suppress-vanilla: true    # 隐藏 Paper 原生加入/退出消息，避免重复

tab:
  format-local-players: true  # 本服玩家在 Tab 中也套用 tab 模板
  latency: 0                  # 虚拟条目的延迟条数值，0 表示不显示
```

> 默认 `chat.override-vanilla: true`：公共聊天**完全由本插件承担**，Paper 原生聊天广播被取消，
> 不会出现「原生一条 + 插件一条」的重复。若你另有聊天管理插件并希望它处理本地聊天，
> 可关闭此项（此时本插件只负责跨服同步）。

**PlaceholderAPI**

```yaml
papi:
  enabled: true          # 安装 PlaceholderAPI 后，在聊天/加入退出/Tab 模板中解析 %变量%
```

- 可直接在 `formats.*` 模板里写 `%player_title%`、`%vault_prefix%` 等，按**发言玩家**解析。
- 称号或前缀返回的旧式 `§` 颜色、十六进制颜色和 `§r` 可与 MiniMessage 模板一起使用，不会影响称号之后的玩家名样式。
- 未安装 PlaceholderAPI 时不报错，占位符保持原样。
- 跨服时接收方以发言玩家 UUID 解析（依赖对应扩展支持离线玩家/共享数据）。

**链接与物品用法**

```
在聊天里直接发 https://example.com     → 自动变成可点击链接
看我的装备 [item]                       → 显示主手物品（悬浮可看完整属性）
看第 3 格 [item-3]                      → 显示物品栏第 3 格（1 基）
其它等价写法：[item]-3  [item]3  %item%-3  【item-3】
[ i ] 或 【item】                       → 同样生效（不区分大小写）
```

> 槽位为 1 基：1-9 快捷栏，10-36 背包，37-40 护甲（37 靴子…40 头盔），41 副手。

- 物品在**发送方**服务端读取（默认取主手，也可指定槽位），序列化后随聊天事件同步；**接收方**用本地数据重建悬浮信息，因此各服看到的物品信息一致。
- 空手发送占位符时显示 `chat.item.empty-text`。
- 物品数据有大小上限（`max-serialized-bytes`）且会校验；损坏/超限的数据不会影响聊天，占位符按原文显示。
- 链接仅在**本地模板渲染后**附加点击事件，不会解析消息中的 MiniMessage，远程文本依旧安全。
- 私聊（`/msg`、`/r`）同样支持链接与物品展示。

## dm（跨服私聊）

```yaml
dm:
  enabled: true                 # 跨服私聊总开关
  commands: [msg, tell, w, whisper, m]   # 会被接管/路由的私聊命令
  reply-commands: [r, reply]    # 回复命令（回复上一次私聊的发信人）
  sender-confirm: true          # 发送方是否显示一条“已发送”确认
  override-vanilla: true        # 连本地私聊也由插件接管（统一格式化 + 日志）
  log-to-console: true          # 每台相关服务器控制台记录完整私聊内容
```

- `override-vanilla: true`（默认）时，**本地与跨服私聊都由本插件负责**：统一用模板渲染并在控制台记录。
- 目标在**其它服务器**时，通过 Redis 定向投递到目标所在服务器（只有目标服投递）。
- 接收方收到后用 `formats.dm-receive` 渲染，并记住发信人；接收方用 `/r` 即可回复（本地/跨服均可）。
- 关闭 `override-vanilla` 时，本地私聊交还原生（或其它私聊插件），插件只处理跨服私聊。
- 对应模板：`formats.dm-send`（发送方确认）、`formats.dm-receive`（接收方展示）。
- 私聊正文同样按**纯文本**插入，不解析 MiniMessage。
- 权限：`hammercross.dm`（默认所有人可用）。

## completion（补全）

```yaml
completion:
  packet-fallback: true     # PacketEvents 兜底改写补全响应（兼容 Brigadier 命令）
  smart: true               # 只对玩家名相关命令 / 已识别为玩家名前缀的输入补全
  commands:                 # smart=true 且非空时，仅这些命令的参数位置补全
    - msg
    - tell
    - tpa
    - pay
    # ...
  min-prefix-length: 0      # 0 表示参数位置即使还没输入前缀也可列出全部玩家名
  max-results: 100
```

## motd（服务器列表）

```yaml
motd:
  sync-player-count: true
  sync-max-players: true
  max-players-mode: "aggregate"   # aggregate=各服上限求和; local=本服; fixed=固定值
  fixed-max-players: 100
  cross-server-sample: false       # 是否在悬浮样本中混入跨服玩家
  sample-size: 12
  override-motd: true              # 用 formats.motd 覆盖 MOTD 文本
```

## 模板与占位符 formats

```yaml
formats:
  chat: "<gray>[<server>]</gray> <white><player></white> <dark_gray>»</dark_gray> <message>"
  join: "<green><player></green> <gray>加入了</gray> <aqua><server></aqua>"
  quit: "<yellow><player></yellow> <gray>离开了</gray> <aqua><server></aqua>"
  tab:  "<white><player></white> <dark_gray>@</dark_gray> <gray><server></gray>"
  motd:
    - "<gradient:#56CCF2:#2F80ED>Hammer Network</gradient>"
    - "<gray>全服在线 <white><online></white>/<white><max></white></gray> <dark_gray>|</dark_gray> <gray>本服 <white><local_online></white></gray>"
```

支持的占位符：

| 占位符 | 含义 | 可用于 |
| --- | --- | --- |
| `<server>` / `%server%` | 来源服务器显示名（接收方映射） | chat/join/quit/tab/motd |
| `<server_id>` | 来源服务器 ID（纯文本） | 全部 |
| `<player>` | 玩家名（纯文本） | chat/join/quit/tab |
| `<player_display>` | 玩家显示名（纯文本） | chat/join/quit/tab |
| `<message>` | 聊天内容 / 私聊内容（纯文本，不解析） | chat / dm-send / dm-receive |
| `<target>` | 私聊目标名（纯文本） | dm-send |
| `<online>` | 全服在线人数 | motd |
| `<max>` | 全服最大人数 | motd |
| `<local_online>` | 本服在线人数 | motd |
| `<symbol:key>` / `%symbol.key%` | 符号表里的符号 | 全部 |

格式支持：

- **MiniMessage**：`<gray>`、`<bold>`、`<gradient:...>`、`<#RRGGBB>` …
- **传统颜色**：`&7`、`&a`、`&l`、`&#FFAA00`，以及 `§` 写法和 `§x§F§F§A§A§0§0`
- **符号**：`<symbol:star>` 或 `%symbol.star%`

---

## 符号与资源包（私用区字符）

`symbols` 是**本服**的符号表；`servers.<来源id>.symbols` 是针对某来源服的覆盖。
因为**接收方永远使用自己的符号表**，所以「有资源包的服」与「无资源包的服」可以各自定义同名符号：

```yaml
symbols:
  star: "★"          # 无资源包：用 Unicode 字符
  moon: "☾"

servers:
  resourcepack-1:    # 来自资源包服的内容，在本服如此显示
    display-name: "<gradient:#FDC830:#F37335>资源包服</gradient>"
    symbols:
      moon: "\uE001"   # 本服已加载资源包，用私用区自定义字形
```

- YAML 中私用区字符可写成 `"\uE001"`（双引号内为转义）。
- 模板里用 `<symbol:moon>` 或 `%symbol.moon%` 引用，渲染时替换为**接收方**解析到的字形。
- 源服聊天内容里出现的 `%symbol.xxx%` **不会**被替换（聊天正文按纯文本处理），只有**模板**会替换符号。

---

## 两台服务器配置示例

下列片段用来修改首次启动生成的配置。`redis.example.test` 是占位地址，请换成各服都能访问的 Redis 地址。

**survival-1（生存服）**

```yaml
server-id: "survival-1"
local:
  display-name: "<green>生存服</green>"

redis: { host: "redis.example.test", port: 6379, prefix: "example:network" }

servers:
  skyblock-1:
    display-name: "<aqua>空岛服</aqua>"
    formats:
      chat: "<gray>[<server>]</gray> <white><player></white><gray>：</gray> <message>"

formats:
  tab: "<white><player></white> <dark_gray>@</dark_gray> <green><server></green>"
```

**skyblock-1（空岛服）**

```yaml
server-id: "skyblock-1"
local:
  display-name: "<aqua>空岛服</aqua>"

redis: { host: "redis.example.test", port: 6379, prefix: "example:network" }

servers:
  survival-1:
    display-name: "<green>生存服</green>"
    symbols:
      star: "☆"           # 本服对「生存服」内容里的星形符号使用空心星

formats:
  tab: "<white><player></white> <dark_gray>|</dark_gray> <aqua><server></aqua>"
```

要点：`server-id` 不同、`local.display-name` 不同、`redis` 相同、各自用 `servers.<对方>` 定义映射与模板。
