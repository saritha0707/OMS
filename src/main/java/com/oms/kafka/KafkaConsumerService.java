package com.oms.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.entity.EventLog;
import com.oms.event.BaseEvent;
import com.oms.event.InventoryCheckResponseEvent;
import com.oms.event.OrderItemEvent;
import com.oms.exception.InsufficientStockException;
import com.oms.repository.EventLogRepository;
import com.oms.service.OrderService;
import com.oms.util.KafkaUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import com.oms.kafka.KafkaResponseTracker;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class KafkaConsumerService {

    private final OrderService orderService;
    private final EventLogRepository eventLogRepository;
    private final ObjectMapper objectMapper;
    private final KafkaUtil kafkaUtil;

    @Autowired
    private KafkaResponseTracker kafkaResponseTracker;

    public KafkaConsumerService(OrderService orderService,
                                EventLogRepository eventLogRepository,
                                ObjectMapper objectMapper,KafkaUtil kafkaUtil) {
        this.orderService = orderService;
        this.eventLogRepository = eventLogRepository;
        this.objectMapper = objectMapper;
        this.kafkaUtil = kafkaUtil;
    }
    /**
     * ✅ UPDATED: Consumes InventoryCheckResponse, processes inventory for all items,
     * tracks per-item success/failure
     */
    @KafkaListener(
            topics = "${kafka.topics.inventory-check-response:inventory-check-response}",
            groupId = "${kafka.consumer.group-id:order-group}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Retryable(
            retryFor = {Exception.class},
            noRetryFor = {InsufficientStockException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 2000, multiplier = 2)
    )
    public void consumeInventoryCheckResponseEvent(
            @Payload InventoryCheckResponseEvent event,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment
    ) {

        log.info("Received InventoryCheckResponse: eventId={}",
                event.getEventId());

        try {
            if(event.getOrderItemCheckResponse() == null || event.getOrderItemCheckResponse().isEmpty()) {
                log.error("Invalid event received - no items");
                acknowledgment.acknowledge();
                return;
            }

            if (isInvalidOrDuplicate(event, acknowledgment)) {
                return;
            }

            // ✅ Create event log
            EventLog eventLog = kafkaUtil.createEventLog(event, "PROCESSING");

            kafkaResponseTracker.complete(event.getEventId(),event);
            eventLog.setStatus("COMPLETED");
            eventLog.setProcessedAt(LocalDateTime.now());
            eventLogRepository.save(eventLog);
            acknowledgment.acknowledge();

        } catch (Exception e) {

            log.error("Kafka processing failed: {}", e.getMessage(), e);

            kafkaUtil.markEventAsFailed(event.getEventId(), e.getMessage());

            throw new RuntimeException("Kafka processing failed", e);
        }
    }
    /**
     * ✅ NEW: Publish consolidated event with all item results
     */
    /*private void publishConsolidatedInventoryUpdatedEvent(OrderCreatedEvent orderEvent,
                                                          List<InventoryUpdatedEvent.ItemResult> itemResults,
                                                          String overallStatus) {

        InventoryUpdatedEvent event = InventoryUpdatedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .orderId(orderEvent.getOrderId())
                .timestamp(LocalDateTime.now())
                .itemResults(itemResults)
                .overallStatus(overallStatus)
                .status(overallStatus)
                .build();

        kafkaProducerService.publishInventoryUpdatedEvent(event);

        log.info("Published consolidated InventoryUpdatedEvent: orderId={}, overallStatus={}, itemCount={}",
                orderEvent.getOrderId(), overallStatus, itemResults.size());
    }

    *//**
     * ✅ UPDATED: Consumes InventoryUpdatedEvent with consolidated item results
     * Updates order status to PARTIAL if ANY item has insufficient stock
     *//*
    @KafkaListener(
            topics = "${kafka.topics.inventory-events:inventory-events}",
            groupId = "${kafka.consumer.group-id:order-group}-inventory",
            containerFactory = "kafkaListenerContainerFactory",
            properties = {
                    "spring.json.value.default.type=com.oms.event.InventoryUpdatedEvent"
            }
    )
    @Retryable(
            retryFor = {Exception.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 2000, multiplier = 2)
    )
    public void consumeInventoryEvent(@Payload InventoryUpdatedEvent event,
                                      @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                                      @Header(KafkaHeaders.OFFSET) long offset,
                                      Acknowledgment acknowledgment)
    {
        log.info("Received InventoryUpdatedEvent: eventId={}, orderId={}, overallStatus={}",
                event.getEventId(), event.getOrderId(), event.getOverallStatus());
        
        EventLog eventLog = createEventLog(event, "PROCESSING");
        
        try {
            if (isInvalidOrDuplicate(event, acknowledgment)) {
                return;
            }

            Integer orderId = Math.toIntExact(event.getOrderId());

            // ✅ Determine order status based on overall inventory result
            OrderStatus targetStatus;
            String logStatus;

            if (event.getOverallStatus() != null) {
                // ✅ New consolidated format
                switch (event.getOverallStatus()) {
                    case "SUCCESS":
                        targetStatus = OrderStatus.PROCESSED;
                        logStatus = "COMPLETED";
                        break;
                    case "PARTIAL":
                        targetStatus = OrderStatus.PARTIAL;
                        logStatus = "PARTIAL";
                        break;
                    case "FAILED":
                        targetStatus = OrderStatus.FAILED;
                        logStatus = "FAILED";
                        break;
                    default:
                        targetStatus = OrderStatus.FAILED;
                        logStatus = "FAILED";
                }
            } else if (event.getStatus() != null) {
                // ✅ Fallback for legacy format
                switch (event.getStatus()) {
                    case "SUCCESS":
                        targetStatus = OrderStatus.PROCESSED;
                        logStatus = "COMPLETED";
                        break;
                    default:
                        targetStatus = OrderStatus.FAILED;
                        logStatus = "FAILED";
                }
            } else {
                targetStatus = OrderStatus.FAILED;
                logStatus = "FAILED";
            }

            // ✅ Update order status
            try {
                orderService.updateOrderStatus(orderId, targetStatus.name());
                log.info("Order {} status updated to {}", orderId, targetStatus.name());
            } catch (Exception e) {
                log.warn("Could not update order status to {}: {}", targetStatus.name(), e.getMessage());
                // Don't fail the consumer, just log the warning
            }

            // ✅ NEW: Update order items with inventory results
            try {
                if (event.getItemResults() != null && !event.getItemResults().isEmpty()) {
                    orderService.updateOrderItemsWithInventoryResults(orderId, event.getItemResults());
                    log.info("Updated order items with inventory results for orderId={}", orderId);
                }
            } catch (Exception e) {
                log.warn("Could not update order items with inventory results: {}", e.getMessage());
            }

            // ✅ Update event log
            eventLog.setStatus(logStatus);
            eventLog.setProcessedAt(LocalDateTime.now());
            eventLogRepository.save(eventLog);

            acknowledgment.acknowledge();
            
        } catch (Exception e) {
            log.error("Kafka processing failed: {}", e.getMessage(), e);
            markEventAsFailed(event.getEventId(), e.getMessage());
            throw new RuntimeException("Kafka processing failed", e);
        }
    }*/



    private boolean isInvalidOrDuplicate(BaseEvent event, Acknowledgment acknowledgment) {

        if (event.getEventId() == null || event.getEventId().isBlank()) {
            log.error("Invalid event received");
            acknowledgment.acknowledge();
            return true;
        }

        if (kafkaUtil.isEventAlreadyProcessed(event.getEventId())) {
            log.warn("Duplicate event: {}", event.getEventId());
            acknowledgment.acknowledge();
            return true;
        }

        return false;
    }
}


