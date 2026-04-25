package com.oms.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class OrderResponseDTO {

    private int id;
    private String customerName;
    private String status;
    private BigDecimal totalAmount;

    private List<OrderItemResponseDTO> items;
}