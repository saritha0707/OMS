package com.oms.exception;

public class OrderProcessingException extends RuntimeException {
    public OrderProcessingException(String databaseErrorWhileCreatingOrder) {
        super(databaseErrorWhileCreatingOrder);
    }
}
