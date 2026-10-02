package com.jlees.budgey.data

import com.jlees.budgey.data.db.CategoryEntity
import com.jlees.budgey.data.db.newId

/** A built-in category the user can start from (and re-add later if deleted). */
data class CategoryTemplate(
    val key: String,
    val name: String,
    val icon: String,
    val color: Long,
    val children: List<CategoryTemplate> = emptyList(),
)

object DefaultCategories {
    val templates: List<CategoryTemplate> = listOf(
        CategoryTemplate("housing", "Housing", "home", 0xFF5C6BC0, listOf(
            CategoryTemplate("rent", "Rent / Mortgage", "home", 0xFF5C6BC0),
            CategoryTemplate("home_maintenance", "Maintenance & Repairs", "handyman", 0xFF7986CB),
            CategoryTemplate("furnishings", "Furniture & Home Goods", "chair", 0xFF9FA8DA),
        )),
        CategoryTemplate("utilities", "Utilities", "bolt", 0xFFF9A825, listOf(
            CategoryTemplate("electric", "Electric", "lightbulb", 0xFFFBC02D),
            CategoryTemplate("water", "Water & Sewer", "water", 0xFF29B6F6),
            CategoryTemplate("gas_heat", "Gas / Heating", "fire", 0xFFFF7043),
            CategoryTemplate("internet", "Internet", "wifi", 0xFF26A69A),
            CategoryTemplate("phone", "Phone", "phone", 0xFF66BB6A),
            CategoryTemplate("trash", "Trash & Recycling", "delete", 0xFF8D6E63),
        )),
        CategoryTemplate("groceries", "Groceries", "cart", 0xFF43A047),
        CategoryTemplate("food", "Food & Dining", "restaurant", 0xFFEF6C00, listOf(
            CategoryTemplate("restaurants", "Restaurants", "restaurant", 0xFFEF6C00),
            CategoryTemplate("fast_food", "Fast Food", "fastfood", 0xFFFF8F00),
            CategoryTemplate("coffee", "Coffee & Snacks", "coffee", 0xFF8D6E63),
            CategoryTemplate("delivery", "Food Delivery", "delivery", 0xFFF4511E),
            CategoryTemplate("bars", "Bars & Nightlife", "bar", 0xFFAD1457),
        )),
        CategoryTemplate("transportation", "Transportation", "car", 0xFF1E88E5, listOf(
            CategoryTemplate("fuel", "Gas & Fuel", "gas", 0xFF1E88E5),
            CategoryTemplate("parking", "Parking & Tolls", "parking", 0xFF42A5F5),
            CategoryTemplate("rideshare", "Rideshare & Taxi", "taxi", 0xFF039BE5),
            CategoryTemplate("transit", "Public Transit", "bus", 0xFF0288D1),
            CategoryTemplate("car_maintenance", "Car Maintenance", "build", 0xFF0277BD),
            CategoryTemplate("car_payment", "Car Payment", "car", 0xFF01579B),
        )),
        CategoryTemplate("entertainment", "Entertainment", "movie", 0xFF8E24AA, listOf(
            CategoryTemplate("streaming", "Streaming", "tv", 0xFF8E24AA),
            CategoryTemplate("games", "Games", "games", 0xFF7B1FA2),
            CategoryTemplate("music", "Music & Audio", "music", 0xFFAB47BC),
            CategoryTemplate("events", "Movies & Events", "ticket", 0xFFBA68C8),
            CategoryTemplate("hobbies", "Hobbies", "brush", 0xFFCE93D8),
        )),
        CategoryTemplate("shopping", "Shopping", "bag", 0xFFD81B60, listOf(
            CategoryTemplate("clothing", "Clothing & Shoes", "checkroom", 0xFFD81B60),
            CategoryTemplate("electronics", "Electronics", "devices", 0xFFC2185B),
            CategoryTemplate("household", "Household Supplies", "cleaning", 0xFFEC407A),
            CategoryTemplate("online", "Online Shopping", "store", 0xFFF06292),
        )),
        CategoryTemplate("health", "Health & Fitness", "health", 0xFFE53935, listOf(
            CategoryTemplate("medical", "Doctor & Medical", "hospital", 0xFFE53935),
            CategoryTemplate("pharmacy", "Pharmacy", "pharmacy", 0xFFEF5350),
            CategoryTemplate("fitness", "Gym & Fitness", "fitness", 0xFFE57373),
            CategoryTemplate("dental_vision", "Dental & Vision", "face", 0xFFFF8A80),
        )),
        CategoryTemplate("insurance", "Insurance", "shield", 0xFF546E7A),
        CategoryTemplate("personal_care", "Personal Care", "spa", 0xFFEC407A),
        CategoryTemplate("software", "Software & Apps", "apps", 0xFF00897B),
        CategoryTemplate("education", "Education", "school", 0xFF3949AB),
        CategoryTemplate("travel", "Travel", "flight", 0xFF00ACC1, listOf(
            CategoryTemplate("lodging", "Lodging", "hotel", 0xFF00ACC1),
            CategoryTemplate("flights", "Flights", "flight", 0xFF26C6DA),
            CategoryTemplate("vacation", "Activities", "beach", 0xFF4DD0E1),
        )),
        CategoryTemplate("gifts", "Gifts & Donations", "gift", 0xFFC0CA33),
        CategoryTemplate("pets", "Pets", "pets", 0xFF6D4C41),
        CategoryTemplate("kids", "Kids & Childcare", "child", 0xFFFFB300),
        CategoryTemplate("debt", "Debt Payments", "card", 0xFF757575),
        CategoryTemplate("fees", "Fees & Charges", "receipt", 0xFF9E9E9E),
        CategoryTemplate("taxes", "Taxes", "bank", 0xFF616161),
    )

    /** Flattened entities for a template tree (parents first), with fresh ids. */
    fun toEntities(templates: List<CategoryTemplate>, parentId: String? = null): List<CategoryEntity> {
        val out = mutableListOf<CategoryEntity>()
        templates.forEachIndexed { index, t ->
            val id = newId()
            out += CategoryEntity(
                id = id,
                name = t.name,
                parentId = parentId,
                icon = t.icon,
                color = t.color.toInt(),
                sortOrder = index,
                templateKey = t.key,
            )
            out += toEntities(t.children, id)
        }
        return out
    }

    fun all(): List<CategoryTemplate> = templates.flatMap { listOf(it) + it.children }

    fun byKey(key: String): CategoryTemplate? = all().firstOrNull { it.key == key }
}
