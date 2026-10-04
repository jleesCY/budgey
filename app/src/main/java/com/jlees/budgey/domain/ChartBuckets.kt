package com.jlees.budgey.domain

/**
 * How the Purchases chart groups spending: by top-level category, and after tapping one, by its
 * sub-categories (and so on down). Pure logic, unit-tested.
 */
object ChartBuckets {
    /**
     * The slice a purchase in [categoryId] belongs to when the chart is focused on [focus]
     * (null = everything, grouped by top-level category). Spending directly in the focused
     * category gets its own slice keyed by [focus]. Null = uncategorized.
     */
    fun bucket(tree: CategoryTree, categoryId: String?, focus: String?): String? = when {
        categoryId == null || categoryId !in tree.byId -> null
        focus == null || focus !in tree.byId -> tree.rootOf(categoryId)?.id
        categoryId == focus -> focus
        // Inside the focused category: the child that contains it. (Anything outside — which the
        // list filter normally excludes — falls back to its top-level category.)
        else -> tree.childOfAncestor(categoryId, focus)?.id ?: tree.rootOf(categoryId)?.id
    }
}
