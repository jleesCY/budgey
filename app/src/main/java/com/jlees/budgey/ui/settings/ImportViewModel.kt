package com.jlees.budgey.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jlees.budgey.AppContainer
import com.jlees.budgey.data.backup.DuplicateStrategy
import com.jlees.budgey.data.backup.ImportPlan
import com.jlees.budgey.data.backup.ImportResult
import com.jlees.budgey.data.backup.LoadedBackup
import com.jlees.budgey.domain.CategoryTree
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Per-category totals inside the backup (including sub-categories). */
data class BackupCounts(val purchases: Int, val subscriptions: Int, val spent: Long)

data class ImportUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val backup: LoadedBackup? = null,
    val counts: Map<String, BackupCounts> = emptyMap(),
    val uncategorizedPurchases: Int = 0,
    val uncategorizedSubscriptions: Int = 0,
    val plan: ImportPlan = ImportPlan(emptySet(), true, true, false),
    val importing: Boolean = false,
    val result: ImportResult? = null,
)

class ImportViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()
    val localTree: StateFlow<CategoryTree> = c.repository.categoryTree.stateIn(viewModelScope, SharingStarted.Eagerly, CategoryTree.EMPTY)

    fun load(uri: Uri) = viewModelScope.launch {
        _state.value = ImportUiState(loading = true)
        runCatching { c.backup.load(uri) }
            .onSuccess { b ->
                val tree = b.categoryTree
                val known = tree.byId.keys
                val counts = tree.all.associate { cat ->
                    val ids = tree.subtreeIds(cat.id)
                    val ps = b.file.purchases.filter { it.categoryId in ids }
                    cat.id to BackupCounts(ps.size, b.file.subscriptions.count { it.categoryId in ids }, ps.sumOf { it.amountCents })
                }
                _state.value = ImportUiState(
                    backup = b,
                    counts = counts,
                    uncategorizedPurchases = b.file.purchases.count { it.categoryId == null || it.categoryId !in known },
                    uncategorizedSubscriptions = b.file.subscriptions.count { it.categoryId == null || it.categoryId !in known },
                    plan = ImportPlan(
                        categoryIds = known,
                        includeUncategorizedPurchases = true,
                        includeUncategorizedSubscriptions = true,
                        includeSettings = false,
                    ),
                )
            }
            .onFailure { _state.value = ImportUiState(error = it.message ?: "Couldn't read that file") }
    }

    /** Checking a folder checks everything inside it; unchecking unchecks the whole subtree. */
    fun toggleCategory(id: String) = _state.update { s ->
        val tree = s.backup?.categoryTree ?: return@update s
        val sub = tree.subtreeIds(id)
        val selected = s.plan.categoryIds
        // Ancestors are deliberately NOT auto-selected: importing just "Coffee" without its
        // parent places it at the chosen destination (top level by default).
        val newSel = if (id in selected) selected - sub else selected + sub
        s.copy(plan = s.plan.copy(categoryIds = newSel))
    }

    fun selectAll(all: Boolean) = _state.update { s ->
        s.copy(plan = s.plan.copy(categoryIds = if (all) s.backup?.categoryTree?.byId?.keys ?: emptySet() else emptySet()))
    }

    fun updatePlan(transform: (ImportPlan) -> ImportPlan) = _state.update { it.copy(plan = transform(it.plan)) }

    fun setDuplicates(d: DuplicateStrategy) = updatePlan { it.copy(duplicates = d) }

    fun runImport() = viewModelScope.launch {
        val s = _state.value
        val b = s.backup ?: return@launch
        _state.update { it.copy(importing = true) }
        runCatching { c.backup.import(b, s.plan) }
            .onSuccess { r -> _state.update { it.copy(importing = false, result = r) } }
            .onFailure { e -> _state.update { it.copy(importing = false, error = e.message) } }
    }

    fun clearError() = _state.update { it.copy(error = null) }
}
