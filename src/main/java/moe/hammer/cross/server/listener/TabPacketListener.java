package moe.hammer.cross.server.listener;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientTabComplete;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTabComplete;
import moe.hammer.cross.server.HammerCrossServer;
import moe.hammer.cross.server.service.CompletionService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TabPacketListener extends PacketListenerAbstract {
    private final HammerCrossServer plugin;
    private final CompletionService completionService;
    private final Map<UUID, String> lastRequest = new ConcurrentHashMap<>();

    public TabPacketListener(HammerCrossServer plugin, CompletionService completionService) {
        super(PacketListenerPriority.LOWEST);
        this.plugin = plugin;
        this.completionService = completionService;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.TAB_COMPLETE) {
            return;
        }
        if (!(event.getPlayer() instanceof org.bukkit.entity.Player player)) {
            return;
        }
        if (!completionService.shouldOfferForPacket()) {
            return;
        }
        try {
            WrapperPlayClientTabComplete wrapper = new WrapperPlayClientTabComplete(event);
            String text = wrapper.getText();
            if (text != null) {
                lastRequest.put(player.getUniqueId(), text);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.TAB_COMPLETE) {
            return;
        }
        if (!(event.getPlayer() instanceof org.bukkit.entity.Player player)) {
            return;
        }
        if (!completionService.shouldOfferForPacket()) {
            return;
        }
        String request = lastRequest.remove(player.getUniqueId());
        if (request == null || !completionService.shouldOffer(request)) {
            return;
        }
        try {
            WrapperPlayServerTabComplete wrapper = new WrapperPlayServerTabComplete(event);
            List<WrapperPlayServerTabComplete.CommandMatch> matches = new ArrayList<>(wrapper.getCommandMatches());
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            for (WrapperPlayServerTabComplete.CommandMatch match : matches) {
                seen.add(match.getText().toLowerCase(Locale.ROOT));
            }
            boolean changed = false;
            for (String name : completionService.completions(request)) {
                if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                matches.add(new WrapperPlayServerTabComplete.CommandMatch(name));
                changed = true;
            }
            if (changed) {
                wrapper.setCommandMatches(matches);
                event.markForReEncode(true);
            }
        } catch (Throwable ignored) {
        }
    }
}
