package com.jlees.budgey.data.backup

import kotlinx.serialization.Serializable

/*
 * Export file format (a .zip):
 *   data.json        — this BackupFile as JSON
 *   receipts/<name>  — receipt / screenshot images referenced by receiptFile
 *
 * Design notes
 * - Plain, documented JSON so a future iOS/desktop/web version can read it.
 * - Dates are ISO-8601 strings (2026-10-01), money is integer cents, colors are #AARRGGBB.
 * - Every record keeps its UUID so re-importing can recognise duplicates.
 * - Categories also carry their full name path, so they can be matched by name on another
 *   device even when the IDs differ.
 * - schemaVersion lets newer app versions migrate older exports. Unknown fields are ignored.
 */

const val BACKUP_FORMAT = "budgey-backup"

/** Backups made before the app was renamed Budgey still import. */
val LEGACY_BACKUP_FORMATS = setOf("budget-tracker-backup")
const val BACKUP_SCHEMA_VERSION = 1

@Serializable
data class BackupFile(
    val format: String = BACKUP_FORMAT,
    val schemaVersion: Int = BACKUP_SCHEMA_VERSION,
    val appVersion: String = "",
    val exportedAt: String = "",
    val device: String = "",
    val currency: String = "USD",
    val categories: List<CategoryDto> = emptyList(),
    val purchases: List<PurchaseDto> = emptyList(),
    val subscriptions: List<SubscriptionDto> = emptyList(),
    val settings: SettingsDto? = null,
    val paymentMethods: List<PaymentMethodDto> = emptyList(),
    val subscriptionPeriods: List<SubscriptionPeriodDto> = emptyList(),
)

/** An earlier price/cycle stretch of a subscription. Dates are ISO strings; endDate is inclusive. */
@Serializable
data class SubscriptionPeriodDto(
    val id: String,
    val subscriptionId: String,
    val startDate: String,
    val endDate: String,
    val amountCents: Long,
    val cycleUnit: String = "MONTH",
    val cycleCount: Int = 1,
    val label: String = "",
    val createdAt: Long = 0,
)

/** icon: "brand:<id>", "generic:<key>" or "image:<file>" (the file travels in icons/ inside the zip). */
@Serializable
data class PaymentMethodDto(
    val id: String,
    val name: String,
    val icon: String = "generic:card",
    val last4: String? = null,
    val sortOrder: Int = 0,
    val archived: Boolean = false,
    val createdAt: Long = 0,
)

@Serializable
data class CategoryDto(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val path: List<String> = emptyList(),
    val icon: String = "folder",
    val color: String = "#FF6750A4",
    val budgetCents: Long? = null,
    val budgetPeriod: String = "MONTHLY",
    val sortOrder: Int = 0,
    val templateKey: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

@Serializable
data class PurchaseDto(
    val id: String,
    val merchant: String,
    val amountCents: Long,
    val date: String,
    val categoryId: String? = null,
    val note: String = "",
    val paymentMethod: String = "",
    val brandKey: String? = null,
    val receiptFile: String? = null,
    val source: String = "MANUAL",
    val subscriptionId: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val paymentMethodId: String? = null,
)

@Serializable
data class SubscriptionDto(
    val id: String,
    val name: String,
    val amountCents: Long,
    val cycleUnit: String = "MONTH",
    val cycleCount: Int = 1,
    val anchorDate: String,
    val nextDueDate: String,
    val categoryId: String? = null,
    val brandKey: String? = null,
    val status: String = "ACTIVE",
    val autoLog: Boolean = true,
    val trialEndDate: String? = null,
    val paymentMethod: String = "",
    val note: String = "",
    val receiptFile: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val reminderDays: Int? = null,
    val listPriceCents: Long? = null,
    val taxRatePercent: Double? = null,
    val feesCents: Long? = null,
    val paymentMethodId: String? = null,
    val endDate: String? = null,
)

@Serializable
data class SettingsDto(
    val themeMode: String = "SYSTEM",
    val dynamicColor: Boolean = true,
    val seedColor: String = "#FF2E5E4E",
    val amoled: Boolean = false,
    val chartType: String = "DONUT",
    val firstDayOfWeek: String = "SUNDAY",
    /** Legacy (auto-log is per subscription now); kept so old backups still parse. */
    val autoLogSubscriptions: Boolean = true,
    val nudgeUncategorized: Boolean = true,
    val renewalReminders: Boolean = true,
    val reminderDaysBefore: Int = 1,
    val font: String = "GOOGLE_SANS_FLEX",
    val roundedFont: Boolean = false,
    val textSize: String = "DEFAULT",
)
