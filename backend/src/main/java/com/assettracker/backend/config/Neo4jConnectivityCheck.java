package com.assettracker.backend.config;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

// Ping Neo4j at startup so bad config fails fast.
@Component
public class Neo4jConnectivityCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Neo4jConnectivityCheck.class);

    private final Driver driver;

    public Neo4jConnectivityCheck(Driver driver) {
        this.driver = driver;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (Session session = driver.session()) {
            int ok = session.run("RETURN 1 AS ok").single().get("ok").asInt();
            if (ok != 1) {
                throw new IllegalStateException("Neo4j returned unexpected value: " + ok);
            }
            log.info("Neo4j connection established");
        }
    }
}
