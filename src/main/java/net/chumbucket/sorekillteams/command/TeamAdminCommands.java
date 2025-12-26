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
import org.bukkit.entity.Player;

public final class TeamAdminCommands implements TeamSubcommandModule {

    private final SorekillTeamsPlugin plugin;

    public TeamAdminCommands(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean handle(Player p, String sub, String[] args, boolean debug) {
        if (!sub.equals("reload")) return false;

        if (!p.hasPermission("sorekillteams.reload") && !p.hasPermission("sorekillteams.admin")) {
            plugin.msg().send(p, "no_permission");
            return true;
        }

        plugin.reloadEverything();
        plugin.msg().send(p, "plugin_reloaded");

        if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " ran /team reload");
        return true;
    }
}
