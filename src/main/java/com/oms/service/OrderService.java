package com.oms.service;

import com.oms.dto.InsufficientItem;
import com.oms.dto.OrderItemResponseDTO;
import com.oms.dto.OrderRequestDTO;
import com.oms.dto.OrderResponseDTO;
import com.oms.entity.*;
import com.oms.enums.OrderStatus;
import com.oms.enums.PaymentMethod;
import com.oms.event.InventoryCheckEvent;
import com.oms.event.InventoryCheckResponseEvent;
import com.oms.event.OrderCreatedEvent;
import com.oms.event.OrderItemEventResponse;
import com.oms.exception.*;
import com.oms.kafka.KafkaProducerService;
import com.oms.kafka.KafkaResponseTracker;
import com.oms.mapper.InventoryCheckEventMapper;
import com.oms.mapper.OrderMapper;
import com.oms.repository.*;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
@Slf4j
public class OrderService {

    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    OrderStatusHistoryRepository statusHistoryRepository;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private KafkaProducerService producerService;
    @Autowired
    private KafkaResponseTracker kafkaResponseTracker;

    @Autowired
    PaymentRepository paymentRepository;

   @Transactional
   public ResponseEntity<OrderResponseDTO> createOrder(OrderRequestDTO dto) {
        try
        {
            Orders order = new Orders();
        //  FIX 1: Strong validation
        validateCustomerOrGuest(dto);
            if (dto.getPaymentMethod() == PaymentMethod.REFUND) {
                throw new InvalidPaymentMethodException(
                        "Invalid value for paymentMethod. Allowed values: CASH_ON_DELIVERY, ONLINE"
                );
            }
            log.info("Creating order with {} items", dto.getItems().size());

            // Save details in DB with status as created
            order.setStatus(OrderStatus.CREATED.name());

            //  FIX 2: Customer vs Guest handling
            if (dto.getCustomerId() != null) {
                Customer customer = customerRepository.findById(dto.getCustomerId()).orElseThrow(() -> new ResourceNotFoundException("Customer not found"));
                order.setCustomer(customer);
            } else {
                order.setGuestName(dto.getGuestName());
                order.setGuestEmail(dto.getGuestEmail());
                order.setGuestPhone(dto.getGuestPhone());
            }

            BigDecimal totalAmount = BigDecimal.ZERO;

            List<OrderItem> orderItems = dto.getItems().stream().map(itemDTO -> {
                OrderItem item = new OrderItem();
                item.setProduct(itemDTO.getProductId());
                item.setWarehouse(itemDTO.getWarehouseId());
                item.setPrice(itemDTO.getPrice());
                item.setQuantity(itemDTO.getQuantity());
                item.setOrder(order);
                return item;
            }).collect(Collectors.toList());
            order.setOrderItems(orderItems);

        //  FIX 4: Calculate total safely
        totalAmount = orderItems.stream().map(item -> item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()))).reduce(BigDecimal.ZERO, BigDecimal::add);

        order.setTotalAmount(totalAmount);

        //update Payment Table
        Payment payment = new Payment();
        payment.setAmount(totalAmount);
        payment.setOrder(order);
        payment.setPaymentMethod(dto.getPaymentMethod());
        if (dto.getPaymentMethod() == PaymentMethod.ONLINE)
            payment.setPaymentStatus("PAID");
        else if (dto.getPaymentMethod() == PaymentMethod.CASH_ON_DELIVERY)
            payment.setPaymentStatus("PENDING");
        order.setPayments(List.of(payment));

        //  FIX 5: Save FIRST (important for consistency) - Initial Status - Created
       /* Orders savedOrder = orderRepository.save(order);
        updateOrderStatusHistory(savedOrder);*/

      //Read kafka message and get details of all items
      String eventId = UUID.randomUUID().toString();
      //Register future
      CompletableFuture<InventoryCheckResponseEvent> future = kafkaResponseTracker.register(eventId);
      //Send message to kafka topic inventory-availability topic
      sendKafkaMessageToInventory(orderItems, eventId);

