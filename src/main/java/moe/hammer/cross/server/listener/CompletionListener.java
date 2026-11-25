package moe.hammer.cross.server.listener;

import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import moe.hammer.cross.server.HammerCrossServer;
import moe.hammer.cross.server.service.CompletionService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;

public final class CompletionListener implements Listener {
    private final HammerCrossServer plugin;
    private final CompletionService completionService;

    public CompletionListener(HammerCrossServer plugin, CompletionService completionService) {
        this.plugin = plugin;
        this.completionService = completionService;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onAsyncTabComplete(AsyncTabCompleteEvent event) {
        if (!(event.getSender() instanceof org.bukkit.entity.Player)) {
            return;
        }
        String buffer = event.getBuffer();
        if (!completionService.shouldOffer(buffer)) {
            return;
        }
        List<AsyncTabCompleteEvent.Completion> merged = new ArrayList<>(event.completions());
        merged.addAll(completionService.paperCompletions(buffer, event.getCompletions()));
        if (merged.size() > event.completions().size()) {
            event.completions(merged);
        }
    }
}
