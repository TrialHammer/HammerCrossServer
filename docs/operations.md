# 运维指南

Paper、Java 和 PacketEvents 的兼容要求见[构建说明](development.md)。详细配置项和双服示例见[配置参考](configuration.md)。

## 安装

1. 每台 Paper 服务器安装 **官方 PacketEvents release** 到 `plugins/`。
2. 把对应版本的 `HammerCrossServer-*.jar` 放进 `plugins/`。
3. 启动一次，生成 `plugins/HammerCrossServer/config.yml` 与 `messages.yml`。
4. 编辑 `config.yml`：
   - **每台服务器 `server-id` 必须唯一**（`survival-1`、`skyblock-1`、`creative-1` …）。
   - 所有服务器使用同一个 Redis 实例、数据库和 `prefix`，并按部署环境填写认证与 TLS 设置。
   - 用 `servers.<其它服务器id>` 为其它服务器定义各自的显示名与模板。
5. 重启全部服务器，用 `/hcs status` 确认 Redis 已连接，再用 `/glist` 检查各节点能否互相看到。

运行配置留在各服务器的 `plugins/HammerCrossServer/`。仓库里的默认 YAML 不应写入真实 Redis 凭据、KOOK Token 或生产环境配置。

> 修改 `server-id` 或 Redis 连接参数后需要**重启**；其余内容（模板、符号表、开关、私聊命令、补全规则、MOTD、`messages.yml` 等）都可用 `/hammercross reload` **热重载，无需重启**。

## 配置版本与自动迁移

`config.yml` 和 `messages.yml` 各自用 `config-version` 记录结构版本，与插件 JAR 的版本号独立。启动和 `/hcs reload` 都会检查版本。没有版本号的旧文件会按旧格式处理。

- 升级前在原文件旁保存一份原样备份，备份文件以 `.bak` 结尾。随后补齐缺失字段，不覆盖已设置的值或列表，也不加入示例服映射、不补写自定义符号表。新增 KOOK 设置时默认关闭。
- 旧的 `chat.item.placeholders` 会迁到 `chat.item.keys`。两项都存在时合并，原来可用的触发词仍然可用。
- 当前版本的文件不会被重写。YAML 无法读取或文件版本比插件新时会报错，不覆盖该文件。热重载失败时保留正在使用的配置和语言文本。
- 升级不需要删除旧文件，也不要手动调高 `config-version`。要回到旧版插件，可从相应备份恢复原文件。

## KOOK 桥接（可选）

把新版 HammerCrossServer 安装到所有参与跨服聊天的节点，只挑一台做 KOOK 网关。
网关沿用已有的跨服广播和在线缓存，不需要新 Redis 实例、数据库、频道或权限。
其他节点保持默认的 `kook.enabled: false`，不创建 KOOK 客户端，也不用填写机器人 Token。

