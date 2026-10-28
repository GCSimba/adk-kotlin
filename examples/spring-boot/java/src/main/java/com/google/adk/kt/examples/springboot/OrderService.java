/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.kt.examples.springboot;

import com.google.adk.kt.annotations.Param;
import com.google.adk.kt.annotations.Tool;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The store's order system, kept in memory where a real app would call its database or carrier. The
 * support agents call its {@code @Tool} methods; the workflow's code calls the others directly.
 */
@Service
class OrderService {

  private static final long REFUND_WINDOW_DAYS = 30;

  /** An order, with {@code deliveredOn} set to {@code null} while in transit. */
  record Order(String id, String item, @Nullable LocalDate deliveredOn) {}

  /** The refund policy's decision for an order, with the reason the customer is given. */
  enum RefundDecision {
    APPROVED("approved"),
    NO_ORDER("denied, because there is no order with this number"),
    NOT_DELIVERED("denied, because the order has not been delivered yet"),
    WINDOW_CLOSED(
        "denied, because the order was delivered more than " + REFUND_WINDOW_DAYS + " days ago");

    private final String description;

    RefundDecision(String description) {
      this.description = description;
    }

    String description() {
      return description;
    }
  }

  private final Map<String, Order> orders;

  OrderService() {
    LocalDate today = today();
    orders =
        Map.of(
            "1042", new Order("1042", "noise-cancelling headphones", /* deliveredOn= */ null),
            "1043", new Order("1043", "espresso machine", today.minusDays(5)),
            "1044", new Order("1044", "running shoes", today.minusDays(45)));
  }

  @Nullable Order findOrder(String id) {
    return orders.get(id);
  }

  /**
   * Applies the store's refund policy, which allows a refund within 30 days of delivery, to the
   * order with {@code orderId}.
   */
  RefundDecision decideRefund(String orderId) {
    Order order = orders.get(orderId);
    if (order == null) {
      return RefundDecision.NO_ORDER;
    }
    if (order.deliveredOn() == null) {
      return RefundDecision.NOT_DELIVERED;
    }
    return order.deliveredOn().isBefore(today().minusDays(REFUND_WINDOW_DAYS))
        ? RefundDecision.WINDOW_CLOSED
        : RefundDecision.APPROVED;
  }

  @Tool(description = "Returns the carrier's latest tracking status for an order.")
  public Map<String, String> trackShipment(
      @Param(name = "orderId", description = "The order number, such as 1042") String orderId) {
    Order order = orders.get(orderId);
    if (order == null) {
      return Map.of("error", "There is no order with this number.");
    }
    if (order.deliveredOn() != null) {
      return Map.of(
          "carrier",
          "Example Express",
          "status",
          "Delivered",
          "deliveredOn",
          order.deliveredOn().toString());
    }
    return Map.of(
        "carrier",
        "Example Express",
        "status",
        "Out for delivery",
        "lastScan",
        "Brooklyn, NY",
        "estimatedDelivery",
        "today by 8 pm");
  }

  @Tool(
      description =
          "Creates a prepaid return label for an order that can be refunded, and returns its"
              + " number.")
  public Map<String, String> createReturnLabel(
      @Param(name = "orderId", description = "The order number, such as 1043") String orderId) {
    // Checks the policy again, so the model cannot create a label that the policy forbids.
    if (decideRefund(orderId) != RefundDecision.APPROVED) {
      return Map.of("error", "This order cannot be refunded.");
    }
    return Map.of("labelNumber", "RL-" + orderId);
  }

  private static LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }
}
