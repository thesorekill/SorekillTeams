package net.chumbucket.sorekillteams.command;

import net.chumbucket.sorekillteams.SorekillTeamsPlugin;
import net.chumbucket.sorekillteams.model.Team;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.regex.Pattern;

public final class TeamCommand implements CommandExecutor {

    private final SorekillTeamsPlugin plugin;

    public TeamCommand(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            plugin.msg().send(sender, "player_only");
            return true;
        }

        if (args.length == 0) {
            p.sendMessage(plugin.msg().prefix() + "Use: /team create|invite|accept|leave");
            return true;
        }

        String sub = args[0].toLowerCase();
        try {
            switch (sub) {
                case "create" -> {
                    if (!p.hasPermission("sorekillteams.create")) {
                        plugin.msg().send(p, "no_permission");
                        return true;
                    }
                    if (args.length < 2) {
                        p.sendMessage(plugin.msg().prefix() + "Usage: /team create <name>");
                        return true;
                    }

                    String name = args[1];
                    if (!validTeamName(name)) {
                        p.sendMessage(plugin.msg().prefix() + "Invalid team name.");
                        return true;
                    }

                    Team t = plugin.teams().createTeam(p.getUniqueId(), name);
                    p.sendMessage(plugin.msg().prefix() + "Created team: " + t.getName());
                    return true;
                }

                case "invite" -> {
                    if (!p.hasPermission("sorekillteams.invite")) {
                        plugin.msg().send(p, "no_permission");
                        return true;
                    }
                    if (args.length < 2) {
                        p.sendMessage(plugin.msg().prefix() + "Usage: /team invite <player>");
                        return true;
                    }

                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        p.sendMessage(plugin.msg().prefix() + "That player is not online.");
                        return true;
                    }
                    if (target.getUniqueId().equals(p.getUniqueId())) {
                        p.sendMessage(plugin.msg().prefix() + "You can't invite yourself.");
                        return true;
                    }

                    plugin.teams().invite(p.getUniqueId(), target.getUniqueId());
                    p.sendMessage(plugin.msg().prefix() + "Invited " + target.getName() + " to your team.");
                    target.sendMessage(plugin.msg().prefix() + p.getName() + " invited you to their team. Type /team accept");
                    return true;
                }

                case "accept" -> {
                    if (!p.hasPermission("sorekillteams.accept")) {
                        plugin.msg().send(p, "no_permission");
                        return true;
                    }
                    boolean ok = plugin.teams().acceptInvite(p.getUniqueId());
                    if (!ok) {
                        p.sendMessage(plugin.msg().prefix() + "You have no pending team invites.");
                        return true;
                    }
                    p.sendMessage(plugin.msg().prefix() + "You joined the team.");
                    return true;
                }

                case "leave" -> {
                    if (!p.hasPermission("sorekillteams.leave")) {
                        plugin.msg().send(p, "no_permission");
                        return true;
                    }
                    plugin.teams().leaveTeam(p.getUniqueId());
                    p.sendMessage(plugin.msg().prefix() + "You left your team.");
                    return true;
                }

                default -> {
                    p.sendMessage(plugin.msg().prefix() + "Unknown subcommand. Use: /team create|invite|accept|leave");
                    return true;
                }
            }
        } catch (IllegalStateException ex) {
            p.sendMessage(plugin.msg().prefix() + ex.getMessage());
            return true;
        } catch (Exception ex) {
            p.sendMessage(plugin.msg().prefix() + "An error occurred.");
            plugin.getLogger().severe("Command error: " + ex.getMessage());
            return true;
        }
    }

    private boolean validTeamName(String name) {
        int min = plugin.getConfig().getInt("teams.name.min_length", 3);
        int max = plugin.getConfig().getInt("teams.name.max_length", 16);
        String allowed = plugin.getConfig().getString("teams.name.allowed", "a-zA-Z0-9_");
        Pattern pattern = Pattern.compile("^[" + allowed + "]{" + min + "," + max + "}$");
        return pattern.matcher(name).matches();
    }
}
