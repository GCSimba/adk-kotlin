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

package com.google.adk.kt.examples.springboot

import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool
import java.time.LocalDate
import java.time.ZoneOffset
import org.springframework.stereotype.Service

/**
 * The store's order system, kept in memory where a real app would call its database or carrier. The
 * support agents call its `@Tool` functions; the workflow's code calls the others directly.
 */
@Service
class OrderService {

  /** An order, with [deliveredOn] set to `null` while in transit. */
  data class Order(val id: String, val item: String, val deliveredOn: LocalDate?)

  /** The refund policy's decision for an order, with the reason the customer is given. */
  enum class RefundDecision(val description: String) {
    APPROVED("approved"),
    NO_ORDER("denied, because there is no order with this number"),
    NOT_DELIVERED("denied, because the order has not been delivered yet"),
    WINDOW_CLOSED("denied, because the order was delivered more than $REFUND_WINDOW_DAYS days ago"),
  }

  private val orders: Map<String, Order> =
    listOf(
        Order("1042", "noise-cancelling headphones", deliveredOn = null),
        Order("1043", "espresso machine", today().minusDays(5)),
        Order("1044", "running shoes", today().minusDays(45)),
      )
      .associateBy { it.id }

  fun findOrder(id: String): Order? = orders[id]

  /**
   * Applies the store's refund policy, which allows a refund within 30 days of delivery, to the
   * order with [orderId].
   */
  fun decideRefund(orderId: String): RefundDecision {
    val order = orders[orderId] ?: return RefundDecision.NO_ORDER
    val deliveredOn = order.deliveredOn ?: return RefundDecision.NOT_DELIVERED
    return if (deliveredOn.isBefore(today().minusDays(REFUND_WINDOW_DAYS))) {
      RefundDecision.WINDOW_CLOSED
    } else {
      RefundDecision.APPROVED
    }
  }

  /** Returns the carrier's latest tracking status for an order. */
  @Tool
  fun trackShipment(@Param("The order number, such as 1042") orderId: String): Map<String, String> {
    val order = orders[orderId] ?: return mapOf("error" to "There is no order with this number.")
    val deliveredOn = order.deliveredOn
    if (deliveredOn != null) {
      return mapOf(
        "carrier" to "Example Express",
        "status" to "Delivered",
        "deliveredOn" to deliveredOn.toString(),
      )
    }
    return mapOf(
      "carrier" to "Example Express",
      "status" to "Out for delivery",
      "lastScan" to "Brooklyn, NY",
      "estimatedDelivery" to "today by 8 pm",
    )
  }

  /** Creates a prepaid return label for an order that can be refunded, and returns its number. */
  @Tool
  fun createReturnLabel(
    @Param("The order number, such as 1043") orderId: String
  ): Map<String, String> {
    // Checks the policy again, so the model cannot create a label that the policy forbids.
    if (decideRefund(orderId) != RefundDecision.APPROVED) {
      return mapOf("error" to "This order cannot be refunded.")
    }
    return mapOf("labelNumber" to "RL-$orderId")
  }

  private fun today(): LocalDate = LocalDate.now(ZoneOffset.UTC)

  companion object {
    private const val REFUND_WINDOW_DAYS = 30L
  }
}
