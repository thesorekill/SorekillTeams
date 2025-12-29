/*
 * Copyright © 2025 Sorekill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillteams.network;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Minimal wire format for cross-server invites.
 *
 * Format (pipe-delimited, escaped):
 * v1|origin|type|teamId|teamName|inviterUuid|inviterName|inviteeUuid|inviteeName|createdAtMs|expiresAtMs
 */
public final class InvitePacket {

    public static final String VERSION = "v1";

    /** Matches your plugin switch(pkt.type()) cases */
    public enum Type {
        SENT,
        ACCEPTED,
        DENIED,
        EXPIRED,
        CANCELLED
    }

    private final String originServer;
    private final Type type;

    private final UUID teamId;
    private final String teamName;

    private final UUID inviterUuid;
    private final String inviterName;

    private final UUID inviteeUuid;
    private final String inviteeName;

    private final long createdAtMs;
    private final long expiresAtMs;

    public InvitePacket(String originServer,
                        Type type,
                        UUID teamId,
                        String teamName,
                        UUID inviterUuid,
                        String inviterName,
                        UUID inviteeUuid,
                        String inviteeName,
                        long createdAtMs,
                        long expiresAtMs) {

        this.originServer = Objects.requireNonNull(originServer, "originServer");
        this.type = Objects.requireNonNull(type, "type");

        this.teamId = Objects.requireNonNull(teamId, "teamId");
        this.teamName = (teamName == null ? "" : teamName);

        this.inviterUuid = Objects.requireNonNull(inviterUuid, "inviterUuid");
        this.inviterName = (inviterName == null ? "" : inviterName);

        this.inviteeUuid = Objects.requireNonNull(inviteeUuid, "inviteeUuid");
        this.inviteeName = (inviteeName == null ? "" : inviteeName);

        long now = System.currentTimeMillis();
        this.createdAtMs = createdAtMs > 0 ? createdAtMs : now;
        this.expiresAtMs = expiresAtMs > 0 ? expiresAtMs : (this.createdAtMs + 1L);
    }

    public String originServer() { return originServer; }
    public Type type() { return type; }

    public UUID teamId() { return teamId; }
    public String teamName() { return teamName; }

    public UUID inviterUuid() { return inviterUuid; }
    public String inviterName() { return inviterName; }

    public UUID inviteeUuid() { return inviteeUuid; }
    public String inviteeName() { return inviteeName; }

    public long createdAtMs() { return createdAtMs; }
    public long expiresAtMs() { return expiresAtMs; }

    public String inviteeNameFallback() {
        if (inviteeName != null && !inviteeName.isBlank()) return inviteeName;
        if (inviteeUuid != null) return inviteeUuid.toString().substring(0, 8);
        return "unknown";
    }

    public String inviterNameFallback() {
        if (inviterName != null && !inviterName.isBlank()) return inviterName;
        if (inviterUuid != null) return inviterUuid.toString().substring(0, 8);
        return "unknown";
    }

    public String encode() {
        return VERSION + "|" +
                esc(originServer) + "|" +
                type.name() + "|" +
                teamId + "|" +
                esc(teamName) + "|" +
                inviterUuid + "|" +
                esc(inviterName) + "|" +
                inviteeUuid + "|" +
                esc(inviteeName) + "|" +
                createdAtMs + "|" +
                expiresAtMs;
    }

    public static InvitePacket decode(String raw) {
        if (raw == null || raw.isBlank()) return null;

        String[] parts = raw.split("\\|", 11);
        if (parts.length != 11) return null;
        if (!VERSION.equals(parts[0])) return null;

        String origin = unesc(parts[1]);
        Type type;
        try { type = Type.valueOf(parts[2]); }
        catch (Exception e) { return null; }

        UUID teamId = safeUuid(parts[3]);
        String teamName = unesc(parts[4]);

        UUID inviterUuid = safeUuid(parts[5]);
        String inviterName = unesc(parts[6]);

        UUID inviteeUuid = safeUuid(parts[7]);
        String inviteeName = unesc(parts[8]);

        long createdAt = safeLong(parts[9]);
        long expiresAt = safeLong(parts[10]);

        if (origin == null || origin.isBlank()) return null;
        if (teamId == null || inviterUuid == null || inviteeUuid == null) return null;

        return new InvitePacket(origin, type, teamId, teamName, inviterUuid, inviterName, inviteeUuid, inviteeName, createdAt, expiresAt);
    }

    private static UUID safeUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s.trim()); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static long safeLong(String s) {
        if (s == null || s.isBlank()) return 0L;
        try { return Long.parseLong(s.trim()); }
        catch (NumberFormatException e) { return 0L; }
    }

    private static String esc(String s) {
        if (s == null) return "";
        String out = s.replace("\\", "\\\\").replace("|", "\\|");
        return new String(out.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private static String unesc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) {
                sb.append(c);
                esc = false;
            } else if (c == '\\') {
                esc = true;
            } else {
                sb.append(c);
            }
        }
        if (esc) sb.append('\\');
        return sb.toString();
    }
}
