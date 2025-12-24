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

import net.chumbucket.sorekillteams.model.TeamHome;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TeamHomeService {

    /* ------------------------------------------------------------------------
     * Storage helpers
     * --------------------------------------------------------------------- */

    void putLoadedHome(TeamHome home);

    void clearAll();

    Collection<TeamHome> allHomes();

    /* ------------------------------------------------------------------------
     * Team-facing
     * --------------------------------------------------------------------- */

    List<TeamHome> listHomes(UUID teamId);

    Optional<TeamHome> getHome(UUID teamId, String name);

    /**
     * Create or update a home for a team.
     *
     * @param home     Home to create/update
     * @param maxHomes Maximum homes allowed for this team (must be >= 1)
     * @return true if saved; false if refused because the team is at max homes
     *         (and the name did not already exist)
     */
    boolean setHome(TeamHome home, int maxHomes);

    boolean deleteHome(UUID teamId, String name);

    void clearTeam(UUID teamId);

    /* ------------------------------------------------------------------------
     * Cooldown tracking
     * --------------------------------------------------------------------- */

    long getLastTeleportMs(UUID teamId);

    void setLastTeleportMs(UUID teamId, long ms);
}
