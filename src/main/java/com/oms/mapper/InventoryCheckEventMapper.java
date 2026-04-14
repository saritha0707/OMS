package com.oms.mapper;

import com.oms.entity.OrderItem;
import com.oms.event.InventoryCheckEvent;

import java.util.List;
import java.util.UUID;

public class InventoryCheckEventMapper {

    public static InventoryCheckEvent buildInventoryCheckEvent(List<OrderItem> items)
    {
        return InventoryCheckEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .orderItems(items)
                .build();
    }
}
