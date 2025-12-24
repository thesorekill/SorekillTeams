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

    // storage helpers
    void putLoadedHome(TeamHome home);
    void clearAll();
    Collection<TeamHome> allHomes();

    // team-facing
    List<TeamHome> listHomes(UUID teamId);
    Optional<TeamHome> getHome(UUID teamId, String name);

    /**
     * Create or update a home for a team.
     * @return true if saved; false if refused because team is at max homes (and name didn't already exist)
     */
    boolean setHome(TeamHome home, int maxHomes);

    boolean deleteHome(UUID teamId, String name);

    void clearTeam(UUID teamId);

    // cooldown
    long getLastTeleportMs(UUID teamId);
    void setLastTeleportMs(UUID teamId, long ms);
}
