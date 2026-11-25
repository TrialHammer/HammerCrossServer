package moe.hammer.cross.server.model;

import java.util.Objects;

public final class ServerEntry {
    public String serverId;
    public String displayName;
    public int maxPlayers;
    public int onlinePlayers;
    public long updatedAt;

    public ServerEntry() {
    }

    public ServerEntry(String serverId, String displayName, int maxPlayers, int onlinePlayers, long updatedAt) {
        this.serverId = Objects.requireNonNullElse(serverId, "");
        this.displayName = Objects.requireNonNullElse(displayName, this.serverId);
        this.maxPlayers = maxPlayers;
        this.onlinePlayers = onlinePlayers;
        this.updatedAt = updatedAt;
    }
}
