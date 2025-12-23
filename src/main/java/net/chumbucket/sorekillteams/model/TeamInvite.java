package net.chumbucket.sorekillteams.model;

import java.util.UUID;

public record TeamInvite(UUID teamId, UUID inviter, long expiresAtMs) {
    public boolean expired(long nowMs) { return nowMs > expiresAtMs; }
}
