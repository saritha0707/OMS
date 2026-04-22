package com.oms.exception;

import com.oms.dto.InsufficientItem;

import java.util.List;
import java.util.stream.Collectors;

public class InsufficientStockException extends RuntimeException {

    private List<InsufficientItem> items;

    // ✅ Single item constructor
    public InsufficientStockException(int productId,int warehouseId, int availableQty, int requestedQty) {
        super("Insufficient stock for productId=" + productId +
                        ", warehouse="+ warehouseId +
                ", available=" + availableQty +
                ", requested=" + requestedQty);

        this.items = List.of(new InsufficientItem(productId,warehouseId, requestedQty, availableQty));
    }

    // ✅ Multiple items constructor
    public InsufficientStockException(List<InsufficientItem> items) {
        super(buildMessage(items));
        this.items = items;
    }

    public List<InsufficientItem> getItems() {
        return items;
    }

    // 🔥 Helper to create single combined message
    private static String buildMessage(List<InsufficientItem> items) {
        return items.stream()
                .map(item -> "productId=" + item.getProductId() +
                        " (available=" + item.getAvailableQuantity() +
                        ", requested=" + item.getRequestedQuantity() + ")")
                .collect(Collectors.joining("; ",
                        "Insufficient stock for items: ", ""));
    }
}