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
     * If the exception contains a messageKey, that always wins.
     */
    public static void send(Player p, SorekillTeamsPlugin plugin, TeamServiceException ex) {
        if (p == null || plugin == null || ex == null) return;

        if (ex.messageKey() != null && !ex.messageKey().isBlank()) {
            plugin.msg().send(p, ex.messageKey(), ex.pairs());
            return;
        }

        TeamError code = ex.code();
        if (code == null) {
            p.sendMessage(plugin.msg().prefix() + "You can't do that right now.");
            return;
        }

        switch (code) {
            case TEAM_FULL -> plugin.msg().send(p, "team_team_full");
            case INVITE_EXPIRED -> plugin.msg().send(p, "team_invite_expired");
            case MULTIPLE_INVITES -> plugin.msg().send(p, "team_multiple_invites_hint");
            case INVITE_ALREADY_PENDING -> plugin.msg().send(p, "team_invite_already_pending");

            case ALREADY_IN_TEAM -> plugin.msg().send(p, "team_already_in_team");
            case NOT_IN_TEAM -> plugin.msg().send(p, "team_not_in_team");
            case NOT_OWNER, ONLY_OWNER_CAN_INVITE -> plugin.msg().send(p, "team_not_owner");
            case ALREADY_MEMBER -> plugin.msg().send(p, "team_already_member");
            case INVITEE_IN_TEAM -> plugin.msg().send(p, "team_invitee_in_team");
            case INVITE_SELF -> plugin.msg().send(p, "team_invite_self");

            case TEAM_NAME_TAKEN -> plugin.msg().send(p, "team_name_taken");
            case INVALID_TEAM_NAME -> plugin.msg().send(p, "team_invalid_name");
            case INVALID_PLAYER -> plugin.msg().send(p, "invalid_player");

            case OWNER_CANNOT_LEAVE -> plugin.msg().send(p, "team_owner_cannot_leave");
            case INVITE_COOLDOWN -> plugin.msg().send(p, "team_invite_cooldown");

            // kick / transfer
            case TARGET_NOT_MEMBER -> plugin.msg().send(p, "team_target_not_member");
            case CANNOT_KICK_OWNER -> plugin.msg().send(p, "team_cannot_kick_owner");
            case TRANSFER_SELF -> plugin.msg().send(p, "team_transfer_self");

            default -> p.sendMessage(plugin.msg().prefix() + "You can't do that right now.");
        }
    }
}
