package com.oms.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.entity.EventLog;
import com.oms.event.BaseEvent;
import com.oms.repository.EventLogRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class KafkaUtil {

    private final EventLogRepository eventLogRepository;
    private final ObjectMapper objectMapper;

    // ✅ Helper methods
    public KafkaUtil(EventLogRepository eventLogRepository,
                                ObjectMapper objectMapper) {
        this.eventLogRepository = eventLogRepository;
        this.objectMapper = objectMapper;
    }
    public boolean isEventAlreadyProcessed(String eventId) {
        return eventLogRepository.existsByEventId(eventId);
    }

    public EventLog createEventLog(BaseEvent event, String status) {
        try {
            EventLog eventLog = new EventLog();
            eventLog.setEventId(event.getEventId());
            eventLog.setEventType(event.getEventType());
            eventLog.setStatus(status);
            eventLog.setEventPayload(objectMapper.writeValueAsString(event));
            eventLog.setCreatedAt(LocalDateTime.now());

            return eventLogRepository.save(eventLog);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create event log", e);
        }
    }

    public void markEventAsFailed(String eventId, String errorMessage) {
        eventLogRepository.findByEventId(eventId).ifPresent(eventLog -> {
            eventLog.setStatus("FAILED");
            eventLog.setErrorMessage(errorMessage);
            eventLog.setProcessedAt(LocalDateTime.now());
            eventLogRepository.save(eventLog);
        });
    }
}
