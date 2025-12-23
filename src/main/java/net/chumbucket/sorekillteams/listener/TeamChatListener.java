package net.chumbucket.sorekillteams.listener;

import net.chumbucket.sorekillteams.SorekillTeamsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.AsyncPlayerChatEvent;

public final class TeamChatListener implements Listener {

    private final SorekillTeamsPlugin plugin;

    public TeamChatListener(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();

        // Only intercept if the player has team chat toggled on
        if (!plugin.teams().isTeamChatEnabled(p.getUniqueId())) return;

        // If they are no longer in a team, turn it off automatically
        var teamOpt = plugin.teams().getTeamByPlayer(p.getUniqueId());
        if (teamOpt.isEmpty()) {
            plugin.teams().setTeamChatEnabled(p.getUniqueId(), false);
            return;
        }

        e.setCancelled(true);
        String msg = e.getMessage();

        // Send team chat on the main thread (safer with some chat/format plugins)
        Bukkit.getScheduler().runTask(plugin, () -> plugin.teams().sendTeamChat(p, msg));
    }
}
