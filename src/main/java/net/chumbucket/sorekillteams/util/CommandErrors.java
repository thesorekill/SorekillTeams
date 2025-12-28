/*
 * Copyright © 2025 Sorekill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillteams.util;

import net.chumbucket.sorekillteams.SorekillTeamsPlugin;
import net.chumbucket.sorekillteams.service.TeamError;
import net.chumbucket.sorekillteams.service.TeamServiceException;
import org.bukkit.entity.Player;

public final class CommandErrors {

    private CommandErrors() {}

    /**
     * Centralized TeamServiceException -> messages.yml output mapping.
     *
     * Priority:
     * 1) If the exception has a messageKey, use it (and any placeholder pairs).
     * 2) Else map the TeamError to a messages.yml key.
     * 3) Else send a safe fallback.
     */
    public static void send(Player p, SorekillTeamsPlugin plugin, TeamServiceException ex) {
        if (p == null || plugin == null || ex == null) return;

        // 1) Explicit message key always wins
        String mk = ex.messageKey();
        if (mk != null && !mk.isBlank()) {
            plugin.msg().send(p, mk, safePairs(ex.pairs()));
            return;
        }

        // 2) Map code -> key
        TeamError code = ex.code();
        if (code == null) {
            sendFallback(p, plugin);
            return;
        }

        switch (code) {
            // team state
            case TEAM_FULL -> plugin.msg().send(p, "team_team_full");
            case ALREADY_IN_TEAM -> plugin.msg().send(p, "team_already_in_team");
            case NOT_IN_TEAM -> plugin.msg().send(p, "team_not_in_team");
            case OWNER_CANNOT_LEAVE -> plugin.msg().send(p, "team_owner_cannot_leave");

            // ownership/permissions
            case NOT_OWNER, ONLY_OWNER_CAN_INVITE -> plugin.msg().send(p, "team_not_owner");

            // membership / invite targets
            case ALREADY_MEMBER -> plugin.msg().send(p, "team_already_member");
            case INVITEE_IN_TEAM -> plugin.msg().send(p, "team_invitee_in_team");
            case INVITE_SELF -> plugin.msg().send(p, "team_invite_self");

            // invites
            case INVITE_EXPIRED -> plugin.msg().send(p, "team_invite_expired");
            case MULTIPLE_INVITES -> plugin.msg().send(p, "team_multiple_invites_hint");
            case INVITE_ALREADY_PENDING -> plugin.msg().send(p, "team_invite_already_pending");
            case INVITE_COOLDOWN -> plugin.msg().send(p, "team_invite_cooldown");
            case INVITE_TARGET_MAX_PENDING -> plugin.msg().send(p, "team_invite_target_max_pending");
            case INVITE_TEAM_MAX_OUTGOING -> plugin.msg().send(p, "team_invite_team_max_outgoing");
            case INVITE_ONLY_ONE_TEAM -> plugin.msg().send(p, "team_invite_only_one_team");

            // naming
            case TEAM_NAME_TAKEN -> plugin.msg().send(p, "team_name_taken");
            case INVALID_TEAM_NAME -> plugin.msg().send(p, "team_invalid_name");

            // invalid inputs
            case INVALID_PLAYER -> plugin.msg().send(p, "invalid_player");

            // kick / transfer
            case TARGET_NOT_MEMBER -> plugin.msg().send(p, "team_target_not_member");
            case CANNOT_KICK_OWNER -> plugin.msg().send(p, "team_cannot_kick_owner");
            case TRANSFER_SELF -> plugin.msg().send(p, "team_transfer_self");

            // These exist in the enum but currently aren't thrown by your service methods.
            // Keeping them mapped avoids future “default fallback” surprises.
            case RENAME_NOT_OWNER -> plugin.msg().send(p, "team_not_owner");
            case RENAME_SELF_SAME_NAME -> {
                // If you add a messages.yml key later, swap this:
                // plugin.msg().send(p, "team_rename_same_name");
                sendFallback(p, plugin);
            }

            default -> sendFallback(p, plugin);
        }
    }

    private static void sendFallback(Player p, SorekillTeamsPlugin plugin) {
        // If you decide to add this key to messages.yml, you can switch to it:
        // plugin.msg().send(p, FALLBACK_KEY);
        p.sendMessage(plugin.msg().prefix() + "You can't do that right now.");
    }

    private static String[] safePairs(String[] pairs) {
        return (pairs == null) ? new String[0] : pairs;
    }
}
