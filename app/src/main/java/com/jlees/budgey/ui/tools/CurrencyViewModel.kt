package com.jlees.budgey.ui.tools

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jlees.budgey.AppContainer
import com.jlees.budgey.domain.FxTable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Currency
import java.util.Locale

data class CurrencyState(
    val table: FxTable? = null,
    /** When the update pack was downloaded (ms); null = built-in rates. */
    val fetchedAt: Long? = null,
    /** True when the daily-updates pack is installed (only then can rates be updated). */
    val hasPack: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val from: String = "USD",
    val to: String = "EUR",
    val amount: String = "1",
)

class CurrencyViewModel(private val c: AppContainer) : ViewModel() {
    // Remembers the last pair you converted (a small per-phone convenience).
    private val prefs = c.context.getSharedPreferences("tools", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        run {
            val local = runCatching { Currency.getInstance(Locale.getDefault()).currencyCode }.getOrNull() ?: "USD"
            val from = prefs.getString("fx_from", null) ?: local
            val to = prefs.getString("fx_to", null) ?: if (from == "EUR") "USD" else "EUR"
            CurrencyState(from = from, to = to, amount = prefs.getString("fx_amount", null) ?: "1")
        }
    )
    val state: StateFlow<CurrencyState> = _state.asStateFlow()

    init { load() }

    /** Uses only what's on the phone: the update pack if installed, else the built-in rates. */
    private fun load() = viewModelScope.launch {
        val r = c.fx.current()
        val hasPack = r.source == com.jlees.budgey.data.FxRepository.Source.PACK
        _state.update { it.copy(table = r.table, fetchedAt = r.fetchedAt, hasPack = hasPack) }
    }

    /** Update now (only offered when the update pack is installed). */
    fun refresh() = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        runCatching { c.fx.downloadPack() }
            .onSuccess { r -> _state.update { it.copy(table = r.table, fetchedAt = r.fetchedAt, hasPack = true, loading = false) } }
            .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: "Couldn't update rates") } }
    }

    fun setAmount(text: String) {
        val clean = text.filter { it.isDigit() || it == '.' || it == ',' }.take(15)
        _state.update { it.copy(amount = clean) }
        prefs.edit().putString("fx_amount", clean).apply()
    }

    fun setFrom(code: String) = setPair(code, _state.value.to)
    fun setTo(code: String) = setPair(_state.value.from, code)
    fun swap() = setPair(_state.value.to, _state.value.from)

    private fun setPair(from: String, to: String) {
        _state.update { it.copy(from = from, to = to) }
        prefs.edit().putString("fx_from", from).putString("fx_to", to).apply()
    }
}
