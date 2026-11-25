package moe.hammer.cross.server.model;

import java.util.Map;
import java.util.UUID;

public record PresenceSnapshot(Map<UUID, PresenceEntry> players, Map<String, ServerEntry> servers) {
}
