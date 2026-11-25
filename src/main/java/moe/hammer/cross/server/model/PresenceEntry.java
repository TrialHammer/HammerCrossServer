package moe.hammer.cross.server.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class PresenceEntry {
    public String serverId;
    public String serverDisplayName;
    public String uuid;
    public String name;
    public String playerDisplayName;
    public long updatedAt;
    public int latency;
    public String gameMode;
    public boolean listed;
    public List<TextureData> textures = new ArrayList<>();

    public PresenceEntry() {
    }

    public PresenceEntry(String serverId,
                         String serverDisplayName,
                         UUID uuid,
                         String name,
                         String playerDisplayName,
                         long updatedAt,
                         int latency,
                         String gameMode,
                         boolean listed,
                         List<TextureData> textures) {
        this.serverId = Objects.requireNonNullElse(serverId, "");
        this.serverDisplayName = Objects.requireNonNullElse(serverDisplayName, "");
        this.uuid = uuid == null ? "" : uuid.toString();
        this.name = Objects.requireNonNullElse(name, "");
        this.playerDisplayName = Objects.requireNonNullElse(playerDisplayName, this.name);
        this.updatedAt = updatedAt;
        this.latency = latency;
        this.gameMode = Objects.requireNonNullElse(gameMode, "SURVIVAL");
        this.listed = listed;
        this.textures = textures == null ? new ArrayList<>() : new ArrayList<>(textures);
    }

    public UUID uuidOrNull() {
        try {
            return uuid == null || uuid.isBlank() ? null : UUID.fromString(uuid);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public String fieldKey() {
        return serverId + ":" + uuid;
    }
}
