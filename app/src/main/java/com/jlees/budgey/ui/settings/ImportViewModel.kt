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
) {
    /** Is anything picked to bring in? (Import is disabled otherwise.) */
    val hasSelection: Boolean
        get() = plan.categoryIds.isNotEmpty() || plan.includeSettings ||
            (plan.includeUncategorizedPurchases && uncategorizedPurchases > 0) ||
            (plan.includeUncategorizedSubscriptions && uncategorizedSubscriptions > 0)
}

class ImportViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()
    val localTree: StateFlow<CategoryTree> = c.repository.categoryTree.stateIn(viewModelScope, SharingStarted.Eagerly, CategoryTree.EMPTY)

    fun load(uri: Uri) = viewModelScope.launch {
        if (_state.value.importing) return@launch
        _state.value.backup?.release() // "Other file": the previous one's unpacked copy can go
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

    /** Set when the screen is gone; the import still running then cleans up after itself. */
    @Volatile private var cleared = false

    fun runImport() {
        val s = _state.value
        val b = s.backup ?: return
        if (s.importing || !s.hasSelection) return // a double tap doesn't import twice
        _state.update { it.copy(importing = true) }
        // In the app's scope: leaving the screen mid-import doesn't stop it half-way, and the unpacked
        // photos aren't deleted while they're still being copied in.
        c.appScope.launch {
            try {
                val r = c.backup.import(b, s.plan)
                _state.update { it.copy(importing = false, result = r) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(importing = false, error = e.message ?: "Import failed") }
            } finally {
                // The photos are copied in (or the import failed): the unpacked backup can go, unless
                // you're still on the screen and might try again.
                if (cleared || _state.value.result != null) b.release()
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    /** Leaving the import screen: delete the unpacked backup (it can be large). */
    override fun onCleared() {
        cleared = true
        // Mid-import, the import releases it when it's done.
        if (!_state.value.importing) _state.value.backup?.release()
    }
}
