# HammerCrossServer 运维指南

## 安装和检查

按[构建说明](development.md)选择符合服务端的 JAR。每台服务器都需要安装 PacketEvents 的完整插件，不能用 Maven 的 API JAR 代替。启动一次生成 `plugins/HammerCrossServer/config.yml` 和 `messages.yml`，再按[双服示例](configuration.md)接入共享 Redis。重启后先用 `/hcs status` 查连接，再用 `/glist` 查跨服在线名单。

| 要做什么 | 命令 | 权限 |
| --- | --- | --- |
| 查看 Redis 与 KOOK 状态 | `/hcs status` | `hammercross.admin` |
| 列出在线玩家 | `/glist [服务器]` | `hammercross.glist` |
| 重载模板、文本、开关和集成设置 | `/hcs reload` | `hammercross.admin` |

管理权限默认给 OP，在线列表默认向所有玩家开放。私聊使用 `/msg` 或 `/r`，需要 `hammercross.dm`。要用服务器的原有本地私聊，可关闭 `dm.override-vanilla`。当同时使用原生和跨服回复时，请用 `/msg <玩家> <内容>` 指明目标。

## 修改现有部署

修改 `server-id` 或 Redis 的连接标识、凭据、数据库和前缀时需要重启。普通模板、功能开关和语言文本可热重载。YAML 不可读或配置无法应用时，检查日志中的原因。结构升级会保留已有值，并在相邻位置生成备份，不需要手动调高 `config-version`。

Redis 不可用时，插件会在后台重连。先检查各服能否访问同一个 Redis、是否使用相同数据库和前缀。不要在仓库的默认配置里填写实际 Redis 密码或 KOOK Token。

## 将公共聊天接到 KOOK

选择一台服作为网关。在 KOOK 将机器人配置为 WebSocket 模式，加入目标服务器，并授权读取和发送频道消息。只在网关服务器启用 `kook.enabled`，填写 `kook.token` 和 `kook.channel`，再用 `/hcs reload` 应用、`/hcs status` 检查连接。迁移时先停用旧桥接，避免重复转发。

公共聊天和非静默上下线通知可以桥接，私聊不会转发。玩家可在 KOOK 输入 `在线玩家` 查询名单。来自 KOOK 的样式通过 `formats.kook-chat` 调整，发往 KOOK 的模板在 `kook.messages`。更换网关时先关闭旧节点再启用新节点。
