package com.jlees.budgey.data

import androidx.room.withTransaction
import com.jlees.budgey.data.db.AppDatabase
import com.jlees.budgey.data.db.CategoryEntity
import com.jlees.budgey.data.db.PurchaseEntity
import com.jlees.budgey.data.db.PaymentMethodEntity
import com.jlees.budgey.data.db.displayName
import com.jlees.budgey.data.db.PurchaseSource
import com.jlees.budgey.data.db.SubscriptionEntity
import com.jlees.budgey.data.db.SubscriptionPeriodEntity
import com.jlees.budgey.data.db.SubscriptionStatus
import com.jlees.budgey.data.db.cycle
import com.jlees.budgey.domain.CategoryTree
import com.jlees.budgey.domain.Renewals
import com.jlees.budgey.data.db.isLive
import com.jlees.budgey.icons.BrandCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import kotlinx.coroutines.sync.withLock

/** Pending changes to a subscription's logged payments (see [BudgetRepository.planHistorySync]). */
data class HistorySync(
    val toAdd: List<PurchaseEntity>,
    val toUpdate: List<PurchaseEntity>,
    val toRemove: List<PurchaseEntity>,
) {
    val isEmpty: Boolean get() = toAdd.isEmpty() && toUpdate.isEmpty() && toRemove.isEmpty()
}

/** Note on payments the app logs for a subscription. */
const val AUTO_NOTE = "Auto-logged subscription payment"
/** …and on one logged on a next-payment date set by hand (off the usual rhythm). */
const val HAND_SET_NOTE = "Auto-logged subscription payment (date set by hand)"

