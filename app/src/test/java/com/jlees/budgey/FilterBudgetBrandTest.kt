package com.jlees.budgey

import com.jlees.budgey.data.DefaultCategories
import com.jlees.budgey.data.db.PurchaseEntity
import com.jlees.budgey.domain.AmountKind
import com.jlees.budgey.domain.Budgets
import com.jlees.budgey.domain.CategoryTree
import com.jlees.budgey.domain.DatePreset
import com.jlees.budgey.domain.PurchaseFilter
import com.jlees.budgey.icons.Brand
import com.jlees.budgey.icons.BrandMatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate

class FilterBudgetBrandTest {
    private val cats = DefaultCategories.toEntities(DefaultCategories.templates)
    private val tree = CategoryTree(cats)
    private val food = cats.first { it.templateKey == "food" }
    private val coffee = cats.first { it.templateKey == "coffee" }
    private val fastFood = cats.first { it.templateKey == "fast_food" }
    private val today = LocalDate.of(2026, 10, 1)
    private val purchases = listOf(
        PurchaseEntity(merchant = "Starbucks", amountCents = 500, date = today, categoryId = coffee.id),
        PurchaseEntity(merchant = "Chipotle", amountCents = 1200, date = today.minusDays(3), categoryId = fastFood.id),
        PurchaseEntity(merchant = "Old", amountCents = 999, date = today.minusMonths(2), categoryId = coffee.id),
        PurchaseEntity(merchant = "Mystery", amountCents = 300, date = today),
        PurchaseEntity(merchant = "Refund", amountCents = -200, date = today, categoryId = coffee.id),
    )

    private fun run(f: PurchaseFilter) = f.apply(purchases, tree, today, DayOfWeek.SUNDAY) { null }.map { it.merchant }

    @Test fun treeBasics() {
        assertTrue(coffee.id in tree.subtreeIds(food.id))
        assertEquals("Food & Dining › Coffee & Snacks", tree.pathLabel(coffee.id))
        assertTrue(tree.wouldCycle(food.id, coffee.id))
    }

    @Test fun filters() {
        assertEquals(setOf("Starbucks", "Refund", "Chipotle"), run(PurchaseFilter(datePreset = DatePreset.LAST_30, categoryIds = setOf(food.id))).toSet())
        assertEquals(listOf("Mystery"), run(PurchaseFilter(datePreset = DatePreset.ALL, uncategorizedOnly = true)))
        assertEquals(listOf("Refund"), run(PurchaseFilter(datePreset = DatePreset.ALL, amountKind = AmountKind.REFUNDS)))
        assertEquals(listOf("Chipotle"), run(PurchaseFilter(datePreset = DatePreset.ALL, query = "chip")))
    }

    @Test fun parentBudgetRollsUpChildren() {
        val budgeted = CategoryTree(cats.map { if (it.id == food.id) it.copy(budgetCents = 10_000) else it })
        val status = Budgets.statuses(budgeted, purchases, today, DayOfWeek.SUNDAY).single()
        assertEquals(300L, status.spentCents) // 5.00 coffee - 2.00 refund; Chipotle was last month
    }

    @Test fun realBrandCatalogMatches() {
        val root = Json.parseToJsonElement(File("src/main/assets/brands.json").readText()).jsonObject
        val brands = root["brands"]!!.jsonArray.map { e ->
            val o = e.jsonObject
            Brand(
                o["id"]!!.jsonPrimitive.content, o["name"]!!.jsonPrimitive.content, 0, o["category"]?.jsonPrimitive?.content,
                o["subscription"]?.jsonPrimitive?.boolean ?: false,
                o["aliases"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(), o["slug"]?.jsonPrimitive?.content,
                o["payment"]?.jsonPrimitive?.boolean ?: false,
                o["strict"]?.jsonPrimitive?.boolean ?: false,
            )
        }
        val m = BrandMatcher(brands)
        mapOf(
            "AMZN Mktp US*1A2B3" to "amazon", "Prime Video" to "amazonprime", "NETFLIX.COM" to "netflix",
            "SQ *STARBUCKS 0912" to "starbucks", "UBER *TRIP" to "uber", "Uber Eats" to "ubereats",
            "TST* CHIPOTLE 2231" to "chipotle", "APPLE.COM/BILL" to "applecom", "Trader Joe's #552" to "traderjoes",
            "WAL-MART #1234" to "walmart", "Walmart+" to "walmartplus", "Disney+" to "disneyplus",
            "Chick-fil-A" to "chickfila", "OpenAI *ChatGPT Subscr" to "chatgpt", "Joe's Pizza" to "",
            "NFL+" to "nfl", "CAPITAL ONE" to "capitalone", "FIFTH THIRD BANK" to "fifththird", "TARGET 00012345" to "target",
            "ZIP'S CAR WASH" to "", "Current Electric Co" to "", "PAYPAL *NETFLIX" to "netflix", "UNITED 0162345" to "united",
        ).forEach { (input, want) -> assertEquals(input, want, m.match(input)?.id ?: "") }
        // Every brand's default category must be a real template key.
        val keys = DefaultCategories.all().map { it.key }.toSet()
        // …and every brand has one, so picking any logo also files the purchase.
        brands.forEach { b -> assertTrue("${b.id} -> ${b.category}", b.category != null && b.category in keys) }
        assertEquals("brand ids must be unique", brands.size, brands.map { it.id }.toSet().size)
    }
}
