package com.assettracker.backend.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class StubLlmClientSelectionTest {

    private final List<String> fleet = List.of(
        "drone-000", "drone-001", "drone-002", "drone-003", "drone-004"
    );

    @Test
    void defaultsToAllAvailable() {
        assertThat(StubLlmClient.selectDroneIds("observe the area", fleet))
            .containsExactlyElementsOf(fleet);
    }

    @Test
    void respectsCountPhrase() {
        assertThat(StubLlmClient.selectDroneIds("send 3 drones in a wedge", fleet))
            .containsExactly("drone-000", "drone-001", "drone-002");
        assertThat(StubLlmClient.selectDroneIds("swarm of 2 at 39.05,-77.18", fleet))
            .containsExactly("drone-000", "drone-001");
    }

    @Test
    void respectsNamedIds() {
        assertThat(StubLlmClient.selectDroneIds("route drone-002 and drone-004", fleet))
            .containsExactly("drone-002", "drone-004");
    }

    @Test
    void namedIdsWinOverCount() {
        assertThat(StubLlmClient.selectDroneIds("send 5 drones: drone-001 and drone-003", fleet))
            .containsExactly("drone-001", "drone-003");
    }

    @Test
    void countClampsToAvailable() {
        assertThat(StubLlmClient.selectDroneIds("send 99 drones", fleet))
            .containsExactlyElementsOf(fleet);
    }
}
