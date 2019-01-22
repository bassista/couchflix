package com.cb.fts.sample;

import com.cb.fts.sample.service.ActorEntityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class ActorNameCmdLine implements CommandLineRunner {

    @Autowired
    private ActorEntityService actorEntityService;

    @Override
    public void run(String... strings) throws Exception {
        //actorEntityService.generateActorNamesDatabase();
    }
}
