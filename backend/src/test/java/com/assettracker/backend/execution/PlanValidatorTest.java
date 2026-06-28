package com.assettracker.backend.execution;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.assettracker.backend.agent.plan.ExecutionPlan;
import com.assettracker.backend.agent.plan.PlanAction;

class PlanValidatorTest {

    private final PlanValidator validator = new PlanValidator();

    private ExecutionPlan plan(PlanAction... actions) {
        return new ExecutionPlan("plan-1", "r", List.of(actions));
    }

    @Test
    void acceptsValidTempIdChain() {
        assertThatCode(() -> validator.validate(plan(
            new PlanAction.UpsertObjective(null, "obj-1", "Observe", 1, 39.05, -77.18, 250.0, null),
            new PlanAction.DeploySquadronToObjective("squadron-alpha", "$obj-1"),
            new PlanAction.SetWaypoint("drone-007", 39.05, -77.18, "RECON")
        ))).doesNotThrowAnyException();
    }

    @Test
    void rejectsEmptyPlan() {
        assertThatThrownBy(() -> validator.validate(plan()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no actions");
    }

    @Test
    void rejectsUnresolvedRef() {
        assertThatThrownBy(() -> validator.validate(plan(
            new PlanAction.DeploySquadronToObjective("squadron-alpha", "$obj-1")
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unresolved reference");
    }

    @Test
    void rejectsRefThatComesBeforeDeclaration() {
        // Deploy references $obj-1 before the upsert that declares it.
        assertThatThrownBy(() -> validator.validate(plan(
            new PlanAction.DeploySquadronToObjective("squadron-alpha", "$obj-1"),
            new PlanAction.UpsertObjective(null, "obj-1", "Observe", 1, null, null, null, null)
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unresolved reference");
    }

    @Test
    void rejectsBothIdAndTempId() {
        assertThatThrownBy(() -> validator.validate(plan(
            new PlanAction.UpsertSquadron("squadron-alpha", "squad-1", "Alpha", "sector-1")
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exactly one of id or tempId");
    }

    @Test
    void rejectsNeitherIdNorTempId() {
        assertThatThrownBy(() -> validator.validate(plan(
            new PlanAction.UpsertSquadron(null, null, "Alpha", "sector-1")
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exactly one of id or tempId");
    }

    @Test
    void rejectsOutOfRangeWaypoint() {
        assertThatThrownBy(() -> validator.validate(plan(
            new PlanAction.SetWaypoint("drone-007", 200.0, -77.18, null)
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("out of range");
    }

    @Test
    void rejectsDollarRefOnDroneId() {
        assertThatThrownBy(() -> validator.validate(plan(
            new PlanAction.SetWaypoint("$drone-1", 39.0, -77.0, null)
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("literal id");
    }
}
