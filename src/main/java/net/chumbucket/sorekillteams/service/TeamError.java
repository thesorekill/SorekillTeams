/*
 * Copyright © 2025 Sorekill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillteams.service;

public enum TeamError {
    INVALID_PLAYER,
    INVALID_TEAM_NAME,
    TEAM_NAME_TAKEN,

    ALREADY_IN_TEAM,
    NOT_IN_TEAM,
    NOT_OWNER,
    ONLY_OWNER_CAN_INVITE,

    ALREADY_MEMBER,
    INVITEE_IN_TEAM,
    INVITE_SELF,

    // invites
    INVITE_ALREADY_PENDING,
    INVITE_TARGET_MAX_PENDING,     // 1.1.3: invites.max_pending_per_player
    INVITE_TEAM_MAX_OUTGOING,      // 1.1.3: invites.max_outgoing_per_team
    INVITE_ONLY_ONE_TEAM,          // 1.1.3: invites.allow_multiple_from_different_teams = false

    MULTIPLE_INVITES,
    INVITE_EXPIRED,
    TEAM_FULL,

    OWNER_CANNOT_LEAVE,

    INVITE_COOLDOWN,

    // kick / transfer
    TARGET_NOT_MEMBER,
    CANNOT_KICK_OWNER,
    TRANSFER_SELF,

    // rename
    RENAME_SELF_SAME_NAME,
    RENAME_NOT_OWNER
}
