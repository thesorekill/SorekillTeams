/*
 * Copyright © 2025 Sorekill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillteams.command;

import net.chumbucket.sorekillteams.SorekillTeamsPlugin;
import net.chumbucket.sorekillteams.model.Team;
import org.bukkit.entity.Player;

public final class TeamInfoCommands implements TeamSubcommandModule {

    private final SorekillTeamsPlugin plugin;

    public TeamInfoCommands(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean handle(Player p, String sub, String[] args, boolean debug) {
        if (!sub.equalsIgnoreCase("info")) return false;

        if (!p.hasPermission("sorekillteams.info")) {
            plugin.msg().send(p, "no_permission");
            return true;
        }

        // Must be in a team to view team info menu
        Team t = plugin.teams().getTeamByPlayer(p.getUniqueId()).orElse(null);
        if (t == null) {
            plugin.msg().send(p, "team_not_in_team");
            return true;
        }

        final boolean menusEnabled = plugin.getConfig().getBoolean("menus.enabled", true);
        if (!menusEnabled || plugin.menuRouter() == null) {
            // fallback: if menus disabled, keep old behavior off for now
            plugin.msg().send(p, "unknown_command");
            plugin.msg().send(p, "team_usage");
            return true;
        }

        plugin.menuRouter().open(p, "team_info");
        return true;
    }
}
