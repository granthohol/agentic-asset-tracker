package com.assettracker.backend.execution;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-plan scratch space for a single {@link PlanExecutor} run. Created fresh for each
 * Kafka message and discarded when execution finishes — never shared across plans.
 *
 * <p>{@code idResolutionMap} maps a plan-local {@code tempId} (e.g. {@code "obj-1"}) to the
 * real id minted when its {@code upsert*} action ran (e.g. {@code "objective-3f2a8c19"}), so
 * later {@code "$obj-1"} references resolve to the persisted node.
 */
class ExecutionContext {

    final String planId;
    final Map<String, String> idResolutionMap = new HashMap<>();
    final List<String> report = new ArrayList<>();

    ExecutionContext(String planId) {
        this.planId = planId;
    }

    void recordMint(String tempId, String realId) {
        idResolutionMap.put(tempId, realId);
    }

    String resolve(String value) {
        if (value != null && value.startsWith("$")) {
            String ref = value.substring(1);
            String real = idResolutionMap.get(ref);
            if (real == null) {
                throw new IllegalStateException("unresolved $ref '" + value + "' (tempId '" + ref + "' was never minted)");
            }
            return real;
        }
        return value;
    }
}
