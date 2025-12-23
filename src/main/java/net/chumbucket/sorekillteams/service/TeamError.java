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
    INVITE_ALREADY_PENDING,

    MULTIPLE_INVITES,
    INVITE_EXPIRED,
    TEAM_FULL,

    OWNER_CANNOT_LEAVE,

    INVITE_COOLDOWN,

    // 1.0.6 additions (kick / transfer)
    TARGET_NOT_MEMBER,
    CANNOT_KICK_OWNER,
    TRANSFER_SELF,

    // 1.0.7 additions
    RENAME_SELF_SAME_NAME,
    RENAME_NOT_OWNER
}