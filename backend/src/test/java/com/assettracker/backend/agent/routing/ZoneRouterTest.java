package com.assettracker.backend.agent.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class ZoneRouterTest {

    @Test
    void clearPathReturnsDirectLeg() {
        RouteResult r = ZoneRouter.plan(39.00, -77.20, 39.05, -77.18, List.of());
        assertThat(r.direct()).isTrue();
        assertThat(r.legs()).hasSize(1);
        assertThat(r.legs().get(0)[0]).isEqualTo(39.05);
        assertThat(r.legs().get(0)[1]).isEqualTo(-77.18);
        assertThat(r.avoidedZoneIds()).isEmpty();
    }

    @Test
    void circleOffToTheSideDoesNotBlock() {
        // Path north; circle far to the east.
        CircleObstacle c = new CircleObstacle("z1", 39.025, -77.10, 500);
        RouteResult r = ZoneRouter.plan(39.00, -77.20, 39.05, -77.20, List.of(c));
        assertThat(r.direct()).isTrue();
        assertThat(r.legs()).hasSize(1);
    }

    @Test
    void blockingCircleProducesDetour() {
        // Midpoint of path sits inside a large circle on the segment.
        CircleObstacle c = new CircleObstacle("no-fly", 39.025, -77.20, 800);
        RouteResult r = ZoneRouter.plan(39.00, -77.20, 39.05, -77.20, List.of(c));
        assertThat(r.direct()).isFalse();
        assertThat(r.avoidedZoneIds()).containsExactly("no-fly");
        assertThat(r.legs().size()).isGreaterThanOrEqualTo(2);
        // Final leg ends at destination.
        double[] last = r.legs().get(r.legs().size() - 1);
        assertThat(last[0]).isEqualTo(39.05);
        assertThat(last[1]).isEqualTo(-77.20);
        // Intermediate waypoints stay outside the buffered circle.
        for (int i = 0; i < r.legs().size() - 1; i++) {
            double[] p = r.legs().get(i);
            assertThat(ZoneRouter.pointInBufferedCircle(p[0], p[1], c)).isFalse();
        }
        double prevLat = 39.00;
        double prevLng = -77.20;
        for (double[] leg : r.legs()) {
            assertThat(ZoneRouter.firstBlocking(prevLat, prevLng, leg[0], leg[1], List.of(c))).isNull();
            prevLat = leg[0];
            prevLng = leg[1];
        }
    }

    @Test
    void destinationInsideZoneThrows() {
        CircleObstacle c = new CircleObstacle("no-fly", 39.05, -77.18, 2000);
        assertThatThrownBy(() -> ZoneRouter.plan(39.00, -77.20, 39.05, -77.18, List.of(c)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("destination is inside");
    }

    @Test
    void startInsideZoneThrows() {
        CircleObstacle c = new CircleObstacle("no-fly", 39.00, -77.20, 2000);
        assertThatThrownBy(() -> ZoneRouter.plan(39.00, -77.20, 39.05, -77.18, List.of(c)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("start point is inside");
    }

    @Test
    void twoCirclesCanBeAvoided() {
        CircleObstacle a = new CircleObstacle("a", 39.02, -77.20, 600);
        CircleObstacle b = new CircleObstacle("b", 39.04, -77.20, 600);
        RouteResult r = ZoneRouter.plan(39.00, -77.20, 39.06, -77.20, List.of(a, b));
        assertThat(r.direct()).isFalse();
        assertThat(r.avoidedZoneIds()).isNotEmpty();
        double[] last = r.legs().get(r.legs().size() - 1);
        assertThat(last[0]).isEqualTo(39.06);
        assertThat(last[1]).isEqualTo(-77.20);
    }

    @Test
    void eastWestBlockingCircleProducesDetour() {
        // Path due east; circle centered on the segment. Deg-space radius would undersize E–W.
        CircleObstacle c = new CircleObstacle("ew-nofly", 39.00, -77.15, 1200);
        RouteResult r = ZoneRouter.plan(39.00, -77.25, 39.00, -77.05, List.of(c));
        assertThat(r.direct()).isFalse();
        assertThat(r.avoidedZoneIds()).containsExactly("ew-nofly");
        assertThat(r.legs().size()).isGreaterThanOrEqualTo(2);
        double[] last = r.legs().get(r.legs().size() - 1);
        assertThat(last[0]).isEqualTo(39.00);
        assertThat(last[1]).isEqualTo(-77.05);
        for (int i = 0; i < r.legs().size() - 1; i++) {
            double[] p = r.legs().get(i);
            assertThat(ZoneRouter.pointInBufferedCircle(p[0], p[1], c)).isFalse();
        }
        // Each chord from previous→next (including start) must clear the circle.
        double prevLat = 39.00;
        double prevLng = -77.25;
        for (double[] leg : r.legs()) {
            assertThat(ZoneRouter.firstBlocking(prevLat, prevLng, leg[0], leg[1], List.of(c))).isNull();
            prevLat = leg[0];
            prevLng = leg[1];
        }
    }
}
