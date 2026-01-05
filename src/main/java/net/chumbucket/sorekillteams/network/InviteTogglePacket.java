package net.chumbucket.sorekillteams.network;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class InviteTogglePacket {

    public static final String VERSION = "it1";

    private final String originServer;
    private final UUID playerUuid;
    private final String playerName;
    private final boolean enabled;
    private final long atMs;

    public InviteTogglePacket(String originServer, UUID playerUuid, String playerName, boolean enabled, long atMs) {
        this.originServer = originServer == null ? "default" : originServer;
        this.playerUuid = playerUuid;
        this.playerName = playerName == null ? "" : playerName;
        this.enabled = enabled;
        this.atMs = atMs > 0 ? atMs : System.currentTimeMillis();
    }

    public String originServer() { return originServer; }
    public UUID playerUuid() { return playerUuid; }
    public String playerName() { return playerName; }
    public boolean enabled() { return enabled; }
    public long atMs() { return atMs; }

    public String encode() {
        return VERSION + "|" +
                esc(originServer) + "|" +
                playerUuid + "|" +
                esc(playerName) + "|" +
                (enabled ? "1" : "0") + "|" +
                atMs;
    }

    public static InviteTogglePacket decode(String raw) {
        if (raw == null || raw.isBlank()) return null;

        // it1|origin|uuid|name|1|time
        String[] p = raw.split("\\|", 6);
        if (p.length != 6) return null;
        if (!VERSION.equals(p[0])) return null;

        String origin = unesc(p[1]);
        UUID uuid = safeUuid(p[2]);
        String name = unesc(p[3]);
        boolean enabled = "1".equals(p[4]) || "true".equalsIgnoreCase(p[4]);
        long at = safeLong(p[5]);

        if (origin == null || origin.isBlank() || uuid == null) return null;
        return new InviteTogglePacket(origin, uuid, name, enabled, at);
    }

    private static UUID safeUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s.trim()); } catch (Exception e) { return null; }
    }

    private static long safeLong(String s) {
        if (s == null || s.isBlank()) return 0L;
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0L; }
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
            if (esc) { sb.append(c); esc = false; }
            else if (c == '\\') esc = true;
            else sb.append(c);
        }
        if (esc) sb.append('\\');
        return sb.toString();
    }
}
