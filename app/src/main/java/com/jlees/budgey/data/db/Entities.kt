package com.jlees.budgey.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.jlees.budgey.domain.BillingCycle
import com.jlees.budgey.domain.BudgetPeriod
import com.jlees.budgey.domain.CycleUnit
import java.time.LocalDate
import java.util.UUID

/*
 * IDs are UUID strings rather than auto-increment ints. That's deliberate: exports from
 * one device can be merged into another without ID collisions, and the same record can
 * be recognised on re-import (skip / replace instead of duplicating).
 */

fun newId(): String = UUID.randomUUID().toString()

/**
 * A category is a folder. It can contain sub-categories (parentId) and can optionally
 * carry a budget. A parent's budget counts spending in all of its descendants.
 */
@Entity(tableName = "categories", indices = [Index("parentId")])
data class CategoryEntity(
    @PrimaryKey val id: String = newId(),
    val name: String,
    val parentId: String? = null,
    /** Key into CategoryIcons (Material symbol name). */
    val icon: String = "folder",
    /** ARGB color. */
    val color: Int = 0xFF6750A4.toInt(),
    /** Null = no budget for this category. */
    val budgetCents: Long? = null,
    val budgetPeriod: BudgetPeriod = BudgetPeriod.MONTHLY,
    val sortOrder: Int = 0,
    /** Stable key for built-in defaults ("groceries"), used to offer re-adding them. */
    val templateKey: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : java.io.Serializable

enum class PurchaseSource { MANUAL, SCAN, SUBSCRIPTION, IMPORT }

@Entity(
    tableName = "purchases",
    indices = [Index("categoryId"), Index("date"), Index("subscriptionId")],
)
data class PurchaseEntity(
    @PrimaryKey val id: String = newId(),
    val merchant: String,
    /** Positive = money spent. Negative = refund / return. */
    val amountCents: Long,
    val date: LocalDate,
    val categoryId: String? = null,
    val note: String = "",
    /** Display name snapshot of the payment method (kept for exports / older data). */
    val paymentMethod: String = "",
    /** The saved payment method (see PaymentMethodEntity). (DB v5) */
    val paymentMethodId: String? = null,
    /**
     * Icon override. null = auto-detect from merchant name, "" = never show a brand
     * (use the category icon), otherwise a brand id from the brand catalog.
     */
    val brandKey: String? = null,
    /** File name inside filesDir/receipts. */
    val receiptFile: String? = null,
    val source: PurchaseSource = PurchaseSource.MANUAL,
    val subscriptionId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

enum class SubscriptionStatus(val label: String) { ACTIVE("Active"), TRIAL("Free trial"), PAUSED("Paused"), CANCELLED("Cancelled") }

@Entity(tableName = "subscriptions", indices = [Index("categoryId")])
data class SubscriptionEntity(
    @PrimaryKey val id: String = newId(),
    val name: String,
    val amountCents: Long,
    val cycleUnit: CycleUnit = CycleUnit.MONTH,
    val cycleCount: Int = 1,
    /** Billing anchor (first charge). Future dates are computed from this to keep month-ends stable. */
    val anchorDate: LocalDate,
    val nextDueDate: LocalDate,
    val categoryId: String? = null,
    val brandKey: String? = null,
    val status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
    /** Automatically create a purchase every time a billing date passes. */
    val autoLog: Boolean = true,
    /** End of a free trial (status TRIAL) — shown as a warning before it converts. */
    val trialEndDate: LocalDate? = null,
    val paymentMethod: String = "",
    val note: String = "",
    val receiptFile: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Renewal reminder lead time in days. null = use the app default, negative = no reminders. (DB v2) */
    val reminderDays: Int? = null,
    /** Base / advertised price (e.g. 19.99). null = only the total is known. (DB v3) */
    val listPriceCents: Long? = null,
    /** Legacy (v3): a tax percentage. Superseded by [feesCents]; kept only so old databases still match. */
    val taxRatePercent: Double? = null,
    /** Fees & taxes added on top of the base price, in cents (amountCents = listPriceCents + feesCents). (DB v4) */
    val feesCents: Long? = null,
    /** The saved payment method (see PaymentMethodEntity). (DB v5) */
    val paymentMethodId: String? = null,
    /** When a paused/cancelled subscription stopped billing (inclusive last day). (DB v6) */
    val endDate: LocalDate? = null,
)

/**
 * An earlier stretch of a subscription's history with its own price / cycle — e.g. a student
 * discount from Feb to Jun before the full price kicked in, or the run before a cancellation.
 * The subscription row itself always describes the *current* period (from its anchorDate on).
 */
@Entity(tableName = "subscription_periods", indices = [Index("subscriptionId")])
data class SubscriptionPeriodEntity(
    @PrimaryKey val id: String = newId(),
    val subscriptionId: String,
    /** First billing date of this period (later billing dates step from here). */
    val startDate: LocalDate,
    /** Last day of this period, inclusive. */
    val endDate: LocalDate,
    /** Total charged per billing in this period. */
    val amountCents: Long,
    val cycleUnit: CycleUnit = CycleUnit.MONTH,
    val cycleCount: Int = 1,
    /** Optional note, e.g. "Student plan". */
    val label: String = "",
    val createdAt: Long = System.currentTimeMillis(),
) : java.io.Serializable

val SubscriptionPeriodEntity.cycle: BillingCycle get() = BillingCycle(cycleUnit, cycleCount.coerceAtLeast(1))

/**
 * A card, bank account or wallet you pay with — managed in Settings so names are never mistyped.
 * [icon] is "brand:<brandId>", "generic:<key>" or "image:<file>" (a picture you chose, in filesDir/icons).
 */
@Entity(tableName = "payment_methods")
data class PaymentMethodEntity(
    @PrimaryKey val id: String = newId(),
    val name: String,
    val icon: String = "generic:card",
    /** Optional last 4 digits, shown as ••1234 to tell cards apart. */
    val last4: String? = null,
    val sortOrder: Int = 0,
    /** Hidden from pickers but kept so old purchases still show it. */
    val archived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) : java.io.Serializable

val PaymentMethodEntity.displayName: String
    get() = if (last4.isNullOrBlank()) name else "$name ••$last4"

// Kept as extensions (not members) so Room doesn't try to map them to columns.
val SubscriptionEntity.cycle: BillingCycle get() = BillingCycle(cycleUnit, cycleCount.coerceAtLeast(1))
val SubscriptionEntity.monthlyCents: Long get() = cycle.monthlyCost(amountCents)
val SubscriptionEntity.yearlyCents: Long get() = cycle.yearlyCost(amountCents)
val SubscriptionEntity.isLive: Boolean get() = status == SubscriptionStatus.ACTIVE || status == SubscriptionStatus.TRIAL
