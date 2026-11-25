# 开发与构建

在仓库根目录运行下列命令。建议使用 JDK 25 构建两个目标，单独构建 Paper 1.21.11 时可使用 JDK 21。项目使用 Maven 3.9+，两个目标均输出 Java 21 字节码。

| Paper 目标 | API 依赖 | `plugin.yml` 的 `api-version` | 分发产物 |
| --- | --- | --- | --- |
| 26.3 | `26.3.build.254-stable` | `26.3` | `dist/HammerCrossServer-1.0.0-26.3.jar` |
| 1.21.11 | `1.21.11-R0.1-SNAPSHOT` | `1.21` | `dist/HammerCrossServer-1.0.0-1.21.11.jar` |

运行时需要在每台服务端安装官方 PacketEvents 2.14.0 完整插件。Maven 的 `packetevents-api` 依赖不能代替服务端插件。

```bash
mvn test     # 默认使用 Paper 26.3 API
mvn -Dpaper.api.version=1.21.11-R0.1-SNAPSHOT -Dpaper.api-version=1.21 test

./scripts/build.sh
PAPER_API_VERSION=1.21.11 ./scripts/build.sh
```

打包脚本运行 Maven 测试，然后将构件从 `target/` 复制到 `dist/`。Jedis、Commons Pool 2 和 `org.json` 被重定位到 `moe.hammer.cross.server.libs.*` 并随包分发。Paper、PacketEvents、Gson 和 SLF4J 不打入插件 JAR，运行时由服务端提供。

## 目录

```text
pom.xml                   Maven 依赖、测试与打包配置
scripts/build.sh          生成对应 Paper 版本的分发 JAR
docs/                     配置、运维与开发文档
src/main/java/            插件源码，根包 moe.hammer.cross.server
src/main/resources/       默认配置、语言文本、插件描述和 KookSRV 授权文本
src/test/java/            配置迁移和模板格式测试
target/、dist/             本地构建输出，不纳入 Git
```

源码按职责分包。`config` 加载和迁移配置，`redis` 管理连接和快照，`listener` 接收 Paper 与 PacketEvents 事件，`service` 处理聊天、私聊、在线状态、Tab 和 MOTD，`text` 负责渲染，`kook` 实现网关。

## 插件 API

`CrossServerApi` 是只读门面，由 Bukkit 的 `ServicesManager` 注册。调用方应在 HammerCrossServer 启用后获取它，并处理服务尚不可用的情况。

```java
CrossServerApi api = Bukkit.getServicesManager().load(CrossServerApi.class);
if (api != null) {
    int total = api.totalOnline();
    Collection<PresenceEntry> all = api.allPlayers();
    List<PresenceEntry> remote = api.remotePlayers();
    boolean connected = api.isConnected(); // 读取缓存，不发起 Redis 请求
}
```

## 工作原理

Redis 键（`prefix` 默认为 `hammer:cross`）：

```
<prefix>:presence:players      Hash  字段 <serverId>:<uuid> -> PresenceEntry(JSON)
<prefix>:presence:servers      Hash  字段 <serverId>        -> ServerEntry(JSON)
<prefix>:heartbeat:<serverId>  带 TTL 的心跳键
<prefix>:events                Pub/Sub 事件频道
```

- 每台服务器周期性写入自己的 `presence:servers`、所有在线玩家的 `presence:players`，并续期心跳 TTL。
- 事件（chat / join / quit / dm / server_offline）通过 Pub/Sub 广播，并带事件 ID 去重、协议版本号校验；其中 `dm` 事件带 `targetServerId`，只有目标所在服务器才会投递。
- 默认由插件接管原生聊天并在各接收服记录完整聊天文本。接收方使用本地模板渲染，远程玩家名和聊天正文以文本插入。
- 每台服务器周期性 `HGETALL` 拉取快照做校准；**心跳键不存在且记录已过期的服务器会被判定为下线并清理其服务器与玩家记录**。
- Pub/Sub 使用独立阻塞连接 + 断线重连退避；其余 Redis 命令走连接池 + 专用执行器，避免阻塞主线程。
- Tab 与补全在**主线程**通过 PacketEvents 发送数据包；`AsyncTabCompleteEvent` 使用 Paper 原生异步补全事件。
