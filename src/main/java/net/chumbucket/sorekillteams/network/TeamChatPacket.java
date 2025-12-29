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
 * Minimal, dependency-free wire format.
 *
 * Format (pipe-delimited, escaped):
 * v1|origin|teamId|senderUuid|senderName|coloredMessage|sentAtMs
 */
public final class TeamChatPacket {

    public static final String VERSION = "v1";

    private final String originServer;
    private final UUID teamId;
    private final UUID senderUuid;
    private final String senderName;
    private final String coloredMessage;
    private final long sentAtMs;

    public TeamChatPacket(String originServer,
                          UUID teamId,
                          UUID senderUuid,
                          String senderName,
                          String coloredMessage,
                          long sentAtMs) {
        this.originServer = Objects.requireNonNull(originServer, "originServer");
        this.teamId = Objects.requireNonNull(teamId, "teamId");
        this.senderUuid = Objects.requireNonNull(senderUuid, "senderUuid");
        this.senderName = (senderName == null ? "unknown" : senderName);
        this.coloredMessage = (coloredMessage == null ? "" : coloredMessage);
        this.sentAtMs = sentAtMs > 0 ? sentAtMs : System.currentTimeMillis();
    }

    public String originServer() { return originServer; }
    public UUID teamId() { return teamId; }
    public UUID senderUuid() { return senderUuid; }
    public String senderName() { return senderName; }
    public String coloredMessage() { return coloredMessage; }
    public long sentAtMs() { return sentAtMs; }

    public String encode() {
        return VERSION + "|" +
                esc(originServer) + "|" +
                teamId + "|" +
                senderUuid + "|" +
                esc(senderName) + "|" +
                esc(coloredMessage) + "|" +
                sentAtMs;
    }

    public static TeamChatPacket decode(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String[] parts = raw.split("\\|", 7);
        if (parts.length != 7) return null;
        if (!VERSION.equals(parts[0])) return null;

        String origin = unesc(parts[1]);
        UUID teamId = safeUuid(parts[2]);
        UUID sender = safeUuid(parts[3]);
        String senderName = unesc(parts[4]);
        String coloredMessage = unesc(parts[5]);
        long sentAt = safeLong(parts[6]);

        if (origin == null || origin.isBlank()) return null;
        if (teamId == null || sender == null) return null;

        return new TeamChatPacket(origin, teamId, sender, senderName, coloredMessage, sentAt);
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
