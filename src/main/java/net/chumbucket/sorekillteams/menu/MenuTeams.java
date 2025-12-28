/*
 * Copyright © 2025 Sorekill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillteams.menu;

import net.chumbucket.sorekillteams.model.Team;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Keeps the exact unique member logic you had (owner + members, de-duped).
 * Used by MenuListRenderer + MenuCycler.
 */
public final class MenuTeams {

    private MenuTeams() {}

    public static int uniqueMemberCount(Team t) {
        if (t == null) return 0;
        LinkedHashSet<UUID> set = new LinkedHashSet<>();
        if (t.getOwner() != null) set.add(t.getOwner());
        if (t.getMembers() != null) for (UUID u : t.getMembers()) if (u != null) set.add(u);
        return set.size();
    }

    public static List<UUID> uniqueTeamMembers(Team team) {
        LinkedHashSet<UUID> set = new LinkedHashSet<>();
        if (team == null) return List.of();

        if (team.getOwner() != null) set.add(team.getOwner());
        if (team.getMembers() != null) for (UUID u : team.getMembers()) if (u != null) set.add(u);

        return new ArrayList<>(set);
    }
}