/** Single source of truth for all budget data. Screens observe the Flows; writes go through here. */
class BudgetRepository(
    private val db: AppDatabase,
    val receipts: ReceiptStore,
    private val brands: BrandCatalog,
    val paymentIcons: ReceiptStore,
) {
    private val categoryDao = db.categoryDao()
    private val purchaseDao = db.purchaseDao()
    private val subscriptionDao = db.subscriptionDao()
    private val paymentMethodDao = db.paymentMethodDao()
    private val periodDao = db.subscriptionPeriodDao()

    val categories: Flow<List<CategoryEntity>> = categoryDao.observeAll()
    val categoryTree: Flow<CategoryTree> = categories.map { CategoryTree(it) }
    val purchases: Flow<List<PurchaseEntity>> = purchaseDao.observeAll()
    val subscriptions: Flow<List<SubscriptionEntity>> = subscriptionDao.observeAll()
    val merchants: Flow<List<String>> = purchaseDao.observeMerchants()
    /** Saved payment methods (including archived ones, last). */
    val paymentMethods: Flow<List<PaymentMethodEntity>> = paymentMethodDao.observeAll()

    // ---------- Purchases ----------

    suspend fun purchase(id: String) = purchaseDao.get(id)

    suspend fun savePurchase(p: PurchaseEntity) =
        purchaseDao.upsert(p.copy(updatedAt = System.currentTimeMillis()))

    suspend fun deletePurchase(p: PurchaseEntity) = purchaseDao.delete(p)

    /** Bulk delete in one transaction (all or nothing). */
    suspend fun deletePurchases(list: List<PurchaseEntity>) = db.withTransaction { purchaseDao.deleteMany(list) }

    /** Puts deleted purchases back (Undo), in one transaction. */
    suspend fun restorePurchases(list: List<PurchaseEntity>) = db.withTransaction { purchaseDao.upsertAll(list) }

    suspend fun setCategory(purchaseIds: List<String>, categoryId: String?) =
        purchaseDao.setCategory(purchaseIds, categoryId, System.currentTimeMillis())

    /**
     * Suggests a category for a merchant: first whatever you used last time for that
     * merchant, then the brand's default category (if that default category still exists).
     */
    suspend fun suggestCategory(merchant: String, brandKey: String? = null): String? {
        if (merchant.isBlank()) return null
        purchaseDao.lastCategoryForMerchant(merchant.trim())?.let { id ->
            if (categoryDao.get(id) != null) return id
        }
        val brand = brands.resolve(brandKey, merchant) ?: return null
        val key = brand.category ?: return null
        return categoryDao.getAll().firstOrNull { it.templateKey == key }?.id
    }

    /** The user's category matching a brand's default category (e.g. Netflix → Streaming). */
    suspend fun categoryForBrand(brand: com.jlees.budgey.icons.Brand): String? {
        val key = brand.category ?: return null
        return categoryDao.getAll().firstOrNull { it.templateKey == key }?.id
    }

    // ---------- Payment methods ----------

    suspend fun paymentMethod(id: String) = paymentMethodDao.get(id)

    /** Saves a method; renaming also updates the name shown on everything already linked to it. */
    suspend fun savePaymentMethod(m: PaymentMethodEntity) = db.withTransaction {
        val old = paymentMethodDao.get(m.id)
        paymentMethodDao.upsert(m)
        if (old != null && old.displayName != m.displayName) {
            paymentMethodDao.renameOnPurchases(m.id, m.displayName)
            paymentMethodDao.renameOnSubscriptions(m.id, m.displayName)
        }
    }

    /** Deletes a method. Linked items keep showing its name as plain text but lose the link. */
    suspend fun deletePaymentMethod(id: String) = db.withTransaction {
        paymentMethodDao.unlinkPurchases(id)
        paymentMethodDao.unlinkSubscriptions(id)
        paymentMethodDao.delete(id)
    }

    suspend fun suggestPaymentMethod(merchant: String): String? =
        if (merchant.isBlank()) null
        else purchaseDao.lastPaymentMethodForMerchant(merchant.trim())?.takeIf { paymentMethodDao.get(it)?.archived == false }

    // ---------- Categories ----------

    suspend fun category(id: String) = categoryDao.get(id)

    suspend fun saveCategory(c: CategoryEntity) {
        val tree = CategoryTree(categoryDao.getAll())
        require(!tree.wouldCycle(c.id, c.parentId)) { "A category can't be moved inside itself." }
        categoryDao.upsert(c.copy(updatedAt = System.currentTimeMillis()))
    }

    /**
     * Deletes a category. Its sub-categories, purchases, and subscriptions move up to the
     * deleted category's parent (or become uncategorized at the top level) — nothing is lost.
     */
    suspend fun deleteCategory(id: String) = db.withTransaction {
        val cat = categoryDao.get(id) ?: return@withTransaction
        categoryDao.reparentChildren(id, cat.parentId)
        purchaseDao.reassignCategory(id, cat.parentId)
        subscriptionDao.reassignCategory(id, cat.parentId)
        categoryDao.delete(id)
    }

    /** Adds built-in categories (with their children) that aren't already present. */
    suspend fun addTemplates(keys: Set<String>, parentId: String? = null) {
        val existing = categoryDao.getAll().mapNotNull { it.templateKey }.toSet()
        val wanted = DefaultCategories.templates.filter { it.key in keys && it.key !in existing }
        val childOnly = DefaultCategories.templates.flatMap { it.children }
            .filter { it.key in keys && it.key !in existing && wanted.none { w -> w.children.contains(it) } }
        val entities = DefaultCategories.toEntities(wanted.map { t ->
            t.copy(children = t.children.filter { it.key !in existing })
        }, parentId) + DefaultCategories.toEntities(childOnly, parentId)
        categoryDao.upsertAll(entities)
    }

    suspend fun seedDefaults() {
        if (categoryDao.getAll().isEmpty()) {
            categoryDao.upsertAll(DefaultCategories.toEntities(DefaultCategories.templates))
        }
    }

    // ---------- Subscriptions ----------

    suspend fun subscription(id: String) = subscriptionDao.get(id)

    suspend fun allSubscriptions() = subscriptionDao.getAll()

    suspend fun saveSubscription(s: SubscriptionEntity) =
        subscriptionDao.upsert(s.copy(updatedAt = System.currentTimeMillis()))

    /** One-time migration helper (the old global auto-log switch). */
    suspend fun setAutoLogForAll(on: Boolean) = subscriptionDao.setAutoLogForAll(on)

    suspend fun deleteSubscription(s: SubscriptionEntity) = db.withTransaction {
        periodDao.deleteFor(s.id)
        subscriptionDao.delete(s)
    }

    /** All history periods (every subscription) — for the calendar. */
    val subscriptionPeriods: Flow<List<SubscriptionPeriodEntity>> = periodDao.observeAll()

    suspend fun periodsFor(subscriptionId: String) = periodDao.getFor(subscriptionId)

    /** Replaces a subscription's history periods with [periods]. */
    suspend fun replacePeriods(subscriptionId: String, periods: List<SubscriptionPeriodEntity>) = db.withTransaction {
        periodDao.deleteFor(subscriptionId)
        if (periods.isNotEmpty()) periodDao.upsertAll(periods.map { it.copy(subscriptionId = subscriptionId) })
    }

    /**
     * For every live subscription with auto-log on, creates a purchase for each billing date
     * that has passed and moves nextDueDate forward. Safe to call repeatedly (idempotent per date).
     * Returns the number of purchases created.
     */
    /** Set by the app before the first auto-log run (one-time migration of the old global switch). */
    var beforeAutoLog: (suspend () -> Unit)? = null

    /** Only one auto-log pass at a time (start-up, the daily worker and a save can all ask at once). */
    private val autoLogGate = kotlinx.coroutines.sync.Mutex()

    suspend fun processDueSubscriptions(today: LocalDate = LocalDate.now(), logEnabled: Boolean = true): Int =
        autoLogGate.withLock {
            beforeAutoLog?.let { it(); beforeAutoLog = null }
            processDue(today, logEnabled)
        }

    /**
     * Saves a subscription, its price-history periods and (with [syncHistory]) the matching changes to
     * its logged payments in ONE transaction. The payment changes are planned inside it, from what's
     * stored right now — not from a plan made before a confirmation dialog, which could be stale (e.g.
     * the daily job logged a payment meanwhile) and double it.
     */
    suspend fun saveSubscriptionWithHistory(
        sub: SubscriptionEntity,
        periods: List<SubscriptionPeriodEntity>,
        syncHistory: Boolean,
    ) = autoLogGate.withLock {
        db.withTransaction {
            subscriptionDao.upsert(sub.copy(updatedAt = System.currentTimeMillis()))
            periodDao.deleteFor(sub.id)
            if (periods.isNotEmpty()) periodDao.upsertAll(periods.map { it.copy(subscriptionId = sub.id) })
            if (syncHistory) {
                val plan = planHistorySync(sub, periods, logEnabled = true)
                if (plan.toRemove.isNotEmpty()) purchaseDao.deleteMany(plan.toRemove)
                if (plan.toUpdate.isNotEmpty() || plan.toAdd.isNotEmpty()) purchaseDao.upsertAll(plan.toUpdate + plan.toAdd)
            }
        }
    }

    private suspend fun processDue(today: LocalDate, logEnabled: Boolean): Int = db.withTransaction {
        var created = 0
        for (sub in subscriptionDao.getAll()) {
            if (sub.status != SubscriptionStatus.ACTIVE && sub.status != SubscriptionStatus.TRIAL) continue
            // Nothing is charged during a free trial: billing starts when it ends (and an open-ended
            // trial isn't charged at all). A trial that has ended stays marked as a trial until you
            // confirm it on the Subscriptions page, so it gets flagged instead of silently converting.
            val start = Renewals.paidFrom(sub, today) ?: continue
            var due = if (sub.nextDueDate.isBefore(start)) start else sub.nextDueDate
            var guard = 0
            val status = sub.status
            while (!due.isAfter(today) && guard++ < 400) {
                val isTrialCharge = Renewals.isFreeTrialDate(sub, due, today)
                if (logEnabled && sub.autoLog && !isTrialCharge && purchaseDao.countForSubscription(sub.id, due) == 0) {
                    // A next-payment date set by hand (off the usual rhythm) is marked, so a later
                    // history sync knows it's a real payment and doesn't offer to remove it.
                    val onRhythm = sub.cycle.nextOnOrAfter(start, due) == due
                    purchaseDao.upsert(
                        PurchaseEntity(
                            merchant = sub.name,
                            amountCents = sub.amountCents,
                            date = due,
                            categoryId = sub.categoryId,
                            paymentMethod = sub.paymentMethod,
                            paymentMethodId = sub.paymentMethodId,
                            brandKey = sub.brandKey,
                            source = PurchaseSource.SUBSCRIPTION,
                            subscriptionId = sub.id,
                            note = if (onRhythm) AUTO_NOTE else HAND_SET_NOTE,
                        )
                    )
                    created++
                }
                due = sub.cycle.nextOnOrAfter(start, due.plusDays(1))
            }
            if (due != sub.nextDueDate || status != sub.status) {
                subscriptionDao.upsert(sub.copy(nextDueDate = due, status = status, updatedAt = System.currentTimeMillis()))
            }
        }
        created
    }

    /**
     * Works out how a subscription's logged payment history should change to match [sub]:
     * - backfill a purchase for every past billing date since the start date (when auto-log is on),
     * - remove auto-logged payments on dates that are no longer billing dates (start date / cycle changed),
     * - update name, price, category, payment method and icon on the payments that stay.
     * Only auto-logged payments (source = SUBSCRIPTION) are changed; manual ones are left alone.
     * Nothing is written until [applyHistorySync] is called, so the UI can ask first.
     */
    suspend fun planHistorySync(
        sub: SubscriptionEntity,
        periods: List<SubscriptionPeriodEntity>,
        logEnabled: Boolean,
        today: LocalDate = LocalDate.now(),
    ): HistorySync {
        val linked = purchaseDao.getForSubscription(sub.id)
        val auto = linked.filter { it.source == PurchaseSource.SUBSCRIPTION }
        // What each billing date should cost, across the current period and all history periods.
        val priceByDate = Renewals.paidDates(sub, periods, today)
        // Dates are only reconciled when we know where the current period ends (live, or an end date is set).
        val reconcileDates = sub.autoLog && logEnabled && (sub.isLive || sub.endDate != null)
        // Payments logged on a hand-set date stand in for the regular billing date of their cycle.
        val handSet = auto.filter { it.note == HAND_SET_NOTE }
        val expected = if (reconcileDates) {
            val dates = priceByDate.keys.sorted()
            dates.filterIndexed { i, d ->
                val nextD = dates.getOrNull(i + 1)
                handSet.none { h -> !h.date.isBefore(d) && (nextD == null || h.date.isBefore(nextD)) && h.date != d }
            }.toSet()
        } else null
        val toRemove = if (expected != null) auto.filter { it.date !in expected && it.note != HAND_SET_NOTE } else emptyList()
        val removeIds = toRemove.map { it.id }.toSet()
        val now = System.currentTimeMillis()
        val toUpdate = auto.filter { it.id !in removeIds }.mapNotNull { p ->
            val u = p.copy(
                merchant = sub.name, amountCents = priceByDate[p.date] ?: sub.amountCents, categoryId = sub.categoryId,
                paymentMethod = sub.paymentMethod, paymentMethodId = sub.paymentMethodId, brandKey = sub.brandKey,
            )
            if (u != p) u.copy(updatedAt = now) else null
        }
        val datesTaken = linked.filter { it.id !in removeIds }.map { it.date }.toSet()
        val toAdd = expected.orEmpty().filter { it !in datesTaken }.sorted().map { d ->
            PurchaseEntity(
                merchant = sub.name, amountCents = priceByDate[d] ?: sub.amountCents, date = d, categoryId = sub.categoryId,
                paymentMethod = sub.paymentMethod, paymentMethodId = sub.paymentMethodId, brandKey = sub.brandKey,
                source = PurchaseSource.SUBSCRIPTION,
                subscriptionId = sub.id, note = AUTO_NOTE,
            )
        }
        return HistorySync(toAdd, toUpdate, toRemove)
    }

    suspend fun applyHistorySync(plan: HistorySync) = db.withTransaction {
        if (plan.toRemove.isNotEmpty()) purchaseDao.deleteMany(plan.toRemove)
        if (plan.toUpdate.isNotEmpty() || plan.toAdd.isNotEmpty()) purchaseDao.upsertAll(plan.toUpdate + plan.toAdd)
    }

    // ---------- Maintenance ----------

    /**
     * "Keep it" on an ended free trial: it's a normal paid subscription now. The trial's end date is
     * kept, so history still knows the trial months were free.
     */
    suspend fun keepAfterTrial(id: String) {
        val s = subscriptionDao.get(id) ?: return
        if (s.status != SubscriptionStatus.TRIAL) return
        subscriptionDao.upsert(s.copy(status = SubscriptionStatus.ACTIVE, updatedAt = System.currentTimeMillis()))
    }

    /** Deletes a receipt photo once nothing refers to it any more (e.g. after deleting its purchase). */
    suspend fun deleteReceiptIfUnused(name: String?) {
        if (name == null) return
        // Two quick EXISTS lookups, not loading every purchase and subscription per photo.
        if (!purchaseDao.usesReceipt(name) && !subscriptionDao.usesReceipt(name)) receipts.delete(name)
    }

    /** Deletes custom icon pictures nothing uses any more (replaced or deleted items, payment methods). */
    /** [keepKeys] = icon keys still in use elsewhere (unfinished adds). */
    suspend fun cleanupIcons(keepKeys: Set<String> = emptySet(), before: Long = Long.MAX_VALUE) {
        fun image(key: String?) = (com.jlees.budgey.icons.IconRef.parse(key) as? com.jlees.budgey.icons.IconRef.Image)?.file
        val referenced = (purchaseDao.imageIconKeys().mapNotNull { image(it) } +
            subscriptionDao.imageIconKeys().mapNotNull { image(it) } +
            paymentMethodDao.getAll().mapNotNull { image(it.icon) } +
            categoryDao.getAll().mapNotNull { image(it.icon) } +
            keepKeys.mapNotNull { image(it) }).toSet()
        paymentIcons.cleanupOrphans(referenced, before)
    }

    /** [keep] = photos still in use elsewhere (e.g. an unfinished add you can resume). */
    suspend fun cleanupReceipts(keep: Set<String> = emptySet(), before: Long = Long.MAX_VALUE) {
        val referenced = (purchaseDao.receiptFiles() + subscriptionDao.receiptFiles()).toSet() + keep
        receipts.cleanupOrphans(referenced, before)
    }

    suspend fun eraseEverything() = db.withTransaction {
        purchaseDao.deleteAll()
        subscriptionDao.deleteAll()
        categoryDao.deleteAll()
        paymentMethodDao.deleteAll()
        periodDao.deleteAll()
    }.also {
        receipts.cleanupOrphans(emptySet())
        paymentIcons.cleanupOrphans(emptySet())
    }

    // Raw access for backup/import.
    internal val database get() = db
}
