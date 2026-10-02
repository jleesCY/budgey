package com.jlees.budgey.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.jlees.budgey.scan.ScanKind
import com.jlees.budgey.ui.calendar.CalendarScreen
import com.jlees.budgey.ui.categories.CategoriesScreen
import com.jlees.budgey.ui.purchases.PurchasesLinks
import com.jlees.budgey.ui.purchases.PurchaseEditScreen
import com.jlees.budgey.ui.purchases.PurchasesScreen
import com.jlees.budgey.ui.scan.ScanScreen
import com.jlees.budgey.ui.settings.ImportScreen
import com.jlees.budgey.ui.settings.PaymentMethodsScreen
import com.jlees.budgey.ui.settings.SettingsScreen
import com.jlees.budgey.ui.subscriptions.SubscriptionEditScreen
import com.jlees.budgey.ui.subscriptions.SubscriptionsScreen
import kotlin.reflect.KClass
import androidx.compose.runtime.saveable.rememberSaveable
import com.jlees.budgey.ui.components.rememberImageSource
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import com.jlees.budgey.ui.components.LocalAnimations
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jlees.budgey.ui.tools.CheckSplitScreen
import com.jlees.budgey.ui.tools.CurrencyScreen
import com.jlees.budgey.ui.tools.TipCalculatorScreen
import com.jlees.budgey.ui.tools.ToolsScreen

