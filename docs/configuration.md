# 配置 HammerCrossServer

打开各服务器的 `plugins/HammerCrossServer/config.yml`。所有字段、默认值和注释见[默认配置](../src/main/resources/config.yml)，玩家可见的命令文本在 [messages.yml](../src/main/resources/messages.yml)。

## 让两台服务器互通

为各服分配不同的 `server-id`，并使用同一个 Redis 实例、数据库与 `prefix`。以下示例用于同一网络中的大厅。把 Redis 地址换成服务端能访问的实际地址。

```yaml
server-id: lobby
local:
  display-name: "<green>大厅</green>"
redis:
  host: redis.example.org
  prefix: "example:network"
servers:
  survival:
    display-name: "<aqua>生存服</aqua>"
```

给生存服使用 `server-id: survival` 和自己的显示名称，保持 `redis` 设置一致。需要为大厅定制显示名称或模板时，在生存服编辑 `servers.lobby`。配置好身份和连接参数后，重启各服，再用 `/hcs status` 和 `/glist` 确认两台服务器互相可见。

## 让界面符合各服风格

`formats` 控制聊天、进出提醒、Tab 和 MOTD。不同服务器可以用不同的模板。同一模板可以包含 MiniMessage、传统颜色、`<player>` 和 `<server>` 等占位符。安装 PlaceholderAPI 后，接收服还可解析模板里的 PAPI 变量。跨服玩家的占位符能否解析，取决于该扩展是否支持离线或共享数据。

如果只有部分客户端使用资源包，让有资源包和无资源包的服分别设置 `symbols`。例如在模板写 `<symbol:star>`，由接收服决定替换成 Unicode 星号还是资源包字形。针对某个来源服的模板和符号可在 `servers.<id>` 覆盖。玩家输入的内容不会作为模板解析。

## 选择要同步的内容

`sync` 控制公共聊天、私聊、上下线提醒、Tab、补全和 MOTD。如果别的插件已负责本地聊天，可保留跨服同步并关闭 `chat.override-vanilla`。私聊对应 `dm.override-vanilla`。默认会把公共聊天和私聊写到相关服务器控制台，可分别关闭 `chat.log-to-console` 和 `dm.log-to-console`。

玩家可以用 `[item]` 在聊天里展示主手物品，或用 `[item-3]` 展示第三个物品栏槽位。设置在 `chat.item`，也可关闭物品展示或停用跨服同步。线上连续出现重复补全时，可以先关闭 `completion.packet-fallback` 排查与其他插件的冲突。

配置生效方式和 KOOK 网关见[运维指南](operations.md)。
