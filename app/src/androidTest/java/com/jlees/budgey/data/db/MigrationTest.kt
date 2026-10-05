package com.jlees.budgey.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Upgrades old databases to the current version and checks the result matches what Room expects
 * (every table, column and index), with the data still there.
 * Run on a phone or emulator: ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val dbName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun fromVersion1ToLatest() {
        helper.createDatabase(dbName, 1).close()
        helper.runMigrationsAndValidate(dbName, LATEST, true, *AppDatabase.ALL_MIGRATIONS).close()
    }

    @Test fun fromVersion3ToLatestKeepsDataAndMergesPaymentMethods() {
        helper.createDatabase(dbName, 3).apply {
            // "Visa" and "visa" typed on different items: one saved payment method after the upgrade.
            execSQL(
                "INSERT INTO purchases (id, merchant, amountCents, date, categoryId, note, paymentMethod, brandKey, " +
                    "receiptFile, source, subscriptionId, createdAt, updatedAt) VALUES " +
                    "('p1', 'Cafe', 450, 20000, NULL, '', 'Visa', NULL, NULL, 'MANUAL', NULL, 0, 0), " +
                    "('p2', 'Shop', 1200, 20001, NULL, '', 'visa ', NULL, NULL, 'MANUAL', NULL, 0, 0)"
            )
            execSQL(
                "INSERT INTO subscriptions (id, name, amountCents, cycleUnit, cycleCount, anchorDate, nextDueDate, " +
                    "categoryId, brandKey, status, autoLog, trialEndDate, paymentMethod, note, receiptFile, createdAt, " +
                    "updatedAt, reminderDays, listPriceCents, taxRatePercent) VALUES " +
                    "('s1', 'Music', 1099, 'MONTH', 1, 20000, 20030, NULL, NULL, 'ACTIVE', 1, NULL, 'VISA', '', NULL, 0, 0, NULL, NULL, NULL)"
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, LATEST, true, *AppDatabase.ALL_MIGRATIONS)
        db.query("SELECT COUNT(*) FROM payment_methods").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        db.query("SELECT COUNT(*) FROM purchases WHERE paymentMethodId IS NOT NULL").use { c -> c.moveToFirst(); assertEquals(2, c.getInt(0)) }
        db.query("SELECT COUNT(*) FROM subscriptions WHERE paymentMethodId IS NOT NULL").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        db.query("SELECT amountCents FROM purchases WHERE id = 'p1'").use { c -> c.moveToFirst(); assertEquals(450, c.getInt(0)) }
        db.close()
    }

    private companion object {
        const val LATEST = 6
    }
}
