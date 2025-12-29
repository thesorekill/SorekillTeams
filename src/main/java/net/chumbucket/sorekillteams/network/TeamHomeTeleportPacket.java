package net.chumbucket.sorekillteams.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Cross-server team home teleport request.
 *
 * Format (pipe-delimited, escaped with backslash):
 * v1|origin|targetServer|teamId|homeKey|homeDisplay|playerUuid|playerName|requestId|atMs
 */
public final class TeamHomeTeleportPacket {

    public static final String VERSION = "v1";

    private final String originServer;
    private final String targetServer;

    private final UUID teamId;
    private final String homeKey;       // normalized key
    private final String homeDisplay;   // pretty display (best-effort)

    private final UUID playerUuid;
    private final String playerName;

    private final String requestId;     // random id for debugging/correlation
    private final long atMs;

    public TeamHomeTeleportPacket(String originServer,
                                  String targetServer,
                                  UUID teamId,
                                  String homeKey,
                                  String homeDisplay,
                                  UUID playerUuid,
                                  String playerName,
                                  String requestId,
                                  long atMs) {

        this.originServer = Objects.requireNonNull(originServer, "originServer");
        this.targetServer = Objects.requireNonNull(targetServer, "targetServer");

        this.teamId = Objects.requireNonNull(teamId, "teamId");
        this.homeKey = (homeKey == null ? "" : homeKey);
        this.homeDisplay = (homeDisplay == null ? "" : homeDisplay);

        this.playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
        this.playerName = (playerName == null ? "" : playerName);

        this.requestId = (requestId == null ? "" : requestId);
        this.atMs = atMs > 0 ? atMs : System.currentTimeMillis();
    }

    public String originServer() { return originServer; }
    public String targetServer() { return targetServer; }

    public UUID teamId() { return teamId; }
    public String homeKey() { return homeKey; }
    public String homeDisplay() { return homeDisplay; }

    public UUID playerUuid() { return playerUuid; }
    public String playerName() { return playerName; }

    public String requestId() { return requestId; }
    public long atMs() { return atMs; }

    public String encode() {
        return VERSION + "|" +
                esc(originServer) + "|" +
                esc(targetServer) + "|" +
                teamId + "|" +
                esc(homeKey) + "|" +
                esc(homeDisplay) + "|" +
                playerUuid + "|" +
                esc(playerName) + "|" +
                esc(requestId) + "|" +
                atMs;
    }

    public static TeamHomeTeleportPacket decode(String raw) {
        if (raw == null || raw.isBlank()) return null;

        List<String> parts = splitEscaped(raw, '|', 10);
        if (parts.size() != 10) return null;
        if (!VERSION.equals(parts.get(0))) return null;

        String origin = parts.get(1);
        String target = parts.get(2);

        UUID teamId = safeUuid(parts.get(3));
        String homeKey = parts.get(4);
        String homeDisplay = parts.get(5);

        UUID playerUuid = safeUuid(parts.get(6));
        String playerName = parts.get(7);

        String requestId = parts.get(8);
        long atMs = safeLong(parts.get(9));

        if (origin == null || origin.isBlank()) return null;
        if (target == null || target.isBlank()) return null;
        if (teamId == null || playerUuid == null) return null;

        return new TeamHomeTeleportPacket(origin, target, teamId, homeKey, homeDisplay, playerUuid, playerName, requestId, atMs);
    }

    private static String esc(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replace("\\", "\\\\").replace("|", "\\|");
    }

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

    private static List<String> splitEscaped(String raw, char delim, int expectedParts) {
        List<String> out = new ArrayList<>(expectedParts);
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
                continue;
            }

            cur.append(c);
        }

        out.add(unesc(cur.toString()));
        return out;
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
}
