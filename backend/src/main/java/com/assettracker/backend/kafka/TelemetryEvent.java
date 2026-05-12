package com.assettracker.backend.kafka;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TelemetryEvent(
    String droneId,
    double latitude,
    double longitude,
    int batteryLevel,
    String status,
    long time,
    int seqNum
) {}
