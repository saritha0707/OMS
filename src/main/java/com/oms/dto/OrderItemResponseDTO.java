package com.oms.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OrderItemResponseDTO {

    private int productId;
    private String productName;
    private int warehouseId;
    private Integer quantity;
    private Integer price;

    private String inventoryStatus; // AVAILABLE, INSUFFICIENT_STOCK
    private Integer availableQuantity; // Avaialbe stock if status in INSUFFICIENT_STOCK
}