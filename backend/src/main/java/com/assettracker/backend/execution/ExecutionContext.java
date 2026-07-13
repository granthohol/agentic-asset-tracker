package com.assettracker.backend.execution;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-plan scratch space during one PlanExecutor run. Fresh per Kafka message.
 * idResolutionMap: tempId -> real id minted when an upsert ran.
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