      //Update Status in DB
      // order.setStatus(OrderStatus.PENDING.name());
       //Update Status of order as Pending
      // savedOrder = orderRepository.save(order);
      // updateOrderStatusHistory(savedOrder);

// Wait for response
            InventoryCheckResponseEvent inventoryResponse = future.get(10, TimeUnit.SECONDS);
            List<OrderItemEventResponse> responses =
                    inventoryResponse.getOrderItemCheckResponse();
            AtomicBoolean hasFailures = new AtomicBoolean(false);
            // ✅ Step 1: Update order items based on inventory
           // List<OrderItem> finalItems = savedOrder.getOrderItems().stream()
            List<OrderItem> finalItems = order.getOrderItems().stream()
                    .map(item -> {
                        OrderItemEventResponse inv = responses
                                .stream()
                                .filter(res ->
                                        res.getProductId().equals(item.getProduct()) &&
                                                res.getWarehouseId().equals(item.getWarehouse()))
                                .findFirst()
                                .orElse(null);

                        if (inv == null)
                            return null;
                        else if("INVALID_PRODUCT".equalsIgnoreCase(inv.getStatus())){
                            hasFailures.set(true);
                            log.warn("INVALID PRODUCT" + inv.getProductId());
                            return null;
                        }
                        else if("INVALID_WAREHOUSE".equalsIgnoreCase(inv.getStatus())) {
                            log.warn("INVALID WAREHOUSE" + inv.getWarehouseId());
                            hasFailures.set(true);
                            return null;
                        }
                        Integer available = inv.getAvailableCount();

                        // ❌ remove item if no stock
                        if (available == null || available == 0) {
                            log.warn("Removing product {} (warehouse {}) due to no stock",
                                    item.getProduct(), item.getWarehouse());
                            return null;
                        }

                        // ✅ always use available quantity (full or partial)
                        if(item.getQuantity() > available)
                        item.setQuantity(available);

                        return item;
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            // ❗ Edge case: no items left
            if (finalItems.isEmpty() & !hasFailures.get() ) {
                List<InsufficientItem> items = order.getOrderItems().stream().map(
                        item -> new InsufficientItem(
                                item.getProduct(),
                                item.getWarehouse(),
                                item.getQuantity(),
                                Optional.ofNullable(item.getAvailableQuantity()).orElse(0)
                        )
                ).collect(Collectors.toUnmodifiableList());

                throw new InsufficientStockException(items);
            }

        // ✅ Step 2: update order with filtered items
            order.setOrderItems(finalItems);

        // ✅ Step 3: calculate total ONLY from final items
            totalAmount = finalItems.stream()
                    .map(item -> item.getPrice()
                            .multiply(BigDecimal.valueOf(item.getQuantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            order.setTotalAmount(totalAmount);

            // ✅ Step 4: update payment
            payment = order.getPayments().get(0);
            payment.setAmount(totalAmount);

        // ✅ Step 5: prepare response DTO (optional)
            List<OrderItemResponseDTO> itemResponseDTOList =
                    responses.stream()
                            .map(orderItem -> {
                                OrderItemResponseDTO dto_updated = new OrderItemResponseDTO();
                                dto_updated.setProductId(orderItem.getProductId());
                                dto_updated.setWarehouseId(orderItem.getWarehouseId());
                               if("INSUFFICIENT_STOCK".equalsIgnoreCase(orderItem.getStatus())) {
                                        dto_updated.setAvailableQuantity(orderItem.getAvailableCount());
                               }
                               else if("INVALID_PRODUCT".equalsIgnoreCase(orderItem.getStatus())){
                                   dto_updated.setInventoryStatus("Product Id is not found");
                               }
                               else if("INVALID_WAREHOUSE".equalsIgnoreCase(orderItem.getStatus())) {
                               dto_updated.setInventoryStatus("Warehouse Id is not found");
                               }
                               else if("AVAILABLE".equalsIgnoreCase(orderItem.getStatus())) {
                                   dto_updated.setProductName(orderItem.getProductName());
                                   dto_updated.setPrice(orderItem.getPrice());
                               }
                                return dto_updated;
                            })
                            .collect(Collectors.toList());
            //set Http Status
            HttpStatus status = evaluateStatus(inventoryResponse);
            OrderResponseDTO response = new OrderResponseDTO();
            if(status == HttpStatus.CREATED || status == HttpStatus.PARTIAL_CONTENT){
                // ✅ Step 6: update order status
                OrderStatus finalStatus = deriveOrderStatus(inventoryResponse);
                order.setStatus(finalStatus.name());

                Orders savedOrder = orderRepository.save(order);
                updateOrderStatusHistory(savedOrder);

                log.info("Order updated with ID: {}", savedOrder.getOrderId());

                // ✅ Step 7: send ONLY valid items to Kafka
                sendKafkaEventReduceInventory(savedOrder);
                response = orderMapper.mapToResponseDTO(savedOrder, itemResponseDTOList);
            }
            else {
                response.setStatus(inventoryResponse.getStatus());
                response.setItems(itemResponseDTOList);
            }
            ResponseEntity responseEntity = ResponseEntity.status(status).body(response);
            return responseEntity;
        }
        catch(ResourceNotFoundException ex)
        {
            throw ex;
        }
        catch (InsufficientStockException ex) {
            throw ex;
        }
        //kafka processing
        catch(TimeoutException exception){
            throw new RuntimeException("Inventory Service Timeout");
        }
        catch (InvalidPaymentMethodException exception) {
            throw new InvalidPaymentMethodException(exception.getMessage());
        }
        // ✅ Handle DB exceptions
        catch (DataIntegrityViolationException ex) {
            throw new OrderProcessingException("Database error while creating order");
        }
        // ✅ Catch unexpected errors
        catch (Exception ex) {
            log.error("Unexpected error while creating order", ex);
            throw new OrderProcessingException("Unable to create order. Please try again");
        }
    }

    private HttpStatus evaluateStatus(InventoryCheckResponseEvent inventoryResponse) {
        List<OrderItemEventResponse> response = inventoryResponse.getOrderItemCheckResponse();
        int availableCount = 0;
        int invalidItemCount = 0;

        for (OrderItemEventResponse i : response) {
            if ("AVAILABLE".equalsIgnoreCase(i.getStatus())) {
                availableCount++;
            } else if ("INVALID_PRODUCT".equalsIgnoreCase(i.getStatus())
                    || "INVALID_WAREHOUSE".equalsIgnoreCase(i.getStatus())) {
                invalidItemCount++;
            }
        }
        int count = response.size();
        if (availableCount == count) {
            return HttpStatus.CREATED;
        } else if (availableCount > 0)
            return HttpStatus.PARTIAL_CONTENT;
        else if (availableCount == 0 && invalidItemCount > 0)
            return HttpStatus.NOT_FOUND;
        else
            return HttpStatus.CONFLICT;
    }

    private OrderStatus deriveOrderStatus(InventoryCheckResponseEvent response) {

        boolean allAvailable = true;
        boolean anyAvailable = false;

        for (var item : response.getOrderItemCheckResponse()) {

            if ("AVAILABLE".equalsIgnoreCase(item.getStatus())) {
                anyAvailable = true;
            } else {
                allAvailable = false;
            }
        }

        if (allAvailable) return OrderStatus.CONFIRMED;
        if (anyAvailable) return OrderStatus.PARTIAL;
        return OrderStatus.FAILED;
    }

    private void updateOrderStatusHistory(Orders savedOrder)
    {
        //Update OrderStatusHistory Table
        OrdersStatusHistory statusHistory = new OrdersStatusHistory();
        statusHistory.setOrder(savedOrder);
        statusHistory.setStatus(savedOrder.getStatus());
        statusHistory.setChangedBy("USER");
        OrdersStatusHistory savedstatusHistory = statusHistoryRepository.save(statusHistory);
    }

    //Send kafka event to kafka topic
    private void sendKafkaMessageToInventory(List<OrderItem>  itemDTO,String eventId)
    {
        InventoryCheckEvent event = InventoryCheckEventMapper.buildInventoryCheckEvent(itemDTO, eventId);
        producerService.publishInventoryCheckEvent(event);
    }


    //  FIX 7: Extracted Kafka logic (clean + reusable)
    private void sendKafkaEventReduceInventory(Orders order) {
        try {
            OrderCreatedEvent event = buildOrderCreatedEvent(order);
            producerService.publishOrderCreatedEvent(event);
            log.info("Kafka event sent for orderId={}", order.getOrderId());
        } catch (Exception e) {
            log.error("Kafka publishing failed for orderId={}", order.getOrderId(), e);
            // 🔥 Future: Outbox pattern / retry queue
        }
    }

    /**
     * Builds OrderCreatedEvent from saved order entity
     */
    private OrderCreatedEvent buildOrderCreatedEvent(Orders order) {


        // Build item events
        List<OrderCreatedEvent.OrderItemEvent> itemEvents = order.getOrderItems().stream()
                .map(item -> OrderCreatedEvent.OrderItemEvent.builder()
                        .orderItemId((long) item.getOrderItemId())
                        .productId(item.getProduct())
                        .quantity(item.getQuantity())
                        .price(item.getPrice())
                        .warehouseId(item.getWarehouse())
                        .build())
                .collect(Collectors.toList());

        // Build main event
        return OrderCreatedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .orderId((long) order.getOrderId())
                .customerId(order.getCustomer() != null ? order.getCustomer().getCustomerId() : null)
                .customerName(order.getCustomer() != null ? order.getCustomer().getName() : null)
                .guestName(order.getGuestName())
                .guestEmail(order.getGuestEmail())
                .guestPhone(order.getGuestPhone())
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus())
                .timestamp(LocalDateTime.now())
                .items(itemEvents)
                .build();
    }

    //  FIX 8: Strong validation logic
    private void validateCustomerOrGuest(OrderRequestDTO dto) {

        if (dto.getCustomerId() == null) {
            if (dto.getGuestName() == null || dto.getGuestEmail() == null) {
                throw new CustomerOrGuestValidationException("Either customerId OR guestName & guestEmail must be provided");
            }
        }
    }


    // Get All Orders
   public List<OrderResponseDTO> getAllOrders() {
        return orderRepository.findAll().stream().map(orderMapper::mapToResponseDTO).collect(Collectors.toList());
    }

    // Get Order By ID
    public OrderResponseDTO getOrderById(int id) {
        Orders order = orderRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + id));
        return orderMapper.mapToResponseDTO(order);
    }
    // Cancel Order - we need to use WebClient to call inventory service method
  /*  public OrderResponseDTO cancelOrder(int orderId) {

        Orders order = orderRepository.findById(orderId).orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

        //  FIX 9: Prevent invalid state transition
        if (OrderStatus.CANCELLED.name().equals(order.getStatus())) {
            throw new InvalidOrderStatusException("Order already cancelled");
        }
        order.setStatus(OrderStatus.CANCELLED.name());
        Orders updatedOrder = orderRepository.save(order);

        // ✅ Restore inventory for each item (gracefully handle missing inventory)
        order.getOrderItems()
                .stream()
                .forEach(item -> {
                    boolean restored = inventoryService.restoreInventoryIfExists(
                            item.getProduct().getProductId(),
                            item.getWarehouse().getWarehouseId(),
                            item.getQuantity()
                    );
                    if (!restored) {
                        log.warn("Could not restore inventory for cancelled order {}: productId={}, warehouseId={}",
                                orderId, item.getProduct().getProductId(), item.getWarehouse().getWarehouseId());
                    }
                });
        List<Payment> payments = paymentRepository.findByOrder(updatedOrder);

        if (!payments.isEmpty()) {
            Payment payment = payments.get(0);

            if (payment.getPaymentMethod() == PaymentMethod.ONLINE) {
                payment.setPaymentMethod(PaymentMethod.REFUND);
                paymentRepository.save(payment);
            }

        }
        return orderMapper.mapToResponseDTO(updatedOrder);
    }*/

