/*
 * Copyright © 2025 Sorekill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillteams.storage;

import net.chumbucket.sorekillteams.service.TeamService;

/**
 * Persistence contract for {@link TeamService}.
 * <p>
 * Implementations are responsible for loading and saving
 * all team-related state (teams, members, metadata).
 */
public interface TeamStorage {

    /**
     * Load all teams into the provided service.
     *
     * @param service the team service to populate
     * @throws Exception if loading fails
     */
    void loadAll(TeamService service) throws Exception;

    /**
     * Persist all teams from the provided service.
     *
     * @param service the team service to save
     * @throws Exception if saving fails
     */
    void saveAll(TeamService service) throws Exception;

}
