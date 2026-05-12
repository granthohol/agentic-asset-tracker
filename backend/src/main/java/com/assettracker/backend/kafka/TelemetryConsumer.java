package com.assettracker.backend.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TelemetryConsumer {

    private static final Logger log = LoggerFactory.getLogger(TelemetryConsumer.class);

    @KafkaListener(
        topics = "drone.telemetry.v1",
        groupId = "asset-tracker-backend"
    )
    public void onTelemetry(TelemetryEvent event) {
        log.info(
            "received event droneId={} lat={} lon={} battery={} status={} time={} seqNum={}",
            event.droneId(),
            event.latitude(),
            event.longitude(),
            event.batteryLevel(),
            event.status(),
            event.time(),
            event.seqNum()
        );
    }
    
}
