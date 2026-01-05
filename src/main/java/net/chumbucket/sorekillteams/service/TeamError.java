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

/**
 * Canonical error codes thrown by {@link TeamServiceException} / returned by service layer.
 *
 * Keep these stable once released (they may map to message keys / API responses).
 */
public enum TeamError {

    // Generic validation
    INVALID_PLAYER,
    INVALID_TEAM_NAME,
    TEAM_NAME_TAKEN,

    // Team membership / ownership
    ALREADY_IN_TEAM,
    NOT_IN_TEAM,
    NOT_OWNER,
    ONLY_OWNER_CAN_INVITE,

    // Invite / member constraints
    ALREADY_MEMBER,
    INVITEE_IN_TEAM,
    INVITE_SELF,

    // Invites
    INVITE_ALREADY_PENDING,
    INVITE_TARGET_MAX_PENDING,     // invites.max_pending_per_player
    INVITE_TEAM_MAX_OUTGOING,      // invites.max_outgoing_per_team
    INVITE_ONLY_ONE_TEAM,          // invites.allow_multiple_from_different_teams = false
    MULTIPLE_INVITES,
    INVITE_EXPIRED,
    INVITE_COOLDOWN,
    INVITES_DISABLED,

    // Capacity / leaving
    TEAM_FULL,
    OWNER_CANNOT_LEAVE,

    // Kick / transfer / member ops
    TARGET_NOT_MEMBER,
    CANNOT_KICK_OWNER,
    TRANSFER_SELF,

    // Rename
    RENAME_SELF_SAME_NAME,
    RENAME_NOT_OWNER
}
