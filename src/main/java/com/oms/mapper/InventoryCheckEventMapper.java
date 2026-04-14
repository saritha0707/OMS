package com.oms.mapper;

import com.oms.entity.OrderItem;
import com.oms.event.InventoryCheckEvent;
import com.oms.event.OrderItemEvent;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class InventoryCheckEventMapper {

    public static InventoryCheckEvent buildInventoryCheckEvent(List<OrderItem>  items)
    {
        List<OrderItemEvent> itemEvents = items.stream()
                .map(item -> {
                    OrderItemEvent event = new OrderItemEvent();
                    event.setWarehouseId(item.getWarehouse());
                    event.setProductId(item.getProduct());
                    event.setQuantity(item.getQuantity());
                    return event;
                })
                .collect(Collectors.toList());
        return InventoryCheckEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .orderItems(itemEvents)
                .build();
    }
}