1. 在 KOOK 开发者中心将机器人事件模式设为 WebSocket，把机器人加入服务器，并允许它查看、收发目标文字频道。见 [KOOK WebSocket 文档](https://developer.kookapp.cn/doc/websocket)。
2. 仅在网关的 `plugins/HammerCrossServer/config.yml` 中设置下列选项。已有配置文件可手动加上这段。
3. 在网关执行 `/hcs reload`，用 `/hcs status` 查看 KOOK 是否已连接。换 Token 或频道也可热重载。迁移时停用旧的 KookSRV，避免两个插件同时转发。

```yaml
kook:
  enabled: true
  token: "机器人 Token"
  channel: "文字频道 ID"
  server-name: "Minecraft 网络"
```

- 默认同步公共文本聊天和非静默上下线卡片。玩家私聊、KOOK 私信、机器人消息和附件不会转发。
- 在 KOOK 频道输入 `在线玩家` 或 `在线列表`，机器人会引用回复全服在线名单。关键词、方向开关、卡片主题与头像 URL 都可在[默认配置](../src/main/resources/config.yml) 中调整。
- 各台游戏服的入站样式用 `formats.kook-chat` 设置，支持 MiniMessage、传统颜色以及本服的符号映射。可用 `<server>`（KOOK）、`<server_id>`（网关 ID）、`<player>`、`<message>`。内容以纯文本插入，链接按本服规则识别。关闭本服的 `sync.chat` 会一并停止处理入站 KOOK 聊天和公共跨服聊天。
- 发往 KOOK 的模板位于 `kook.messages`。公共聊天和上下线的 `%server_name%` 是玩家所在服，`%network_name%` 是 `kook.server-name`；在线回复的 `%server_name%` 也是网络名。可以沿用 KookSRV 的发送模板。装有 PlaceholderAPI 的网关可解析发送模板中的 PAPI 变量，跨服玩家只能使用支持离线玩家数据的扩展。
- 玩家所在服装有 Floodgate 时，头像 URL 中的 `%player_name%` 使用原始基岩版账号名。把 `kook.avatar-url` 设为空可关闭卡片头像。
- 只有配置了网关的节点需要访问 KOOK。切换网关时先关闭原网关，再启用新网关，避免重复发送。

KOOK 功能移植自 KookSRV（Eter Lu，MIT）。[授权文本](../src/main/resources/META-INF/LICENSE-KookSRV)随插件打包。

## 命令与权限

| 命令 | 权限 | 说明 |
| --- | --- | --- |
| `/hcs status` | `hammercross.admin` | 查看 Redis、KOOK 状态和已知节点 |
| `/hcs players` | `hammercross.admin` | 列出当前缓存中的全服玩家 |
| `/hcs reload` | `hammercross.admin` | 热重载 `config.yml` 与 `messages.yml` |
| `/glist [服务器]` | `hammercross.glist` | 按服列出全网在线玩家，可按 ID 或显示名筛选 |
| `/msg <玩家> <内容>`、`/r <内容>` | `hammercross.dm` | 发送或回复私聊，命令别名可配置 |

`/hcs` 是 `/hammercross` 的别名。管理权限默认只给 OP，在线列表和私聊权限默认对所有玩家开放。

### `/glist` 全服在线列表

```
/glist                  # 列出全部服务器与全部在线玩家
/glist skyblock-1       # 只看某个服务器（可用 ID 或显示名，支持模糊匹配）
/glist 空岛             # 用中文显示名过滤
```

- 独立命令，别名 `/globalist`、`/gl`；也可用 `/hammercross glist [服务器]`
- 权限：`hammercross.glist`（默认所有人可用，可用权限插件收紧）
- 按服务器分组输出「服务器名 (server-id) 在线/上限」以及该服每个玩家名
- 已知但无人在线的服务器也会列出并标注「无玩家」
- 数据来自 Redis 在线缓存，任意节点（含控制台）都能看到全网络玩家

### 热重载（`/hammercross reload`）

可热重载的内容：

- 所有 `formats.*` 模板、`symbols`、`servers.*` 映射与符号
- `sync.*` 各功能开关（关闭 `sync.tab` 时会先清除已注入的虚拟 Tab 条目再恢复原生显示）
- `chat.*`、`dm.*`、`join-quit.*`、`tab.*`、`completion.*`、`motd.*`、`presence.*`
- KOOK 网关设置与 `messages.yml` 全部文本

重载流程与效果：

- **先完整解析、再一次性应用**：YAML 写坏或字段非法时直接报错，**旧配置原样保留**，不会出现半应用状态
- 应用阶段若抛异常会尝试回滚到上一份配置。若日志提示回滚失败，需要重启服务器
- 重载后立即刷新 Tab 显示名，下一次服务器列表 ping 即使用新 MOTD
- `server-id` 和 Redis 的主机、端口、用户名、密码、数据库、TLS 与前缀不可热改：检测到变化会**拒绝重载**并提示重启
- 热重载不会断开现有玩家，也不会重建 Redis 连接

## 常见问题

- **每台服务器都必须安装官方 PacketEvents release 插件**；本插件不 shade PacketEvents。
- 不同 Paper 目标的产物不能混用。请按[构建说明](development.md)选择 JAR。
- Redis 不可用时插件仍能正常启动：启动日志会告警，后台自动重连；此时仅本服功能可用。
- 远程服显示名若未在本服 `servers.<id>.display-name` 中映射，会作为**纯文本**显示（其中的 MiniMessage/标签会被剥离），如需颜色/字形请显式配置映射。
- 玩家名、聊天正文、私聊正文不会被解析为 MiniMessage，防止跨服注入格式。
- 跨服私聊的 `/r` 由本插件维护“上一次跨服发信人”。若同一玩家同时与本地玩家私聊，原生 `/r` 与本插件的 `/r` 可能各记一份，混用时请优先用完整 `/msg <名字>`。
- 如果 Tab 的远程玩家条目异常，先按[构建说明](development.md)核对 PacketEvents 版本，以及当前 JAR 是否匹配服务端。
- 若与其他也修改 Tab 或补全的插件共存，可按需关闭 `sync.tab` 或 `completion.packet-fallback`，排查虚拟条目或数据包补全冲突。
