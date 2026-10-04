package com.jlees.budgey.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
data object CalendarRoute

/** Purchases list. With [categoryId] it opens pre-filtered (pushed from a category folder). */
@Serializable
data class PurchasesRoute(
    val categoryId: String? = null,
    val uncategorized: Boolean = false,
    /** Opened from Settings → Payment methods. */
    val paymentMethodId: String? = null,
)

/** Category folder browser. null = top level. */
@Serializable
data class CategoriesRoute(val folderId: String? = null)

@Serializable
data object SubscriptionsRoute

@Serializable
data object SettingsRoute

/** Tools hub (from the ☰ menu) and the tools it opens. */
@Serializable
data object ToolsRoute

@Serializable
data object CheckSplitRoute

@Serializable
data object TipCalculatorRoute

@Serializable
data object CurrencyRoute

/** Settings → Scanner & AI models, opened on its own (e.g. from a scan); Back returns to where you were. */
@Serializable
data object ScannerSettingsRoute

@Serializable
data class PurchaseEditRoute(
    val id: String? = null,
    val fromScan: Boolean = false,
    val categoryId: String? = null,
    /** ISO date to pre-fill (from the calendar). */
    val date: String? = null,
    /** Reopen the unfinished new purchase. */
    val resume: Boolean = false,
)

@Serializable
data class SubscriptionEditRoute(val id: String? = null, val fromScan: Boolean = false, val resume: Boolean = false)

/**
 * mode = "scan" (camera-or-gallery chooser) | "shared" (an image URI) | "resume" (the unfinished scan
 * review for [target]); target = "auto" | "purchase" | "subscription".
 */
@Serializable
data class ScanRoute(val mode: String = "scan", val target: String = "auto", val sharedUri: String? = null)

@Serializable
data object ImportRoute

@Serializable
data object PaymentMethodsRoute
