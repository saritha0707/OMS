package com.oms.kafka;

import com.oms.config.KafkaTopicsConfig;
import com.oms.entity.EventLog;
import com.oms.event.InventoryCheckEvent;
import com.oms.event.OrderCreatedEvent;
import com.oms.util.KafkaUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class KafkaProducerService {
    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private KafkaTopicsConfig kafkaTopicsConfig;
    private final KafkaUtil kafkaUtil;

    private static final String Order_TOPIC = "order-events";
    private static final String Inventory_TOPIC = "inventory-events";

    KafkaProducerService(KafkaUtil kafkaUtil)
    {
        this.kafkaUtil = kafkaUtil;
    }

    public void publishInventoryCheckEvent(InventoryCheckEvent event)
    {
        String inventory_topic = kafkaTopicsConfig.getInventoryCheckRequest();
        kafkaTemplate.send(inventory_topic,event);
        // ✅ Create event log
        EventLog eventLog = kafkaUtil.createEventLog(event, "PROCESSING");
        log.info("InventoryCheckEvent:" + event.getEventId());
       event.getOrderItems().forEach(
                        i -> log.info("Product Details: Product {} Warehouse {} Quantity {}" + i.getProductId() + i.getWarehouseId() + i.getQuantity()));
    }


//    public void publishInventoryUpdatedEvent(InventoryUpdatedEvent event) {
//        kafkaTemplate.send(Inventory_TOPIC, event);
//        System.out.println("InventoryUpdatedEvent sent: eventId=" + event.getEventId() +
//                ", productId=" + event.getProductId() +
//                ", status=" + event.getStatus());
//    }

    public void publishOrderCreatedEvent(OrderCreatedEvent event) {
        kafkaTemplate.send(Order_TOPIC, event);
        System.out.println("OrderCreatedEvent sent: eventId=" + event.getEventId() +
                ", orderId=" + event.getOrderId() +
                ", status=" + event.getStatus());
    }
}
