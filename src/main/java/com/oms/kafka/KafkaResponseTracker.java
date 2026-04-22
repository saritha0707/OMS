package com.oms.kafka;

import com.oms.event.InventoryCheckResponseEvent;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class KafkaResponseTracker {
    private final Map<String, CompletableFuture<InventoryCheckResponseEvent>> map = new ConcurrentHashMap<>();

    public CompletableFuture<InventoryCheckResponseEvent> register(String eventId)
    {
        CompletableFuture<InventoryCheckResponseEvent> future = new CompletableFuture<>();
        map.put(eventId,future);
        return future;
    }

    public void complete(String eventId, InventoryCheckResponseEvent response)
    {
        CompletableFuture<InventoryCheckResponseEvent> future = map.remove(eventId);
        if(future != null)
        {
            future.complete(response);
        }
    }
}
