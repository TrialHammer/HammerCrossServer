# HammerCrossServer

HammerCrossServer 让独立的 Paper 服务器共享聊天、Tab 列表和在线人数。玩家在不同服务器上也能互发私聊、看到上下线提醒，并用 `/glist` 查找全网在线玩家。各服通过 Redis 同步，不需要 BungeeCord 或 Velocity。

## 安装

按[构建说明](docs/development.md)选择适合服务端的 JAR，在每台参与同步的 Paper 服务器上安装 HammerCrossServer 和 PacketEvents。首次启动后，在 `plugins/HammerCrossServer/config.yml` 中为每台服务器设置唯一的 `server-id`，并让它们连接同一个 Redis、使用相同的键前缀。重启后用 `/hcs status` 检查连接，再用 `/glist` 确认各服能看到彼此的玩家。

## 配置

各服可以使用自己的聊天和 Tab 样式。[配置参考](docs/configuration.md)提供双服示例，[运维指南](docs/operations.md)介绍命令权限、热重载、排障和可选的 KOOK 桥接。

## 第三方授权

KOOK 桥接源自 KookSRV，其 [MIT 授权文本](src/main/resources/META-INF/LICENSE-KookSRV)随插件分发。
