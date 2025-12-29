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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Minimal wire format for cross-server team events.
 *
 * Format (pipe-delimited, escaped with backslash):
 * v1|origin|type|teamId|teamName|actorUuid|actorName|targetUuid|targetName|atMs
 *
 * Escape rules:
 * - '\' escapes the next char
 * - '|' inside fields is encoded as '\|'
 * - '\' inside fields is encoded as '\\'
 */
public final class TeamEventPacket {

    public static final String VERSION = "v1";
    private static final int PARTS = 10;

    public enum Type {
        MEMBER_JOINED,
        MEMBER_LEFT,
        MEMBER_KICKED,
        TEAM_DISBANDED,
        TEAM_RENAMED,
        OWNER_TRANSFERRED
    }

    private final String originServer;
    private final Type type;

    private final UUID teamId;
    private final String teamName;

    private final UUID actorUuid;
    private final String actorName;

    private final UUID targetUuid; // nullable
    private final String targetName;

    private final long atMs;

    public TeamEventPacket(String originServer,
                           Type type,
                           UUID teamId,
                           String teamName,
                           UUID actorUuid,
                           String actorName,
                           UUID targetUuid,
                           String targetName,
                           long atMs) {

        this.originServer = Objects.requireNonNull(originServer, "originServer");
        this.type = Objects.requireNonNull(type, "type");

        this.teamId = Objects.requireNonNull(teamId, "teamId");
        this.teamName = (teamName == null ? "" : teamName);

        this.actorUuid = Objects.requireNonNull(actorUuid, "actorUuid");
        this.actorName = (actorName == null ? "" : actorName);

        this.targetUuid = targetUuid;
        this.targetName = (targetName == null ? "" : targetName);

        this.atMs = atMs > 0 ? atMs : System.currentTimeMillis();
    }

    public String originServer() { return originServer; }
    public Type type() { return type; }

    public UUID teamId() { return teamId; }
    public String teamName() { return teamName; }

    public UUID actorUuid() { return actorUuid; }
    public String actorName() { return actorName; }

    public UUID targetUuid() { return targetUuid; }
    public String targetName() { return targetName; }

    public long atMs() { return atMs; }

    public String encode() {
        // Keep exact field order/shape.
        return VERSION + "|" +
                esc(originServer) + "|" +
                type.name() + "|" +
                teamId + "|" +
                esc(teamName) + "|" +
                actorUuid + "|" +
                esc(actorName) + "|" +
                (targetUuid == null ? "" : targetUuid.toString()) + "|" +
                esc(targetName) + "|" +
                atMs;
    }

    public static TeamEventPacket decode(String raw) {
        if (raw == null || raw.isBlank()) return null;

        // FIX: enforce exact part count so garbage suffix/prefix doesn't silently parse wrong.
        List<String> parts = splitEscaped(raw, '|', PARTS);
        if (parts.size() != PARTS) return null;

        if (!VERSION.equals(parts.get(0))) return null;

        String origin = parts.get(1);
        if (origin == null || origin.isBlank()) return null;

        Type type;
        try {
            type = Type.valueOf(parts.get(2));
        } catch (Exception ignored) {
            return null;
        }

        UUID teamId = safeUuid(parts.get(3));
        if (teamId == null) return null;

        String teamName = parts.get(4);

        UUID actorUuid = safeUuid(parts.get(5));
        if (actorUuid == null) return null;

        String actorName = parts.get(6);

        UUID targetUuid = safeUuidOrNull(parts.get(7));
        String targetName = parts.get(8);

        long atMs = safeLong(parts.get(9));

        return new TeamEventPacket(origin, type, teamId, teamName, actorUuid, actorName, targetUuid, targetName, atMs);
    }

    // -------------------------
    // Escaping
    // -------------------------

    private static String esc(String s) {
        if (s == null || s.isEmpty()) return "";
        // escape backslash first, then pipe
        return s.replace("\\", "\\\\").replace("|", "\\|");
    }

    /**
     * Unescape a single field.
     * Rules:
     * - '\' escapes the next char (whatever it is)
     * - trailing '\' is preserved literally
     */
    private static String unesc(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length());
        boolean escaping = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaping) {
                sb.append(c);
                escaping = false;
            } else if (c == '\\') {
                escaping = true;
            } else {
                sb.append(c);
            }
        }
        if (escaping) sb.append('\\');
        return sb.toString();
    }

    /**
     * Split a string by a delimiter char, honoring backslash escapes.
     * Returns unescaped fields.
     *
     * FIX: previously this ignored expectedParts and would happily return 11+ parts.
     * Now we enforce exact part count:
     * - If we exceed expectedParts, return empty list (decode fails).
     * - If we end with fewer than expectedParts, return what we have (decode fails).
     */
    private static List<String> splitEscaped(String raw, char delim, int expectedParts) {
        List<String> out = new ArrayList<>(Math.max(1, expectedParts));

        StringBuilder cur = new StringBuilder(raw.length());
        boolean escaping = false;

        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);

            if (escaping) {
                cur.append(c);
                escaping = false;
                continue;
            }

            if (c == '\\') {
                escaping = true;
                continue;
            }

            if (c == delim) {
                out.add(unesc(cur.toString()));
                cur.setLength(0);

                // too many parts -> bail early
                if (expectedParts > 0 && out.size() > expectedParts) {
                    return List.of();
                }
                continue;
            }

            cur.append(c);
        }

        out.add(unesc(cur.toString()));

        if (expectedParts > 0 && out.size() != expectedParts) {
            // Let caller decide; decode checks size == expectedParts anyway.
            return out;
        }

        return out;
    }

    // -------------------------
    // Safe parsing
    // -------------------------

    private static UUID safeUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s.trim()); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static UUID safeUuidOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s.trim()); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static long safeLong(String s) {
        if (s == null || s.isBlank()) return 0L;
        try { return Long.parseLong(s.trim()); }
        catch (NumberFormatException e) { return 0L; }
    }
}
