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
import net.chumbucket.sorekillteams.util.Msg;
import org.bukkit.entity.Player;

import java.util.Locale;

public final class TeamFriendlyFireCommands implements TeamSubcommandModule {

    private final SorekillTeamsPlugin plugin;

    public TeamFriendlyFireCommands(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean handle(Player p, String sub, String[] args, boolean debug) {
        if (!(sub.equals("ff") || sub.equals("friendlyfire"))) return false;

        if (!p.hasPermission("sorekillteams.ff")) {
            plugin.msg().send(p, "no_permission");
            return true;
        }

        Team t = plugin.teams().getTeamByPlayer(p.getUniqueId()).orElse(null);
        if (t == null) {
            plugin.msg().send(p, "team_not_in_team");
            return true;
        }

        // /team ff  OR  /team ff status
        if (args.length < 2 || args[1].equalsIgnoreCase("status")) {
            String state = t.isFriendlyFireEnabled() ? "&aON" : "&cOFF";
            plugin.msg().send(p, "team_ff_status", "{state}", Msg.color(state));
            plugin.msg().send(p, "team_ff_usage");
            return true;
        }

        // Anything beyond status is a toggle/change attempt -> respect config
        if (!plugin.isFriendlyFireToggleEnabled()) {
            plugin.msg().send(p, "team_ff_toggle_disabled");
            return true;
        }

        if (!t.getOwner().equals(p.getUniqueId())) {
            plugin.msg().send(p, "team_not_owner");
            return true;
        }

        String mode = args[1].toLowerCase(Locale.ROOT);
        boolean newValue;

        switch (mode) {
            case "on", "true", "enable", "enabled" -> newValue = true;
            case "off", "false", "disable", "disabled" -> newValue = false;
            case "toggle" -> newValue = !t.isFriendlyFireEnabled();
            default -> {
                plugin.msg().send(p, "team_ff_usage");
                return true;
            }
        }

        t.setFriendlyFireEnabled(newValue);

        try {
            if (plugin.storage() != null && plugin.teams() != null) {
                plugin.storage().saveAll(plugin.teams());
            }
        } catch (Exception ignored) {}

        plugin.msg().send(p, newValue ? "team_ff_status_on" : "team_ff_status_off");

        if (debug) plugin.getLogger().info("[TEAM-DBG] " + p.getName() + " ff -> " + (newValue ? "ON" : "OFF"));
        return true;
    }
}
