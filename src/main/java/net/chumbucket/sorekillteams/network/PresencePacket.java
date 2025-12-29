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
 * v1|origin|type|playerUuid|playerName|serverId|atMs
 */
public final class PresencePacket {

    public static final String VERSION = "v1";

    public enum Type {
        ONLINE,
        OFFLINE
    }

    private final String originServer;
    private final Type type;

    private final UUID playerUuid;
    private final String playerName;

    private final String serverId;
    private final long atMs;

    public PresencePacket(String originServer,
                          Type type,
                          UUID playerUuid,
                          String playerName,
                          String serverId,
                          long atMs) {

        this.originServer = Objects.requireNonNull(originServer, "originServer");
        this.type = Objects.requireNonNull(type, "type");

        this.playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
        this.playerName = (playerName == null ? "" : playerName);

        this.serverId = (serverId == null ? "" : serverId);
        this.atMs = atMs > 0 ? atMs : System.currentTimeMillis();
    }

    public String originServer() { return originServer; }
    public Type type() { return type; }

    public UUID playerUuid() { return playerUuid; }
    public String playerName() { return playerName; }

    public String serverId() { return serverId; }
    public long atMs() { return atMs; }

    public String encode() {
        return VERSION + "|" +
                esc(originServer) + "|" +
                type.name() + "|" +
                playerUuid + "|" +
                esc(playerName) + "|" +
                esc(serverId) + "|" +
                atMs;
    }

    public static PresencePacket decode(String raw) {
        if (raw == null || raw.isBlank()) return null;

        String[] parts = raw.split("\\|", 7);
        if (parts.length != 7) return null;
        if (!VERSION.equals(parts[0])) return null;

        String origin = unesc(parts[1]);
        Type type;
        try { type = Type.valueOf(parts[2]); }
        catch (Exception e) { return null; }

        UUID uuid = safeUuid(parts[3]);
        String name = unesc(parts[4]);
        String serverId = unesc(parts[5]);
        long atMs = safeLong(parts[6]);

        if (origin == null || origin.isBlank()) return null;
        if (uuid == null) return null;

        return new PresencePacket(origin, type, uuid, name, serverId, atMs);
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
