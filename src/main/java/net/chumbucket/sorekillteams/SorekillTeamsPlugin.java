package net.chumbucket.sorekillteams;

import net.chumbucket.sorekillteams.command.AdminCommand;
import net.chumbucket.sorekillteams.command.TeamChatCommand;
import net.chumbucket.sorekillteams.command.TeamCommand;
import net.chumbucket.sorekillteams.listener.FriendlyFireListener;
import net.chumbucket.sorekillteams.service.SimpleTeamService;
import net.chumbucket.sorekillteams.service.TeamService;
import net.chumbucket.sorekillteams.storage.TeamStorage;
import net.chumbucket.sorekillteams.storage.YamlTeamStorage;
import net.chumbucket.sorekillteams.util.Msg;
import org.bukkit.plugin.java.JavaPlugin;

public final class SorekillTeamsPlugin extends JavaPlugin {

    private Msg msg;
    private TeamStorage storage;
    private TeamService teams;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResourceIfMissing(getMessagesFileName());

        this.msg = new Msg(this);
        this.storage = new YamlTeamStorage(this);
        this.teams = new SimpleTeamService(this, storage);

        storage.loadAll(teams);

        // commands
        getCommand("sorekillteams").setExecutor(new AdminCommand(this));
        getCommand("team").setExecutor(new TeamCommand(this));
        getCommand("tc").setExecutor(new TeamChatCommand(this));

        // listeners
        getServer().getPluginManager().registerEvents(new FriendlyFireListener(this), this);

        getLogger().info("SorekillTeams enabled.");
    }

    @Override
    public void onDisable() {
        try {
            storage.saveAll(teams);
        } catch (Exception e) {
            getLogger().severe("Failed to save teams: " + e.getMessage());
        }
        getLogger().info("SorekillTeams disabled.");
    }

    public void reloadEverything() {
        reloadConfig();
        saveResourceIfMissing(getMessagesFileName());
        msg.reload();
    }

    private void saveResourceIfMissing(String resourceName) {
        // Only saves defaults if the file doesn't exist yet
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        var file = new java.io.File(getDataFolder(), resourceName);
        if (!file.exists()) saveResource(resourceName, false);
    }

    public String getMessagesFileName() {
        return getConfig().getString("files.messages", "messages.yml");
    }

    public Msg msg() { return msg; }
    public TeamService teams() { return teams; }
    public TeamStorage storage() { return storage; }
}
