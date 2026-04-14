package com.oms.service;

import com.oms.config.KafkaTopicsConfig;
import com.oms.event.InventoryCheckEvent;
import com.oms.event.OrderCreatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class KafkaProducerService {
    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;
    static KafkaTopicsConfig kafkaTopicsConfig;

    private static final String Order_TOPIC = "order-events";
    private static final String Inventory_TOPIC = "inventory-events";
    private static final String Inventory_Check_TOPIC = kafkaTopicsConfig.getInventoryCheckRequest();

    public void publishInventoryCheckEvent(InventoryCheckEvent event)
    {
        kafkaTemplate.send(Inventory_Check_TOPIC,event);
        log.info("InventoryCheckEvent:" + event.getEventId());
       event.getOrderItems().forEach(
                        i -> log.info("Product Details: Product {} Warehouse {} Quanityt {}" + i.getProductId() + i.getWarehouseId() + i.getQuantity()));
    }

//    public void publishInventoryUpdatedEvent(InventoryUpdatedEvent event) {
//        kafkaTemplate.send(Inventory_TOPIC, event);
//        System.out.println("InventoryUpdatedEvent sent: eventId=" + event.getEventId() +
//                ", productId=" + event.getProductId() +
//                ", status=" + event.getStatus());
//    }

 /*   public void publishOrderCreatedEvent(OrderCreatedEvent event) {
        kafkaTemplate.send(Order_TOPIC, event);
        System.out.println("OrderCreatedEvent sent: eventId=" + event.getEventId() +
                ", orderId=" + event.getOrderId() +
                ", status=" + event.getStatus());
    }*/
}
