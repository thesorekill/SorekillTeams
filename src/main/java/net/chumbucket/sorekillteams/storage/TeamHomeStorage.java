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

import net.chumbucket.sorekillteams.service.TeamHomeService;

import java.util.UUID;

/**
 * Persistence contract for {@link TeamHomeService}.
 */
public interface TeamHomeStorage {

    void loadAll(TeamHomeService homes) throws Exception;

    void saveAll(TeamHomeService homes) throws Exception;

    /**
     * ✅ Remove all homes for a team from storage (used on disband).
     * Default no-op so YAML/other backends don’t break.
     */
    default void deleteTeam(UUID teamId) throws Exception {
        // no-op by default
    }
}