    /*public OrderStatusUpdateResponseDTO updateOrderStatus(int orderId, String status) {

        Orders order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        // ✅ Convert input status → Enum
        OrderStatus newStatus;
        try {
            newStatus = OrderStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new InvalidOrderStatusException("Not a valid status");
        }

        // ✅ Convert DB status (String → Enum)
        OrderStatus currentStatus;
        try {
            currentStatus = OrderStatus.valueOf(order.getStatus().toUpperCase());
        } catch (Exception e) {
            throw new InvalidOrderStatusException("Invalid status in DB: " + order.getStatus());
        }

        // ✅ Prevent same status update
        if (currentStatus == newStatus) {
            throw new InvalidOrderStatusException(
                    "Order is already in "+currentStatus+" status."
            );
        }

        // ✅ Handle CANCELLED separately
        if (newStatus == OrderStatus.CANCELLED) {

            if (currentStatus == OrderStatus.SHIPPED ||
                    currentStatus == OrderStatus.CANCELLED) {

                throw new InvalidOrderStatusException(
                        "Order cannot be CANCELLED when status is " + currentStatus

                );
            }

            cancelOrder(orderId);
        }

        // ✅ Forward flow validation
        if (!isValidTransition(currentStatus, newStatus)) {
            throw new InvalidOrderStatusException(
                    "Invalid status transition from " + currentStatus + " to " + newStatus
            );
        }

        // ✅ Update status
        order.setStatus(newStatus.name());
        orderRepository.save(order);

        // ✅ Save history
        OrdersStatusHistory history = new OrdersStatusHistory();
        history.setOrder(order);
        history.setStatus(newStatus.name());
        history.setChangedBy("USER");
        statusHistoryRepository.save(history);

        // ✅ Response
        OrderStatusUpdateResponseDTO response = new OrderStatusUpdateResponseDTO();
        response.setMessage("Order status updated successfully");
        response.setId(orderId);
        Optional<Orders> orders = orderRepository.findById(orderId);
        response.setStatus(orders.get().getStatus());

        return response;
    }

    // ✅ New method: Check and update status conditionally (for Kafka consumer)
    @Transactional
    public boolean updateOrderStatusIfNotAlready(int orderId, OrderStatus targetStatus) {
        Orders order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        // If already in target status, return false (no update needed)
        if (targetStatus.name().equals(order.getStatus())) {
            return false;
        }

        // Update the status
        OrderStatusUpdateResponseDTO result = updateOrderStatus(orderId, targetStatus.name());
        return result != null;
    }

    // ✅ NEW: Update order items with inventory processing results
    @Transactional
    public void updateOrderItemsWithInventoryResults(int orderId, List<com.oms.event.InventoryUpdatedEvent.ItemResult> itemResults) {
        Orders order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        if (itemResults == null || itemResults.isEmpty()) {
            log.warn("No item results provided for orderId={}", orderId);
            return;
        }

        // ✅ Map item results by productId for quick lookup
        Map<Integer, com.oms.event.InventoryUpdatedEvent.ItemResult> resultsByProductId = itemResults.stream()
                .collect(Collectors.toMap(
                        com.oms.event.InventoryUpdatedEvent.ItemResult::getProductId,
                        result -> result,
                        (existing, replacement) -> existing
                ));

        // ✅ Update each order item with its inventory status
        for (OrderItem item : order.getOrderItems()) {
            com.oms.event.InventoryUpdatedEvent.ItemResult result = resultsByProductId.get(item.getProduct().getProductId());

            if (result != null) {
                item.setInventoryStatus(result.getStatus());
                item.setAvailableQuantity(result.getAvailableQuantity());
                log.info("Updated orderItem {} with status={}, availableQty={}",
                        item.getOrderItemId(), result.getStatus(), result.getAvailableQuantity());
            } else {
                log.warn("No inventory result found for productId={} in order {}",
                        item.getProduct().getProductId(), orderId);
            }
        }

        // ✅ Save the updated items
        orderRepository.save(order);
    }

    private boolean isValidTransition(OrderStatus current, OrderStatus next) {

        switch (current) {

            case CREATED:
                return next == OrderStatus.PARTIAL ||
                        next == OrderStatus.PROCESSED ||
                        next == OrderStatus.CANCELLED;

            case PARTIAL:
                return next == OrderStatus.PROCESSED ||
                        next == OrderStatus.SHIPPED ||
                        next == OrderStatus.CANCELLED;

            case PROCESSED:
                return next == OrderStatus.SHIPPED ||
                        next == OrderStatus.CANCELLED;

            case SHIPPED:
                return false; // no further transitions

            case CANCELLED:
                return false; // terminal state

            default:
                return false;
        }
    }*/
}

