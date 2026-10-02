package com.jlees.budgey.ui.categories

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.jlees.budgey.AppContainer
import com.jlees.budgey.data.CategoryTemplate
import com.jlees.budgey.data.DefaultCategories
import com.jlees.budgey.data.db.CategoryEntity
import com.jlees.budgey.data.db.PurchaseEntity
import com.jlees.budgey.data.db.SubscriptionEntity
import com.jlees.budgey.data.db.isLive
import com.jlees.budgey.domain.BudgetPeriod
import com.jlees.budgey.domain.BudgetStatus
import com.jlees.budgey.domain.Budgets
import com.jlees.budgey.domain.CategoryTree
import com.jlees.budgey.ui.navigation.CategoriesRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Budgets first and selected by default; Folders is the second view. */
enum class CategoriesView { BUDGETS, FOLDERS }

data class FolderItem(
    val category: CategoryEntity,
    /** Spending in the category's budget period (or this month when it has no budget), incl. children. */
    val spent: Long,
    val status: BudgetStatus?,
    val childCount: Int,
)

data class CategoriesUiState(
    val loading: Boolean = true,
    val tree: CategoryTree = CategoryTree.EMPTY,
    val folder: CategoryEntity? = null,
    val path: List<CategoryEntity> = emptyList(),
    val items: List<FolderItem> = emptyList(),
    val folderStatus: BudgetStatus? = null,
    val folderSpentThisMonth: Long = 0,
    val folderPurchaseCount: Int = 0,
    val recent: List<PurchaseEntity> = emptyList(),
    val subscriptionsHere: List<SubscriptionEntity> = emptyList(),
    val uncategorizedCount: Int = 0,
    val budgets: List<BudgetStatus> = emptyList(),
    val monthlyBudgetTotal: Long = 0,
    val monthlySpentTotal: Long = 0,
    val missingTemplates: List<CategoryTemplate> = emptyList(),
)

class CategoriesViewModel(private val c: AppContainer, handle: SavedStateHandle) : ViewModel() {
    val folderId: String? = handle.toRoute<CategoriesRoute>().folderId
    val view = MutableStateFlow(CategoriesView.BUDGETS)

    val state: StateFlow<CategoriesUiState> = combine(
        c.repository.categoryTree,
        c.repository.purchases,
        c.repository.subscriptions,
        c.settings.settings,
    ) { tree, purchases, subs, settings ->
        val today = LocalDate.now()
        val month = BudgetPeriod.MONTHLY.rangeContaining(today)
        val statuses = Budgets.statuses(tree, purchases, today, settings.firstDayOfWeek)
        val statusById = statuses.associateBy { it.category.id }

        fun spentFor(cat: CategoryEntity): Long {
            val range = statusById[cat.id]?.range ?: month
            val ids = tree.subtreeIds(cat.id)
            return purchases.filter { it.date in range && it.categoryId in ids }.sumOf { it.amountCents }
        }

        val folder = folderId?.let { tree.byId[it] }
        val items = tree.children(folder?.id).map { child ->
            FolderItem(child, spentFor(child), statusById[child.id], tree.children(child.id).size)
        }
        val folderIds = folder?.let { tree.subtreeIds(it.id) } ?: emptySet()
        val inFolder = if (folder != null) purchases.filter { it.categoryId in folderIds } else emptyList()

        val existingKeys = tree.all.mapNotNull { it.templateKey }.toSet()
        val missing = DefaultCategories.templates.filter { it.key !in existingKeys } +
            DefaultCategories.templates.filter { it.key in existingKeys }.flatMap { it.children }.filter { it.key !in existingKeys }

        // Only top-level budgets are summed so a parent + child budget isn't double counted.
        val topBudgets = statuses.filter { s -> tree.path(s.category.id).dropLast(1).none { it.budgetCents != null } }
        CategoriesUiState(
            loading = false,
            tree = tree,
            folder = folder,
            path = tree.path(folder?.id),
            items = items,
            folderStatus = folder?.let { statusById[it.id] },
            folderSpentThisMonth = inFolder.filter { it.date in month }.sumOf { it.amountCents },
            folderPurchaseCount = inFolder.size,
            recent = inFolder.take(5),
            subscriptionsHere = if (folder != null) subs.filter { it.categoryId in folderIds && it.isLive } else emptyList(),
            uncategorizedCount = purchases.count { it.categoryId == null || it.categoryId !in tree.byId },
            budgets = statuses.sortedByDescending { it.fraction },
            monthlyBudgetTotal = topBudgets.sumOf { Math.round(it.budgetCents * it.category.budgetPeriod.perMonth) },
            monthlySpentTotal = topBudgets.sumOf { s ->
                val ids = tree.subtreeIds(s.category.id)
                purchases.filter { it.date in month && it.categoryId in ids }.sumOf { it.amountCents }
            },
            missingTemplates = missing,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoriesUiState())

    fun save(category: CategoryEntity, onError: (String) -> Unit = {}) = viewModelScope.launch {
        runCatching { c.repository.saveCategory(category) }.onFailure { onError(it.message ?: "Couldn't save") }
    }

    fun delete(id: String) = viewModelScope.launch { c.repository.deleteCategory(id) }

    fun addTemplates(keys: Set<String>) = viewModelScope.launch { c.repository.addTemplates(keys) }

    fun setBudget(category: CategoryEntity, cents: Long?, period: BudgetPeriod) = viewModelScope.launch {
        c.repository.saveCategory(category.copy(budgetCents = cents, budgetPeriod = period))
    }
}
