package moe.hammer.cross.server;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import moe.hammer.cross.server.api.CrossServerApi;
import moe.hammer.cross.server.command.CrossCommand;
import moe.hammer.cross.server.command.GListCommand;
import moe.hammer.cross.server.config.ConfigManager;
import moe.hammer.cross.server.config.CrossConfig;
import moe.hammer.cross.server.config.KookSettings;
import moe.hammer.cross.server.kook.KookBridge;
import moe.hammer.cross.server.listener.ChatListener;
import moe.hammer.cross.server.listener.CompletionListener;
import moe.hammer.cross.server.listener.JoinQuitListener;
import moe.hammer.cross.server.listener.PingListener;
import moe.hammer.cross.server.listener.PrivateMessageListener;
import moe.hammer.cross.server.listener.TabPacketListener;
import moe.hammer.cross.server.model.CrossEvent;
import moe.hammer.cross.server.model.PresenceEntry;
import moe.hammer.cross.server.model.PresenceSnapshot;
import moe.hammer.cross.server.redis.RedisManager;
import moe.hammer.cross.server.service.ChatSyncService;
import moe.hammer.cross.server.service.CompletionService;
import moe.hammer.cross.server.service.JoinQuitService;
import moe.hammer.cross.server.service.MotdService;
import moe.hammer.cross.server.service.PresenceService;
import moe.hammer.cross.server.service.PrivateMessageService;
import moe.hammer.cross.server.service.TabSyncService;
import moe.hammer.cross.server.text.FormatService;
import moe.hammer.cross.server.text.MessageService;
import moe.hammer.cross.server.text.PlayerListCache;
import moe.hammer.cross.server.util.Deduper;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public final class HammerCrossServer extends JavaPlugin {
    private ConfigManager configManager;
    private volatile CrossConfig config;
    private MessageService messages;
    private FormatService formatService;
    private PlayerListCache playerCache;
    private RedisManager redis;
    private PresenceService presenceService;
    private TabSyncService tabSyncService;
    private ChatSyncService chatSyncService;
    private JoinQuitService joinQuitService;
    private MotdService motdService;
    private CompletionService completionService;
    private PrivateMessageService privateMessageService;
    private TabPacketListener tabPacketListener;
    private PacketListenerCommon packetListenerHandle;
    private Deduper deduper;
    private CrossServerApi api;
    private volatile KookBridge kookBridge;

    @Override
    public void onEnable() {
        configManager = new ConfigManager(this);
        ConfigManager.Prepared initial = configManager.prepare();
        initial.save(getLogger());
        config = initial.config();
        messages = new MessageService(initial.messages());
        playerCache = new PlayerListCache();
        playerCache.setLocalCounts(Bukkit.getOnlinePlayers().size(), Bukkit.getMaxPlayers());
        formatService = new FormatService(this, config);
        redis = new RedisManager(this);
        tabSyncService = new TabSyncService(this, playerCache, formatService);
        chatSyncService = new ChatSyncService(this, formatService);
        joinQuitService = new JoinQuitService(this, formatService);
        motdService = new MotdService(this, playerCache, formatService);
        completionService = new CompletionService(this, playerCache, formatService);
        privateMessageService = new PrivateMessageService(this, playerCache, formatService);
        presenceService = new PresenceService(this, redis, playerCache, tabSyncService);
        deduper = new Deduper(60_000L);
        api = new CrossServerApi(this);

        if (!Bukkit.getPluginManager().isPluginEnabled("packetevents")) {
            getLogger().severe("需要 PacketEvents 插件才能同步虚拟 Tab 列表与数据包补全。");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getServicesManager().register(
                CrossServerApi.class, api, this, org.bukkit.plugin.ServicePriority.Normal);

        tabSyncService.start(config);
        chatSyncService.start(config);
        joinQuitService.start(config);
        motdService.start(config);
        completionService.start(config);
        privateMessageService.start(config);
        presenceService.start(config);

        Bukkit.getPluginManager().registerEvents(new ChatListener(this, chatSyncService, formatService), this);
        Bukkit.getPluginManager().registerEvents(new JoinQuitListener(this, presenceService, joinQuitService, tabSyncService), this);
        Bukkit.getPluginManager().registerEvents(new PingListener(this, motdService), this);
        Bukkit.getPluginManager().registerEvents(new CompletionListener(this, completionService), this);
        Bukkit.getPluginManager().registerEvents(new PrivateMessageListener(this, privateMessageService), this);

        tabPacketListener = new TabPacketListener(this, completionService);
        packetListenerHandle = PacketEvents.getAPI().getEventManager().registerListener(tabPacketListener);

        PluginCommand command = getCommand("hammercross");
        if (command != null) {
            CrossCommand executor = new CrossCommand(this, messages);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        PluginCommand glist = getCommand("glist");
        if (glist != null) {
            GListCommand executor = new GListCommand(this, messages);
            glist.setExecutor(executor);
            glist.setTabCompleter(executor);
        }

        redis.connect(config, this::handleEvent, () -> {
            redis.loadSnapshot(this::handleSnapshot);
            presenceService.refreshLocalPlayerCache();
        });

        try {
            config.kook().validate();
            applyKook(config.kook());
        } catch (RuntimeException error) {
            getLogger().warning("KOOK 未启动: " + describeError(error));
        }

        getLogger().info("HammerCrossServer 已启用，server-id=" + config.serverId());
        redis.loadSnapshot(this::handleSnapshot);
        presenceService.refreshLocalPlayerCache();
    }

    private void handleSnapshot(PresenceSnapshot snapshot) {
        Bukkit.getGlobalRegionScheduler().execute(this, () -> {
            presenceService.acceptSnapshot(snapshot);
        });
    }

    private void handleEvent(CrossEvent event) {
        if (event == null || event.type == null || event.serverId == null || event.serverId.equals(config.serverId())) {
            return;
        }
        if (event.protocol != 0 && event.protocol != CrossEvent.PROTOCOL_VERSION) {
            return;
        }
        if (!deduper.firstTime(event.id)) {
            return;
        }
        switch (event.type) {
            case "chat" -> chatSyncService.handleRemoteChat(event);
            case "kook_chat" -> chatSyncService.handleKookChat(event);
            case "join" -> {
                if (event.entry != null) {
                    PresenceEntry entry = event.entry;
                    Bukkit.getGlobalRegionScheduler().execute(this, () -> playerCache.put(entry));
                    Bukkit.getGlobalRegionScheduler().execute(this, () -> tabSyncService.reconcileRemoteEntries());
                }
                joinQuitService.broadcastRemoteJoin(event);
            }
            case "quit" -> {
                String serverId = event.serverId;
                String uuidText = event.playerUuid;
                Bukkit.getGlobalRegionScheduler().execute(this, () -> {
                    try {
                        if (uuidText != null && !uuidText.isBlank()) {
                            java.util.UUID uuid = java.util.UUID.fromString(uuidText);
                            playerCache.remove(serverId, uuid);
                            tabSyncService.removeRemoteFromAll(uuid);
                        }
                    } catch (IllegalArgumentException ignored) {
                    }
                });
                joinQuitService.broadcastRemoteQuit(event);
            }
            case "dm" -> Bukkit.getGlobalRegionScheduler().execute(this,
                    () -> privateMessageService.handleRemoteDm(event));
            case "server_offline" -> Bukkit.getGlobalRegionScheduler().execute(this, () -> {
                // Remove the virtual entries first: the tab service needs to read
                // the cache to know which UUIDs to drop.
                tabSyncService.removeServerFromAll(event.serverId);
                playerCache.removeServer(event.serverId);
            });
            default -> {
            }
        }
        forwardToKook(event);
    }

    /** Publishes a locally produced public event; Redis echoes are ignored by this node. */
    public void publishPublicEvent(CrossEvent event) {
        redis.publish(event);
        forwardToKook(event);
    }

    private void forwardToKook(CrossEvent event) {
        KookBridge bridge = kookBridge;
        if (bridge != null) {
            bridge.forward(event);
        }
    }

    /** Receives a KOOK-origin event without feeding it back into the KOOK sender. */
    public void acceptKookChat(KookBridge source, CrossEvent event) {
        if (kookBridge != source || !deduper.firstTime(event.id)) {
            return;
        }
        redis.publish(event);
        chatSyncService.handleKookChat(event);
    }

    private void applyKook(KookSettings next) {
        KookBridge old = kookBridge;
        if (next.enabled() && old != null && old.canReconfigure(next)) {
            old.reconfigure(next);
            return;
        }
        stopKook();
        if (next.enabled()) {
            KookBridge bridge = new KookBridge(this, next);
            kookBridge = bridge;
            bridge.start();
        }
    }

    private void stopKook() {
        KookBridge old = kookBridge;
        kookBridge = null;
        if (old != null) {
            old.close();
        }
    }

    public String kookStatus() {
        KookBridge bridge = kookBridge;
        if (bridge == null) {
            return config.kook().enabled() ? "未启动（请检查配置）" : "已关闭";
        }
        return (bridge.connected() ? "已连接" : "连接中") + " · " + config.kook().channel();
    }

    /**
     * Hot-reloads config.yml and messages.yml. Parsing happens before anything is
     * applied, and any failure while applying rolls every subsystem back to the
     * previous configuration, so a bad reload never leaves a half-applied state.
     *
     * <p>Network identity (server-id / Redis connection / prefix) cannot change
     * while the server is running: it is rejected with a restart hint.</p>
     */
    public void reloadCrossServer(CommandSender sender) {
        CrossConfig previous = config;
        ConfigManager.Prepared prepared;
        CrossConfig next;
        try {
            prepared = configManager.prepare();
            next = prepared.config();
            next.kook().validate();
        } catch (Throwable error) {
            getLogger().warning("重载配置失败，当前配置保持不变: " + describeError(error));
            messages.send(sender, "reload-failed");
            return;
        }

        if (!previous.sameNetwork(next)) {
            getLogger().warning("检测到 server-id 或 Redis 连接参数发生变化，已拒绝热重载（需要重启服务器）。");
            messages.send(sender, "reload-requires-restart");
            return;
        }

        try {
            prepared.save(getLogger());
        } catch (RuntimeException error) {
            getLogger().warning("保存配置迁移失败，当前运行配置保持不变: " + describeError(error));
            messages.send(sender, "reload-failed");
            return;
        }

        try {
            applyConfig(next);
            applyKook(next.kook());
            messages.replace(prepared.messages());
        } catch (Throwable error) {
            getLogger().warning("应用新配置失败，正在回滚: " + describeError(error));
            try {
                applyConfig(previous);
                if (previous.kook().valid()) {
                    applyKook(previous.kook());
                } else {
                    stopKook();
                }
            } catch (Throwable rollbackError) {
                getLogger().severe("配置回滚失败，请重启服务器: " + describeError(rollbackError));
            }
            messages.send(sender, "reload-failed");
            return;
        }
        messages.send(sender, "reload-success");
        getLogger().info("配置已热重载: server-id=" + next.serverId()
                + " chat=" + next.sync().chat()
                + " tab=" + next.sync().tab()
                + " dm=" + next.sync().dm()
                + " motd=" + next.sync().motd());
    }

    /** Applies an already-validated configuration to every subsystem. */
    private void applyConfig(CrossConfig next) {
        config = next;
        formatService.setConfig(next);
        if (!next.sync().tab()) {
            // Clear previously injected virtual entries before resetting state.
            tabSyncService.removeAllVirtual();
        }
        tabSyncService.start(next);
        chatSyncService.start(next);
        joinQuitService.start(next);
        motdService.start(next);
        completionService.start(next);
        privateMessageService.start(next);
        presenceService.start(next);
        tabSyncService.refreshDisplayNames();
    }

    /** Compact one-line error description for user-facing logs. */
    private static String describeError(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return message.replace('\n', ' ').replace('\r', ' ').trim();
    }

    @Override
    public void onDisable() {
        stopKook();
        if (tabSyncService != null) {
            try {
                tabSyncService.resetLocalNamesNow();
            } catch (Throwable ignored) {
            }
        }
        if (presenceService != null) {
            presenceService.shutdown();
        }
        if (packetListenerHandle != null) {
            try {
                PacketEvents.getAPI().getEventManager().unregisterListener(packetListenerHandle);
            } catch (Throwable ignored) {
            }
        }
        if (redis != null) {
            redis.shutdown();
        }
        try {
            getServer().getServicesManager().unregisterAll(this);
        } catch (Throwable ignored) {
        }
    }

    public CrossConfig config() {
        return config;
    }

    public MessageService messages() {
        return messages;
    }

    public FormatService formatService() {
        return formatService;
    }

    public PlayerListCache playerCache() {
        return playerCache;
    }

    public RedisManager redis() {
        return redis;
    }

    public PresenceService presenceService() {
        return presenceService;
    }

    public TabSyncService tabSyncService() {
        return tabSyncService;
    }

    public CompletionService completionService() {
        return completionService;
    }

    public PrivateMessageService privateMessageService() {
        return privateMessageService;
    }

    public MotdService motdService() {
        return motdService;
    }

    public CrossServerApi api() {
        return api;
    }

    public List<Player> onlinePlayers() {
        return new ArrayList<>(Bukkit.getOnlinePlayers());
    }
}