private data class TopLevel(val route: Any, val routeClass: KClass<*>, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

/**
 * Four destinations plus a ☰ menu (Settings, Tools) — five slots, the Material guidance maximum for
 * a navigation bar. Purchases is also the overview (chart, total, shortcuts), so there's no
 * separate Home tab.
 */
private val topLevels = listOf(
    TopLevel(PurchasesRoute(), PurchasesRoute::class, "Purchases", Icons.Outlined.ReceiptLong, Icons.Rounded.ReceiptLong),
    TopLevel(SubscriptionsRoute, SubscriptionsRoute::class, "Subs", Icons.Outlined.Autorenew, Icons.Rounded.Autorenew),
    TopLevel(CategoriesRoute(), CategoriesRoute::class, "Budgets & Categories", Icons.Outlined.Savings, Icons.Rounded.Savings),
    TopLevel(CalendarRoute, CalendarRoute::class, "Calendar", Icons.Outlined.CalendarMonth, Icons.Rounded.CalendarMonth),
)

/** Pages reached from the ☰ menu; the menu button shows as selected on them. */
private val menuRoutes: List<KClass<*>> = listOf(SettingsRoute::class, ToolsRoute::class)

private val barRoutes = topLevels.map { it.routeClass } + menuRoutes

// Quick, subtle transitions: the default NavHost crossfade is 700 ms, which feels sluggish.
private const val ENTER_MS = 180
private const val EXIT_MS = 120

@Composable
fun AppNav(
    sharedImage: String?,
    onSharedImageConsumed: () -> Unit,
    openSubscriptionId: String?,
    onOpenSubscriptionConsumed: () -> Unit,
    nav: NavHostController = rememberNavController(),
) {
    val animations = LocalAnimations.current
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val showBar = destination?.hierarchy?.any { d -> barRoutes.any { d.hasRoute(it) } } ?: true

    // Image shared into the app from another app (e.g. a screenshot) → straight to scanning.
    LaunchedEffect(sharedImage) {
        if (sharedImage != null) {
            nav.navigate(ScanRoute(mode = "shared", sharedUri = sharedImage))
            onSharedImageConsumed()
        }
    }
    // Tapped a renewal notification → open that subscription.
    LaunchedEffect(openSubscriptionId) {
        if (openSubscriptionId != null) {
            nav.navigate(SubscriptionEditRoute(id = openSubscriptionId))
            onOpenSubscriptionConsumed()
        }
    }

    // "Scan / Import" on Purchases / Subscriptions opens Android's camera-or-files chooser right
    // away; the scan screen only appears once a picture has been picked.
    var scanTarget by rememberSaveable { mutableStateOf("auto") }
    val pickForScan = rememberImageSource { uri ->
        if (uri != null) nav.navigate(ScanRoute(mode = "shared", target = scanTarget, sharedUri = uri.toString()))
    }

    // Guard against double-taps popping past the first screen (which would leave a blank screen).
    val goBack: () -> Unit = { if (nav.previousBackStackEntry != null) nav.popBackStack() }

    /** Switch tabs the same way the bottom bar does, so its selection & back stack stay in sync. */
    fun switchTab(route: Any) {
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun openScanResult(kind: ScanKind) {
        val target: Any = if (kind == ScanKind.SUBSCRIPTION) SubscriptionEditRoute(fromScan = true) else PurchaseEditRoute(fromScan = true)
        nav.navigate(target) { popUpTo<ScanRoute> { inclusive = true } }
    }

    val purchaseLinks = remember(nav) {
        PurchasesLinks(
            openSubscription = { id -> nav.navigate(SubscriptionEditRoute(id = id)) },
            subscriptionsTab = { switchTab(SubscriptionsRoute) },
            budgetsTab = { switchTab(CategoriesRoute()) },
            calendarTab = { switchTab(CalendarRoute) },
        )
    }

    /** New purchase ⇄ new subscription, keeping what was typed (replaces the current editor). */
    fun switchEditor(toSubscription: Boolean) {
        if (toSubscription) nav.navigate(SubscriptionEditRoute(fromScan = true)) { popUpTo<PurchaseEditRoute> { inclusive = true } }
        else nav.navigate(PurchaseEditRoute(fromScan = true)) { popUpTo<SubscriptionEditRoute> { inclusive = true } }
    }

    Scaffold(
        bottomBar = {
            AnimatedVisibility(
                showBar,
                enter = if (animations) slideInVertically { it } else EnterTransition.None,
                exit = if (animations) slideOutVertically { it } else ExitTransition.None,
            ) {
                // Extra side insets keep the first/last tab's highlight pill clear of rounded screen corners.
                // Icon-only, so the bar can be shorter than the default 80dp (64dp + the system nav inset).
                val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                NavigationBar(
                    modifier = Modifier.height(64.dp + navInset),
                    windowInsets = NavigationBarDefaults.windowInsets.add(WindowInsets(left = 12.dp, right = 12.dp)),
                ) {
                    topLevels.forEach { item ->
                        val selected = destination?.hierarchy?.any { it.hasRoute(item.routeClass) } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { switchTab(item.route) },
                            // Icon-only bar: bigger icons, no labels (the label is still read out by TalkBack).
                            icon = { Icon(if (selected) item.selectedIcon else item.icon, contentDescription = item.label, modifier = Modifier.size(26.dp)) },
                            alwaysShowLabel = false,
                        )
                    }
                    // ☰ — opens a small menu: Tools, then Settings.
                    var menuOpen by remember { mutableStateOf(false) }
                    val inMenu = destination?.hierarchy?.any { d -> menuRoutes.any { d.hasRoute(it) } } == true
                    NavigationBarItem(
                        selected = inMenu,
                        onClick = { menuOpen = true },
                        icon = {
                            Box {
                                Icon(if (inMenu) Icons.Rounded.Menu else Icons.Outlined.Menu, contentDescription = "Menu", modifier = Modifier.size(26.dp))
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, shape = RoundedCornerShape(16.dp)) {
                                    DropdownMenuItem(
                                        text = { Text("Tools") },
                                        leadingIcon = { Icon(Icons.Outlined.Handyman, null) },
                                        onClick = { menuOpen = false; switchTab(ToolsRoute) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Settings") },
                                        leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                                        onClick = { menuOpen = false; switchTab(SettingsRoute) },
                                    )
                                }
                            }
                        },
                        alwaysShowLabel = false,
                    )
                }
            }
        },
    ) { padding ->
        val bottom = PaddingValues(bottom = padding.calculateBottomPadding())
        // Consume the bottom inset here so each screen's own Scaffold doesn't pad for it twice.
        Box(Modifier.fillMaxSize().padding(bottom).consumeWindowInsets(bottom)) {
            NavHost(
                navController = nav,
                startDestination = PurchasesRoute(),
                enterTransition = { if (animations) fadeIn(tween(ENTER_MS)) else EnterTransition.None },
                exitTransition = { if (animations) fadeOut(tween(EXIT_MS)) else ExitTransition.None },
                popEnterTransition = { if (animations) fadeIn(tween(ENTER_MS)) else EnterTransition.None },
                popExitTransition = { if (animations) fadeOut(tween(EXIT_MS)) else ExitTransition.None },
            ) {
                composable<PurchasesRoute> { entry ->
                    val route = entry.toRoute<PurchasesRoute>()
                    val scoped = route.categoryId != null || route.uncategorized || route.paymentMethodId != null
                    PurchasesScreen(
                        onBack = if (scoped) goBack else null,
                        onAdd = { cat -> nav.navigate(PurchaseEditRoute(categoryId = cat)) },
                        onOpen = { id -> nav.navigate(PurchaseEditRoute(id = id)) },
                        onScan = { scanTarget = "auto"; pickForScan() },
                        links = purchaseLinks,
                    )
                }
                composable<CalendarRoute> {
                    CalendarScreen(
                        onOpenPurchase = { id -> nav.navigate(PurchaseEditRoute(id = id)) },
                        onOpenSubscription = { id -> nav.navigate(SubscriptionEditRoute(id = id)) },
                        onAddPurchase = { date -> nav.navigate(PurchaseEditRoute(date = date.toString())) },
                    )
                }
                composable<CategoriesRoute> { entry ->
                    val route = entry.toRoute<CategoriesRoute>()
                    CategoriesScreen(
                        onBack = if (route.folderId != null) goBack else null,
                        onOpenFolder = { id -> nav.navigate(CategoriesRoute(id)) },
                        onJumpToFolder = { id ->
                            if (!nav.popBackStack(CategoriesRoute(id), inclusive = false)) nav.navigate(CategoriesRoute(id))
                        },
                        onViewPurchases = { id, uncategorized -> nav.navigate(PurchasesRoute(categoryId = id, uncategorized = uncategorized)) },
                        onOpenPurchase = { id -> nav.navigate(PurchaseEditRoute(id = id)) },
                    )
                }
                composable<SubscriptionsRoute> {
                    SubscriptionsScreen(
                        onAdd = { nav.navigate(SubscriptionEditRoute()) },
                        onOpen = { id -> nav.navigate(SubscriptionEditRoute(id = id)) },
                        onScan = { scanTarget = "subscription"; pickForScan() },
                    )
                }
                composable<SettingsRoute> {
                    SettingsScreen(
                        onImport = { nav.navigate(ImportRoute) },
                        onPaymentMethods = { nav.navigate(PaymentMethodsRoute) },
                    )
                }
                composable<PurchaseEditRoute> {
                    PurchaseEditScreen(
                        onBack = goBack,
                        onSwitchToSubscription = { switchEditor(toSubscription = true) },
                    )
                }
                composable<SubscriptionEditRoute> {
                    SubscriptionEditScreen(
                        onBack = goBack,
                        onOpenPurchase = { id -> nav.navigate(PurchaseEditRoute(id = id)) },
                        onSwitchToPurchase = { switchEditor(toSubscription = false) },
                    )
                }
                composable<ScanRoute> {
                    ScanScreen(
                        onBack = goBack,
                        onContinue = ::openScanResult,
                        onOpenScannerSettings = { nav.navigate(ScannerSettingsRoute) },
                    )
                }
                composable<ToolsRoute> {
                    ToolsScreen(
                        onCheckSplit = { nav.navigate(CheckSplitRoute) },
                        onTipCalculator = { nav.navigate(TipCalculatorRoute) },
                        onCurrency = { nav.navigate(CurrencyRoute) },
                    )
                }
                composable<CheckSplitRoute> { CheckSplitScreen(onBack = goBack, onOpenScannerSettings = { nav.navigate(ScannerSettingsRoute) }) }
                composable<TipCalculatorRoute> { TipCalculatorScreen(onBack = goBack) }
                composable<CurrencyRoute> { CurrencyScreen(onBack = goBack) }
                composable<ScannerSettingsRoute> {
                    SettingsScreen(
                        onImport = { nav.navigate(ImportRoute) },
                        onPaymentMethods = { nav.navigate(PaymentMethodsRoute) },
                        initialPage = com.jlees.budgey.ui.settings.SettingsPage.SCANNER,
                        onExit = goBack,
                    )
                }
                composable<ImportRoute> {
                    ImportScreen(onBack = goBack)
                }
                composable<PaymentMethodsRoute> {
                    PaymentMethodsScreen(
                        onBack = goBack,
                        onViewPurchases = { id -> nav.navigate(PurchasesRoute(paymentMethodId = id)) },
                    )
                }
            }
        }
    }
}
