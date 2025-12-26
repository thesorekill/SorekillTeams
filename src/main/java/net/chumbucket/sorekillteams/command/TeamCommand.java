package net.chumbucket.sorekillteams.command;

import net.chumbucket.sorekillteams.SorekillTeamsPlugin;
import net.chumbucket.sorekillteams.service.TeamServiceException;
import net.chumbucket.sorekillteams.util.CommandErrors;
import net.chumbucket.sorekillteams.util.TeamHomeCooldowns;
import net.chumbucket.sorekillteams.util.TeamHomeWarmupManager;
import net.chumbucket.sorekillteams.util.TeamNameValidator;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class TeamCommand implements CommandExecutor {

    private final SorekillTeamsPlugin plugin;

    // Shared deps
    private final TeamNameValidator nameValidator;

    // Homes deps
    private final TeamHomeCooldowns homeCooldowns;
    private final TeamHomeWarmupManager homeWarmups;

    // Modules list
    private final List<TeamSubcommandModule> modules = new ArrayList<>();

    public TeamCommand(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;

        this.nameValidator = new TeamNameValidator(plugin);

        this.homeCooldowns = new TeamHomeCooldowns(plugin);
        this.homeWarmups = new TeamHomeWarmupManager(plugin);
        Bukkit.getPluginManager().registerEvents(homeWarmups, plugin);

        // Register modules in the order you want them evaluated
        modules.add(new TeamMenuCommands(plugin));
        modules.add(new TeamAdminCommands(plugin));
        modules.add(new TeamChatCommands(plugin));
        modules.add(new TeamSpyCommands(plugin));
        modules.add(new TeamHomeCommands(plugin, homeCooldowns, homeWarmups));
        modules.add(new TeamCreateCommands(plugin, nameValidator));
        modules.add(new TeamInviteCommands(plugin));
        modules.add(new TeamMembershipCommands(plugin, nameValidator));
        modules.add(new TeamInfoCommands(plugin));
        modules.add(new TeamFriendlyFireCommands(plugin));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            plugin.msg().send(sender, "player_only");
            return true;
        }

        if (!p.hasPermission("sorekillteams.use")) {
            plugin.msg().send(p, "no_permission");
            return true;
        }

        final boolean menusEnabled = plugin.getConfig().getBoolean("menus.enabled", true);
        final boolean openOnNoArgs = plugin.getConfig().getBoolean("menus.open_on_team_command_no_args", true);

        // ✅ NEW: /team with no args opens team info if you're in a team
        if (args.length == 0) {
            if (menusEnabled && openOnNoArgs && plugin.menuRouter() != null) {
                boolean inTeam = plugin.teams().getTeamByPlayer(p.getUniqueId()).isPresent();

                if (inTeam) {
                    plugin.menuRouter().open(p, "team_info");
                } else {
                    plugin.menuRouter().open(p, "main");
                }
                return true;
            }

            plugin.msg().send(p, "team_usage");
            return true;
        }

        final boolean debug = plugin.debug() != null && plugin.debug().enabled();
        final String sub = args[0].toLowerCase(Locale.ROOT);

        try {
            for (TeamSubcommandModule m : modules) {
                if (m.handle(p, sub, args, debug)) {
                    return true;
                }
            }

            plugin.msg().send(p, "unknown_command");
            plugin.msg().send(p, "team_usage");
            return true;

        } catch (TeamServiceException ex) {
            CommandErrors.send(p, plugin, ex);

            if (debug) {
                plugin.getLogger().info("[TEAM-DBG] TeamServiceException sub=" + sub + " player=" + p.getName() + " code=" +
                        (ex.code() == null ? "null" : ex.code().name()));
            }
            return true;

        } catch (Exception ex) {
            p.sendMessage(plugin.msg().prefix() + "An error occurred.");
            plugin.getLogger().severe("Command error (" + sub + "): " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return true;
        }
    }
}
