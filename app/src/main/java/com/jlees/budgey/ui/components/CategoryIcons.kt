package com.jlees.budgey.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Icons available for categories. Keys are stored in the database / export, so only ever
 * add keys — never rename them.
 */
object CategoryIcons {
    val all: Map<String, ImageVector> = linkedMapOf(
        "folder" to Icons.Rounded.Folder,
        "home" to Icons.Rounded.Home,
        "handyman" to Icons.Rounded.Handyman,
        "chair" to Icons.Rounded.Chair,
        "bolt" to Icons.Rounded.Bolt,
        "lightbulb" to Icons.Rounded.Lightbulb,
        "water" to Icons.Rounded.WaterDrop,
        "fire" to Icons.Rounded.LocalFireDepartment,
        "wifi" to Icons.Rounded.Wifi,
        "phone" to Icons.Rounded.PhoneAndroid,
        "delete" to Icons.Rounded.Delete,
        "cart" to Icons.Rounded.ShoppingCart,
        "restaurant" to Icons.Rounded.Restaurant,
        "fastfood" to Icons.Rounded.Fastfood,
        "coffee" to Icons.Rounded.LocalCafe,
        "delivery" to Icons.Rounded.DeliveryDining,
        "bar" to Icons.Rounded.LocalBar,
        "pizza" to Icons.Rounded.LocalPizza,
        "car" to Icons.Rounded.DirectionsCar,
        "gas" to Icons.Rounded.LocalGasStation,
        "parking" to Icons.Rounded.LocalParking,
        "taxi" to Icons.Rounded.LocalTaxi,
        "bus" to Icons.Rounded.DirectionsBus,
        "train" to Icons.Rounded.Train,
        "bike" to Icons.Rounded.PedalBike,
        "build" to Icons.Rounded.Build,
        "movie" to Icons.Rounded.Movie,
        "tv" to Icons.Rounded.LiveTv,
        "games" to Icons.Rounded.SportsEsports,
        "music" to Icons.Rounded.MusicNote,
        "ticket" to Icons.Rounded.ConfirmationNumber,
        "brush" to Icons.Rounded.Brush,
        "bag" to Icons.Rounded.ShoppingBag,
        "checkroom" to Icons.Rounded.Checkroom,
        "devices" to Icons.Rounded.Devices,
        "cleaning" to Icons.Rounded.CleaningServices,
        "store" to Icons.Rounded.Storefront,
        "health" to Icons.Rounded.Favorite,
        "hospital" to Icons.Rounded.LocalHospital,
        "pharmacy" to Icons.Rounded.LocalPharmacy,
        "fitness" to Icons.Rounded.FitnessCenter,
        "face" to Icons.Rounded.Face,
        "shield" to Icons.Rounded.Shield,
        "spa" to Icons.Rounded.Spa,
        "apps" to Icons.Rounded.Apps,
        "cloud" to Icons.Rounded.Cloud,
        "school" to Icons.Rounded.School,
        "book" to Icons.Rounded.AutoStories,
        "flight" to Icons.Rounded.Flight,
        "hotel" to Icons.Rounded.Hotel,
        "beach" to Icons.Rounded.BeachAccess,
        "gift" to Icons.Rounded.CardGiftcard,
        "donate" to Icons.Rounded.VolunteerActivism,
        "pets" to Icons.Rounded.Pets,
        "child" to Icons.Rounded.ChildCare,
        "card" to Icons.Rounded.CreditCard,
        "receipt" to Icons.Rounded.Receipt,
        "bank" to Icons.Rounded.AccountBalance,
        "savings" to Icons.Rounded.Savings,
        "payments" to Icons.Rounded.Payments,
        "work" to Icons.Rounded.Work,
        "celebration" to Icons.Rounded.Celebration,
        "star" to Icons.Rounded.Star,
        "sports" to Icons.Rounded.SportsBasketball,
        "park" to Icons.Rounded.Park,
        "laundry" to Icons.Rounded.LocalLaundryService,
        "haircut" to Icons.Rounded.ContentCut,
        "subscriptions" to Icons.Rounded.Subscriptions,
        "computer" to Icons.Rounded.Computer,
        "category" to Icons.Rounded.Category,
    )

    fun get(key: String?): ImageVector = all[key] ?: Icons.Rounded.Folder
}

/** Palette offered when picking a category color. */
val CategoryColors: List<Long> = listOf(
    0xFFE53935, 0xFFD81B60, 0xFF8E24AA, 0xFF5E35B1, 0xFF3949AB, 0xFF1E88E5, 0xFF039BE5, 0xFF00ACC1,
    0xFF00897B, 0xFF43A047, 0xFF7CB342, 0xFFC0CA33, 0xFFFDD835, 0xFFFFB300, 0xFFFB8C00, 0xFFF4511E,
    0xFF6D4C41, 0xFF757575, 0xFF546E7A, 0xFF2E5E4E,
)
