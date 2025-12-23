package net.chumbucket.sorekillteams.storage;

import net.chumbucket.sorekillteams.service.TeamService;

public interface TeamStorage {
    void loadAll(TeamService service);
    void saveAll(TeamService service);
}
