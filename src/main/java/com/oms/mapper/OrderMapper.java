package com.oms.mapper;

import com.oms.dto.OrderItemResponseDTO;
import com.oms.dto.OrderResponseDTO;
import com.oms.entity.Orders;
import com.oms.enums.InventoryStatus;
import com.oms.event.OrderItemEventResponse;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;
@Component
public class OrderMapper {
    public OrderResponseDTO mapToResponseDTO(Orders order, List<OrderItemResponseDTO> itemResponse) {

        OrderResponseDTO dto = new OrderResponseDTO();

        dto.setId(order.getOrderId());

        if (order.getCustomer() != null) {
            dto.setCustomerName(order.getCustomer().getName());
        } else {
            dto.setCustomerName(order.getGuestName());
        }

        dto.setStatus(order.getStatus());
        dto.setTotalAmount(order.getTotalAmount());
        dto.setItems(itemResponse);
        return dto;
    }
    public OrderResponseDTO mapToResponseDTO(Orders order) {

        OrderResponseDTO dto = new OrderResponseDTO();

        dto.setId(order.getOrderId());

        if (order.getCustomer() != null) {
            dto.setCustomerName(order.getCustomer().getName());
        } else {
            dto.setCustomerName(order.getGuestName());
        }

        dto.setStatus(order.getStatus());
        dto.setTotalAmount(order.getTotalAmount());

        List<OrderItemResponseDTO> items = order.getOrderItems().stream().map(item -> {
            OrderItemResponseDTO itemDTO = new OrderItemResponseDTO();
            itemDTO.setProductId(item.getProduct());
            itemDTO.setWarehouseId(item.getWarehouse());
            //we need to get product details and update here
            // itemDTO.setProductName(item.getProductName());
            if(InventoryStatus.INSUFFICIENT_STOCK.name().equalsIgnoreCase(item.getInventoryStatus()))
            {
                itemDTO.setAvailableQuantity(item.getAvailableQuantity());
                itemDTO.setInventoryStatus(item.getInventoryStatus());
            }
            itemDTO.setQuantity(item.getQuantity());
            itemDTO.setPrice(item.getPrice().intValue());
            return itemDTO;
        }).collect(Collectors.toList());
        dto.setItems(items);
        return dto;
    }
}


