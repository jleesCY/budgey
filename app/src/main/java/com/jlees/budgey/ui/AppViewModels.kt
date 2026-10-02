package com.jlees.budgey.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jlees.budgey.AppContainer
import com.jlees.budgey.BudgeyApp
import com.jlees.budgey.ui.categories.CategoriesViewModel
import com.jlees.budgey.ui.calendar.CalendarViewModel
import com.jlees.budgey.ui.purchases.PurchaseEditViewModel
import com.jlees.budgey.ui.purchases.PurchasesViewModel
import com.jlees.budgey.ui.scan.ScanViewModel
import com.jlees.budgey.ui.settings.ImportViewModel
import com.jlees.budgey.ui.settings.PaymentMethodsViewModel
import com.jlees.budgey.ui.settings.SettingsViewModel
import com.jlees.budgey.ui.subscriptions.SubscriptionEditViewModel
import com.jlees.budgey.ui.subscriptions.SubscriptionsViewModel

fun CreationExtras.container(): AppContainer =
    (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BudgeyApp).container

object AppViewModels {
    val Factory: ViewModelProvider.Factory = viewModelFactory {
        initializer { CalendarViewModel(container()) }
        initializer { PurchasesViewModel(container(), createSavedStateHandle()) }
        initializer { PurchaseEditViewModel(container(), createSavedStateHandle()) }
        initializer { CategoriesViewModel(container(), createSavedStateHandle()) }
        initializer { SubscriptionsViewModel(container()) }
        initializer { SubscriptionEditViewModel(container(), createSavedStateHandle()) }
        initializer { ScanViewModel(container(), createSavedStateHandle()) }
        initializer { SettingsViewModel(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BudgeyApp) }
        initializer { ImportViewModel(container()) }
        initializer { PaymentMethodsViewModel(container()) }
        initializer { com.jlees.budgey.ui.tools.CheckSplitViewModel(container()) }
        initializer { com.jlees.budgey.ui.tools.CurrencyViewModel(container()) }
    }
}
