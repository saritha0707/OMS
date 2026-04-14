package com.oms.entity;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "order_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int orderItemId;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private BigDecimal price;

    // ✅ Add inventory status: SUCCESS, INSUFFICIENT_STOCK, PENDING
    @Column(nullable = true)
    private String inventoryStatus;

    // ✅ Add available quantity for reference
    @Column(nullable = true)
    private Integer availableQuantity;

    @ManyToOne
    @JoinColumn(name = "order_id", nullable = false)
    private Orders order;

    @Column(nullable = false)
    private int product;

    //  ADD THIS (based on ER diagram)
    @Column(nullable = false)
    private int warehouse;

}