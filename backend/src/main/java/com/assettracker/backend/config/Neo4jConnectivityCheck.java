package com.assettracker.backend.config;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

// ApplicationRunner: Spring invokes run(...) once after the context is fully
// initialized but before the app is marked "ready". Perfect place to fail loud
// if Neo4j is misconfigured, instead of crashing later on the first real query.
@Component
public class Neo4jConnectivityCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Neo4jConnectivityCheck.class);

    private final Driver driver;

    public Neo4jConnectivityCheck(Driver driver) {
        this.driver = driver;
    }

    @Override
    public void run(ApplicationArguments args) {
        // try-with-resources: session is checked back into the pool on close,
        // even if the Cypher call throws.
        try (Session session = driver.session()) {
            int ok = session.run("RETURN 1 AS ok").single().get("ok").asInt();
            if (ok != 1) {
                throw new IllegalStateException("Neo4j returned unexpected value: " + ok);
            }
            log.info("Neo4j connection established");
        }
    }
}
