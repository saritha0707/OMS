package com.oms.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.stereotype.Component;

@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "kafka.topics")
@ComponentScan
public class KafkaTopicsConfig {
  public String inventoryCheckRequest;
  public String inventoryCheckResponse;
  public String orderEvents;
}
