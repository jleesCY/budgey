package com.jlees.budgey.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.LocalDate

class Converters {
    @TypeConverter
    fun dateToLong(date: LocalDate?): Long? = date?.toEpochDay()

    @TypeConverter
    fun longToDate(value: Long?): LocalDate? = value?.let(LocalDate::ofEpochDay)
}

@Database(
    entities = [
        CategoryEntity::class, PurchaseEntity::class, SubscriptionEntity::class, PaymentMethodEntity::class,
        SubscriptionPeriodEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun purchaseDao(): PurchaseDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun paymentMethodDao(): PaymentMethodDao
    abstract fun subscriptionPeriodDao(): SubscriptionPeriodDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "budgey.db")
                .addMigrations(*ALL_MIGRATIONS)
                .build()

        /** Every migration, oldest first (also used by the migration tests). */
        val ALL_MIGRATIONS: Array<Migration> by lazy { arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6) }

        /** v6: subscription history periods + an end date for paused/cancelled subscriptions. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN endDate INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `subscription_periods` (`id` TEXT NOT NULL, `subscriptionId` TEXT NOT NULL, " +
                        "`startDate` INTEGER NOT NULL, `endDate` INTEGER NOT NULL, `amountCents` INTEGER NOT NULL, " +
                        "`cycleUnit` TEXT NOT NULL, `cycleCount` INTEGER NOT NULL, `label` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_subscription_periods_subscriptionId` " +
                        "ON `subscription_periods` (`subscriptionId`)"
                )
            }
        }

        /**
         * v5: payment methods become real records. Every distinct payment-method text already
         * typed on purchases/subscriptions becomes a saved method, and items are linked to it.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `payment_methods` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`icon` TEXT NOT NULL, `last4` TEXT, `sortOrder` INTEGER NOT NULL, `archived` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("ALTER TABLE purchases ADD COLUMN paymentMethodId TEXT")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN paymentMethodId TEXT")
                db.execSQL(
                    "INSERT INTO payment_methods (id, name, icon, last4, sortOrder, archived, createdAt) " +
                        "SELECT lower(hex(randomblob(16))), pm, 'generic:card', NULL, 0, 0, " +
                        "CAST(strftime('%s','now') AS INTEGER) * 1000 FROM (" +
                        // "Visa" and "visa" are one method (the first spelling found is kept).
                        "SELECT min(pm) AS pm FROM (" +
                        "SELECT trim(paymentMethod) AS pm FROM purchases WHERE trim(paymentMethod) != '' " +
                        "UNION ALL SELECT trim(paymentMethod) FROM subscriptions WHERE trim(paymentMethod) != ''" +
                        ") GROUP BY lower(pm))"
                )
                db.execSQL(
                    "UPDATE purchases SET paymentMethodId = (SELECT id FROM payment_methods m " +
                        "WHERE m.name = trim(purchases.paymentMethod) COLLATE NOCASE) WHERE trim(paymentMethod) != ''"
                )
                db.execSQL(
                    "UPDATE subscriptions SET paymentMethodId = (SELECT id FROM payment_methods m " +
                        "WHERE m.name = trim(subscriptions.paymentMethod) COLLATE NOCASE) WHERE trim(paymentMethod) != ''"
                )
            }
        }

        /** v4: fees & taxes as a flat amount; converts any v3 tax percentage into cents. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN feesCents INTEGER")
                db.execSQL(
                    "UPDATE subscriptions SET feesCents = amountCents - listPriceCents " +
                        "WHERE listPriceCents IS NOT NULL"
                )
            }
        }

        /** v3: advertised (pre-tax) price + tax rate on subscriptions. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN listPriceCents INTEGER")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN taxRatePercent REAL")
            }
        }

        /** v2: per-subscription renewal reminder lead time. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN reminderDays INTEGER")
            }
        }
    }
}
