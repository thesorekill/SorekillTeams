package net.chumbucket.sorekillteams.command;

import net.chumbucket.sorekillteams.SorekillTeamsPlugin;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

public final class TeamChatCommand implements CommandExecutor {

    private final SorekillTeamsPlugin plugin;

    public TeamChatCommand(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            plugin.msg().send(sender, "player_only");
            return true;
        }
        if (!p.hasPermission("sorekillteams.chat")) {
            plugin.msg().send(p, "no_permission");
            return true;
        }

        // Must be in a team to toggle team chat
        if (plugin.teams().getTeamByPlayer(p.getUniqueId()).isEmpty()) {
            p.sendMessage(plugin.msg().prefix() + "You are not in a team.");
            return true;
        }

        boolean nowOn = plugin.teams().toggleTeamChat(p.getUniqueId());
        if (nowOn) {
            p.sendMessage(plugin.msg().prefix() + "Team chat: ON");
        } else {
            p.sendMessage(plugin.msg().prefix() + "Team chat: OFF");
        }
        return true;
    }
}
