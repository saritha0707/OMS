package com.oms.dto;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Data
public class InsufficientItem {
    private int productId;
    private int warehouseId;
    private int requestedQuantity;
    private int availableQuantity;

    public InsufficientItem(int productId,int warehouseId, int requestedQuantity, int availableQuantity) {
        this.productId = productId;
        this.warehouseId = warehouseId;
        this.requestedQuantity = requestedQuantity;
        this.availableQuantity = availableQuantity;
    }

    // getters
}
