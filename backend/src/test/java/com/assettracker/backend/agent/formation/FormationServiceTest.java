package com.assettracker.backend.agent.formation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class FormationServiceTest {

    private final FormationService service = new FormationService();

    @Test
    void listSpecsCoversCatalog() {
        List<FormationSpec> specs = service.listSpecs();
        assertThat(specs).extracting(FormationSpec::type)
            .containsExactly(FormationType.RING, FormationType.WEDGE, FormationType.LINE);
    }

    @Test
    void ringPreviewHasDistinctSlotsNearCenter() {
        List<String> ids = IntStream.range(0, 6).mapToObj(i -> "drone-00" + i).toList();
        FormationPreview preview = service.preview(FormationType.RING, 39.05, -77.18, ids, 200.0);

        assertThat(preview.type()).isEqualTo(FormationType.RING);
        assertThat(preview.slots()).hasSize(6);
        assertDistinctTargets(preview);
        for (FormationSlot slot : preview.slots()) {
            assertThat(slot.targetLat()).isBetween(39.05 - 0.003, 39.05 + 0.003);
            assertThat(slot.targetLng()).isBetween(-77.18 - 0.003, -77.18 + 0.003);
        }
    }

    @Test
    void lineAndWedgeProduceDistinctLayouts() {
        List<String> ids = List.of("a", "b", "c", "d");
        FormationPreview line = service.preview(FormationType.LINE, 39.0, -77.0, ids, null);
        FormationPreview wedge = service.preview(FormationType.WEDGE, 39.0, -77.0, ids, null);

        assertThat(line.slots()).hasSize(4);
        assertThat(wedge.slots()).hasSize(4);
        assertDistinctTargets(line);
        assertDistinctTargets(wedge);

        // LINE is east-west: latitudes stay on the center line.
        assertThat(line.slots()).allSatisfy(s -> assertThat(s.targetLat()).isEqualTo(39.0));
        // WEDGE apex is at center for slot 0.
        assertThat(wedge.slots().get(0).targetLat()).isEqualTo(39.0);
        assertThat(wedge.slots().get(0).targetLng()).isEqualTo(-77.0);
    }

    @Test
    void clampsToMaxFormationDrones() {
        List<String> ids = IntStream.range(0, FormationService.MAX_FORMATION_DRONES + 10)
            .mapToObj(i -> "d-" + i).toList();
        FormationPreview preview = service.preview(FormationType.RING, 39.05, -77.18, ids, null);
        assertThat(preview.slots()).hasSize(FormationService.MAX_FORMATION_DRONES);
    }

    @Test
    void emptyDroneListThrows() {
        assertThatThrownBy(() -> service.preview(FormationType.RING, 39.0, -77.0, List.of(), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("droneId");
    }

    @Test
    void standoffCenterLiesBetweenLeaderAndAoi() {
        double aoiLat = 39.05;
        double aoiLng = -77.18;
        double leaderLat = 39.00;
        double leaderLng = -77.18;
        double[] formUp = FormationService.standoffCenter(
            aoiLat, aoiLng, leaderLat, leaderLng, FormationService.DEFAULT_STANDOFF_METERS);

        assertThat(formUp[0]).isBetween(leaderLat, aoiLat);
        assertThat(formUp[1]).isCloseTo(aoiLng, org.assertj.core.data.Offset.offset(0.001));
        assertThat(formUp[0]).isLessThan(aoiLat - 0.01);
    }

    @Test
    void standoffFallsBackSouthWhenLeaderOnAoi() {
        double[] formUp = FormationService.standoffCenter(
            39.05, -77.18, 39.05, -77.18, FormationService.DEFAULT_STANDOFF_METERS);
        assertThat(formUp[0]).isLessThan(39.05);
        assertThat(formUp[1]).isEqualTo(-77.18);
    }

    @Test
    void wedgeFacesEastWhenFacingPointIsEast() {
        List<String> ids = List.of("a", "b", "c", "d");
        // Face due east of center → arms should trail west (lower lng than apex).
        FormationPreview wedge = service.preview(
            FormationType.WEDGE, 39.0, -77.0, ids, null, 39.0, -76.9);

        FormationSlot apex = wedge.slots().get(0);
        assertThat(apex.targetLat()).isEqualTo(39.0);
        assertThat(apex.targetLng()).isEqualTo(-77.0);

        for (int i = 1; i < wedge.slots().size(); i++) {
            assertThat(wedge.slots().get(i).targetLng())
                .as("arm slot %s should be west of apex when facing east", i)
                .isLessThan(apex.targetLng());
        }
    }

    @Test
    void lineIsPerpendicularToFacingDirection() {
        List<String> ids = List.of("a", "b", "c", "d");
        // Face north → line stays east-west (constant lat).
        FormationPreview line = service.preview(
            FormationType.LINE, 39.0, -77.0, ids, null, 39.1, -77.0);
        assertThat(line.slots()).allSatisfy(s -> assertThat(s.targetLat()).isEqualTo(39.0));
    }

    private static void assertDistinctTargets(FormationPreview preview) {
        Set<String> keys = preview.slots().stream()
            .map(s -> s.targetLat() + "," + s.targetLng())
            .collect(Collectors.toSet());
        assertThat(keys).hasSize(preview.slots().size());
    }
}
