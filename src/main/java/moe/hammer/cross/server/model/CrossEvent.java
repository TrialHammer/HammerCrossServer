package moe.hammer.cross.server.model;

import java.util.Objects;
import java.util.UUID;

public final class CrossEvent {
    public static final int PROTOCOL_VERSION = 1;

    public int protocol = PROTOCOL_VERSION;
    public String id;
    public String type;
    public String serverId;
    public String serverDisplayName;
    public long timestamp;
    public String playerUuid;
    public String playerName;
    public String playerDisplayName;
    /** Optional original Bedrock account name, for external avatars. */
    public String bedrockUsername;
    public String message;
    /** Base64 of ItemStack#serializeAsBytes() for the sender's held item, or null. */
    public String chatItem;
    public boolean silent;
    public PresenceEntry entry;

    // Private-message (dm) routing fields.
    public String targetUuid;
    public String targetName;
    public String targetServerId;

    public CrossEvent() {
    }

    public static CrossEvent join(String serverId, String serverDisplayName, PresenceEntry entry, boolean silent) {
        CrossEvent event = new CrossEvent();
        event.id = UUID.randomUUID().toString();
        event.type = "join";
        event.serverId = Objects.requireNonNullElse(serverId, "");
        event.serverDisplayName = Objects.requireNonNullElse(serverDisplayName, serverId);
        event.timestamp = System.currentTimeMillis();
        event.silent = silent;
        event.entry = entry;
        if (entry != null) {
            event.playerUuid = entry.uuid;
            event.playerName = entry.name;
            event.playerDisplayName = entry.playerDisplayName;
        }
        return event;
    }

    public static CrossEvent quit(String serverId, String serverDisplayName, String uuid, String name,
                                  String playerDisplayName, boolean silent) {
        CrossEvent event = new CrossEvent();
        event.id = UUID.randomUUID().toString();
        event.type = "quit";
        event.serverId = Objects.requireNonNullElse(serverId, "");
        event.serverDisplayName = Objects.requireNonNullElse(serverDisplayName, serverId);
        event.timestamp = System.currentTimeMillis();
        event.playerUuid = Objects.requireNonNullElse(uuid, "");
        event.playerName = Objects.requireNonNullElse(name, "");
        event.playerDisplayName = Objects.requireNonNullElse(playerDisplayName, event.playerName);
        event.silent = silent;
        return event;
    }

    public static CrossEvent chat(String serverId, String serverDisplayName, String uuid, String name,
                                  String playerDisplayName, String message, String chatItem) {
        CrossEvent event = new CrossEvent();
        event.id = UUID.randomUUID().toString();
        event.type = "chat";
        event.serverId = Objects.requireNonNullElse(serverId, "");
        event.serverDisplayName = Objects.requireNonNullElse(serverDisplayName, serverId);
        event.timestamp = System.currentTimeMillis();
        event.playerUuid = Objects.requireNonNullElse(uuid, "");
        event.playerName = Objects.requireNonNullElse(name, "");
        event.playerDisplayName = Objects.requireNonNullElse(playerDisplayName, event.playerName);
        event.message = Objects.requireNonNullElse(message, "");
        event.chatItem = chatItem;
        return event;
    }

    public static CrossEvent kookChat(String gatewayId, String messageId, String nickname, String message) {
        CrossEvent event = chat(gatewayId, "KOOK", "", nickname, nickname, message, null);
        event.type = "kook_chat";
        event.id = "kook:" + messageId;
        return event;
    }

    public static CrossEvent privateMessage(String serverId, String serverDisplayName,
                                            String senderUuid, String senderName, String senderDisplayName,
                                            String targetUuid, String targetName, String targetServerId,
                                            String message, String chatItem) {
        CrossEvent event = new CrossEvent();
        event.id = UUID.randomUUID().toString();
        event.type = "dm";
        event.serverId = Objects.requireNonNullElse(serverId, "");
        event.serverDisplayName = Objects.requireNonNullElse(serverDisplayName, serverId);
        event.timestamp = System.currentTimeMillis();
        event.playerUuid = Objects.requireNonNullElse(senderUuid, "");
        event.playerName = Objects.requireNonNullElse(senderName, "");
        event.playerDisplayName = Objects.requireNonNullElse(senderDisplayName, event.playerName);
        event.targetUuid = Objects.requireNonNullElse(targetUuid, "");
        event.targetName = Objects.requireNonNullElse(targetName, "");
        event.targetServerId = Objects.requireNonNullElse(targetServerId, "");
        event.message = Objects.requireNonNullElse(message, "");
        event.chatItem = chatItem;
        event.silent = true;
        return event;
    }

    public static CrossEvent serverOffline(String serverId, String serverDisplayName) {
        CrossEvent event = new CrossEvent();
        event.id = UUID.randomUUID().toString();
        event.type = "server_offline";
        event.serverId = Objects.requireNonNullElse(serverId, "");
        event.serverDisplayName = Objects.requireNonNullElse(serverDisplayName, serverId);
        event.timestamp = System.currentTimeMillis();
        event.silent = true;
        return event;
    }
}
