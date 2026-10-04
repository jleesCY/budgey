package com.jlees.budgey.ui.subscriptions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jlees.budgey.AppContainer
import com.jlees.budgey.data.db.SubscriptionEntity
import com.jlees.budgey.data.db.SubscriptionStatus
import com.jlees.budgey.data.db.isLive
import com.jlees.budgey.data.db.monthlyCents
import com.jlees.budgey.domain.CategoryTree
import com.jlees.budgey.domain.Renewals
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import java.time.LocalDate

enum class SubTab(val label: String) { ACTIVE("Active"), PAUSED("Paused"), CANCELLED("Cancelled"), ALL("All") }
enum class SubSort(val label: String) { NEXT_DUE("Next payment"), PRICE("Monthly cost"), NAME("Name") }

data class SubscriptionsUiState(
    val loading: Boolean = true,
    val tree: CategoryTree = CategoryTree.EMPTY,
    val tab: SubTab = SubTab.ACTIVE,
    val sort: SubSort = SubSort.NEXT_DUE,
    val list: List<SubscriptionEntity> = emptyList(),
    val upcoming: List<SubscriptionEntity> = emptyList(),
    val monthlyTotal: Long = 0,
    val yearlyTotal: Long = 0,
    val liveCount: Int = 0,
    val trialsEndingSoon: List<SubscriptionEntity> = emptyList(),
    /** Free trials that have reached their end (now being charged) but are still marked as trials. */
    val trialsEnded: List<SubscriptionEntity> = emptyList(),
    val counts: Map<SubTab, Int> = emptyMap(),
    val remindersOn: Boolean = true,
)

class SubscriptionsViewModel(private val c: AppContainer) : ViewModel() {
    /** "Keep it" on an ended trial: it becomes a normal Active subscription. */
    fun keepAfterTrial(id: String) = viewModelScope.launch { c.repository.keepAfterTrial(id) }

    val tab = MutableStateFlow(SubTab.ACTIVE)
    val sort = MutableStateFlow(SubSort.NEXT_DUE)

    val state: StateFlow<SubscriptionsUiState> = combine(
        c.repository.subscriptions, c.repository.categoryTree, tab, sort, c.settings.settings,
    ) { subs, tree, t, s, settings ->
        val today = LocalDate.now()
        val live = subs.filter { it.isLive }
        fun inTab(x: SubscriptionEntity, tab: SubTab) = when (tab) {
            SubTab.ACTIVE -> x.isLive
            SubTab.PAUSED -> x.status == SubscriptionStatus.PAUSED
            SubTab.CANCELLED -> x.status == SubscriptionStatus.CANCELLED
            SubTab.ALL -> true
        }
        val filtered = subs.filter { inTab(it, t) }
        SubscriptionsUiState(
            loading = false,
            tree = tree,
            tab = t,
            sort = s,
            list = when (s) {
                SubSort.NEXT_DUE -> filtered.sortedBy { it.nextDueDate }
                SubSort.PRICE -> filtered.sortedByDescending { it.monthlyCents }
                SubSort.NAME -> filtered.sortedBy { it.name.lowercase() }
            },
            // Real charges only: nothing during a free trial.
            upcoming = live.filter { s ->
                val paidFrom = Renewals.paidFrom(s, today)
                paidFrom != null && !s.nextDueDate.isBefore(paidFrom) && !s.nextDueDate.isAfter(today.plusDays(14))
            }.sortedBy { it.nextDueDate },
            monthlyTotal = live.sumOf { it.monthlyCents },
            yearlyTotal = live.sumOf { it.monthlyCents } * 12,
            liveCount = live.size,
            trialsEndingSoon = subs.filter {
                it.status == SubscriptionStatus.TRIAL && it.trialEndDate != null &&
                    it.trialEndDate.isAfter(today) && !it.trialEndDate.isAfter(today.plusDays(7))
            },
            trialsEnded = subs.filter { Renewals.trialEnded(it, today) }.sortedBy { it.trialEndDate },
            counts = SubTab.entries.associateWith { tb -> subs.count { inTab(it, tb) } },
            remindersOn = settings.renewalReminders,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SubscriptionsUiState())
}
