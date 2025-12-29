/*
 * Copyright © 2025 Sorekill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillteams.storage.sql;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public final class SqlMigrations {

    private SqlMigrations() {}

    public static void migrate(Connection c, String pfx) throws SQLException {
        try (Statement st = c.createStatement()) {

            st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS " + pfx + "teams (" +
                            "id VARCHAR(36) PRIMARY KEY," +
                            "name VARCHAR(32) NOT NULL," +
                            "owner_uuid VARCHAR(36) NOT NULL," +
                            "friendly_fire BOOLEAN NOT NULL DEFAULT FALSE," +
                            "created_at BIGINT NOT NULL" +
                            ")"
            );

            st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS " + pfx + "team_members (" +
                            "team_id VARCHAR(36) NOT NULL," +
                            "member_uuid VARCHAR(36) NOT NULL," +
                            "PRIMARY KEY (team_id, member_uuid)" +
                            ")"
            );

            st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS " + pfx + "team_homes (" +
                            "team_id VARCHAR(36) NOT NULL," +
                            "name VARCHAR(64) NOT NULL," +
                            "display_name VARCHAR(64) NOT NULL," +
                            "world VARCHAR(64) NOT NULL," +
                            "x DOUBLE NOT NULL," +
                            "y DOUBLE NOT NULL," +
                            "z DOUBLE NOT NULL," +
                            "yaw FLOAT NOT NULL," +
                            "pitch FLOAT NOT NULL," +
                            "created_at BIGINT NOT NULL," +
                            "created_by VARCHAR(36)," +
                            "server_name VARCHAR(64) NOT NULL," +
                            "PRIMARY KEY (team_id, name)" +
                            ")"
            );

            // =========================================================
            // ✅ Invites (cross-server correctness)
            // Source of truth when storage.type != yaml
            // =========================================================
            st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS " + pfx + "invites (" +
                            "invitee_uuid VARCHAR(36) NOT NULL," +
                            "team_id VARCHAR(36) NOT NULL," +
                            "inviter_uuid VARCHAR(36) NOT NULL," +
                            "created_at_ms BIGINT NOT NULL," +
                            "expires_at_ms BIGINT NOT NULL," +
                            "PRIMARY KEY (invitee_uuid, team_id)" +
                            ")"
            );

            // Optional indexes (best-effort; some dialects may not support IF NOT EXISTS)
            try {
                st.executeUpdate("CREATE INDEX IF NOT EXISTS " + pfx + "invites_invitee_expires_idx ON " + pfx + "invites (invitee_uuid, expires_at_ms)");
            } catch (Exception ignored) {}

            try {
                st.executeUpdate("CREATE INDEX IF NOT EXISTS " + pfx + "invites_team_expires_idx ON " + pfx + "invites (team_id, expires_at_ms)");
            } catch (Exception ignored) {}
        }
    }
}
