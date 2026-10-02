package com.jlees.budgey.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY sortOrder, name COLLATE NOCASE")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY sortOrder, name COLLATE NOCASE")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun get(id: String): CategoryEntity?

    @Upsert
    suspend fun upsert(category: CategoryEntity)

    @Upsert
    suspend fun upsertAll(categories: List<CategoryEntity>)

    @Query("UPDATE categories SET parentId = :newParent WHERE parentId = :oldParent")
    suspend fun reparentChildren(oldParent: String, newParent: String?)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM categories")
    suspend fun deleteAll()
}

@Dao
interface PurchaseDao {
    @Query("SELECT * FROM purchases ORDER BY date DESC, createdAt DESC")
    fun observeAll(): Flow<List<PurchaseEntity>>

    @Query("SELECT * FROM purchases ORDER BY date DESC, createdAt DESC")
    suspend fun getAll(): List<PurchaseEntity>

    @Query("SELECT * FROM purchases WHERE id = :id")
    suspend fun get(id: String): PurchaseEntity?

    @Upsert
    suspend fun upsert(purchase: PurchaseEntity)

    @Upsert
    suspend fun upsertAll(purchases: List<PurchaseEntity>)

    @Delete
    suspend fun delete(purchase: PurchaseEntity)

    @Query("UPDATE purchases SET categoryId = :newCategory WHERE categoryId = :oldCategory")
    suspend fun reassignCategory(oldCategory: String, newCategory: String?)

    @Query("UPDATE purchases SET categoryId = :categoryId, updatedAt = :now WHERE id IN (:ids)")
    suspend fun setCategory(ids: List<String>, categoryId: String?, now: Long)

    @Query("SELECT * FROM purchases WHERE subscriptionId = :subscriptionId ORDER BY date")
    suspend fun getForSubscription(subscriptionId: String): List<PurchaseEntity>

    @Delete
    suspend fun deleteMany(purchases: List<PurchaseEntity>)

    @Query("SELECT COUNT(*) FROM purchases WHERE subscriptionId = :subscriptionId AND date = :date")
    suspend fun countForSubscription(subscriptionId: String, date: LocalDate): Int

    /** Most recent category used for this merchant — powers "smart" category suggestions. */
    @Query(
        "SELECT categoryId FROM purchases WHERE merchant = :merchant COLLATE NOCASE " +
            "AND categoryId IS NOT NULL ORDER BY date DESC LIMIT 1"
    )
    suspend fun lastCategoryForMerchant(merchant: String): String?

    /** Payment method you used last time at this merchant — pre-selected on new purchases. */
    @Query(
        "SELECT paymentMethodId FROM purchases WHERE merchant = :merchant COLLATE NOCASE " +
            "AND paymentMethodId IS NOT NULL ORDER BY date DESC LIMIT 1"
    )
    suspend fun lastPaymentMethodForMerchant(merchant: String): String?

    @Query("SELECT DISTINCT merchant FROM purchases ORDER BY merchant COLLATE NOCASE")
    fun observeMerchants(): Flow<List<String>>

    @Query("SELECT DISTINCT paymentMethod FROM purchases WHERE paymentMethod != '' ORDER BY paymentMethod COLLATE NOCASE")
    fun observePaymentMethods(): Flow<List<String>>

    @Query("DELETE FROM purchases")
    suspend fun deleteAll()
}

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY nextDueDate, name COLLATE NOCASE")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions ORDER BY nextDueDate, name COLLATE NOCASE")
    suspend fun getAll(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions WHERE id = :id")
    suspend fun get(id: String): SubscriptionEntity?

    @Upsert
    suspend fun upsert(subscription: SubscriptionEntity)

    @Upsert
    suspend fun upsertAll(subscriptions: List<SubscriptionEntity>)

    @Delete
    suspend fun delete(subscription: SubscriptionEntity)

    @Query("UPDATE subscriptions SET categoryId = :newCategory WHERE categoryId = :oldCategory")
    suspend fun reassignCategory(oldCategory: String, newCategory: String?)

    @Query("DELETE FROM subscriptions")
    suspend fun deleteAll()

    @Query("UPDATE subscriptions SET autoLog = :on")
    suspend fun setAutoLogForAll(on: Boolean)
}

@Dao
interface PaymentMethodDao {
    @Query("SELECT * FROM payment_methods ORDER BY archived, sortOrder, name COLLATE NOCASE")
    fun observeAll(): Flow<List<PaymentMethodEntity>>

    @Query("SELECT * FROM payment_methods ORDER BY archived, sortOrder, name COLLATE NOCASE")
    suspend fun getAll(): List<PaymentMethodEntity>

    @Query("SELECT * FROM payment_methods WHERE id = :id")
    suspend fun get(id: String): PaymentMethodEntity?

    @Upsert
    suspend fun upsert(method: PaymentMethodEntity)

    @Upsert
    suspend fun upsertAll(methods: List<PaymentMethodEntity>)

    @Query("DELETE FROM payment_methods WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE purchases SET paymentMethodId = NULL WHERE paymentMethodId = :id")
    suspend fun unlinkPurchases(id: String)

    @Query("UPDATE subscriptions SET paymentMethodId = NULL WHERE paymentMethodId = :id")
    suspend fun unlinkSubscriptions(id: String)

    /** Keep the name snapshot on linked items in sync after a rename. */
    @Query("UPDATE purchases SET paymentMethod = :name WHERE paymentMethodId = :id")
    suspend fun renameOnPurchases(id: String, name: String)

    @Query("UPDATE subscriptions SET paymentMethod = :name WHERE paymentMethodId = :id")
    suspend fun renameOnSubscriptions(id: String, name: String)

    @Query("DELETE FROM payment_methods")
    suspend fun deleteAll()
}

@Dao
interface SubscriptionPeriodDao {
    @Query("SELECT * FROM subscription_periods ORDER BY startDate")
    fun observeAll(): Flow<List<SubscriptionPeriodEntity>>

    @Query("SELECT * FROM subscription_periods ORDER BY startDate")
    suspend fun getAll(): List<SubscriptionPeriodEntity>

    @Query("SELECT * FROM subscription_periods WHERE subscriptionId = :subscriptionId ORDER BY startDate")
    suspend fun getFor(subscriptionId: String): List<SubscriptionPeriodEntity>

    @Upsert
    suspend fun upsertAll(periods: List<SubscriptionPeriodEntity>)

    @Query("DELETE FROM subscription_periods WHERE subscriptionId = :subscriptionId")
    suspend fun deleteFor(subscriptionId: String)

    @Query("DELETE FROM subscription_periods")
    suspend fun deleteAll()
}
