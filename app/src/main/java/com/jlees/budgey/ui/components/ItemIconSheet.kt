package com.jlees.budgey.ui.components

import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jlees.budgey.data.DefaultCategories
import com.jlees.budgey.data.db.CategoryEntity
import com.jlees.budgey.icons.Brand
import com.jlees.budgey.icons.BrandMatcher
import com.jlees.budgey.icons.IconRef
import kotlinx.coroutines.launch

enum class IconSheetMode { MERCHANT, PAYMENT }

/**
 * One icon chooser for purchases, subscriptions and payment methods:
 *  • Auto-detect (merchants) — the logo is picked from the name
 *  • Any logo from the catalog (every one has a name & default category)
 *  • Custom text — up to 3 letters on a color
 *  • Photo — take one or pick from the gallery (Android's camera-or-gallery chooser)
 *  • Category icon (merchants) / plain symbols (payment methods)
 *
 * [onSelect] gets the stored icon string, plus the brand when one was picked (so the editor can
 * fill in the name and category).
 */
@Composable
fun ItemIconSheet(
    mode: IconSheetMode,
    name: String,
    current: String?,
    category: CategoryEntity?,
    importImage: suspend (Uri) -> String,
    onSelect: (ref: String?, brand: Brand?) -> Unit,
    onDismiss: () -> Unit,
) {
    val catalog = LocalBrandCatalog.current
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var query by remember { mutableStateOf("") }
    val currentRef = remember(current) { IconRef.parse(current) }
    var showText by remember { mutableStateOf(currentRef is IconRef.Text) }
    var text by remember {
        mutableStateOf((currentRef as? IconRef.Text)?.text ?: initials(name))
    }
    var textColor by remember { mutableLongStateOf((currentRef as? IconRef.Text)?.color ?: CategoryColors.first()) }
    val pickImage = rememberImageSource(title = "Choose a picture") { uri ->
        // A picture that can't be read (corrupt, unsupported, a cloud photo that won't download)
        // shows a message instead of crashing.
        if (uri != null) scope.launch {
            runCatching { importImage(uri) }
                .onSuccess { onSelect("image:$it", null) }
                .onFailure { android.widget.Toast.makeText(context, "Couldn't use that picture", android.widget.Toast.LENGTH_SHORT).show() }
        }
    }
    val auto = remember(name) { catalog.match(name)?.takeIf { catalog.hasIcon(it) } }
    val selectedBrandId = (currentRef as? IconRef.BrandRef)?.id

    // Logos to show: search results, or everything grouped by category.
    val sections: List<Pair<String, List<Brand>>> = remember(query, mode) {
        val q = BrandMatcher.normalize(query)
        val pool = catalog.withIcons
        if (q.isNotEmpty()) {
            listOf("Results" to pool.filter { b ->
                BrandMatcher.normalize(b.name).contains(q) || b.aliases.any { BrandMatcher.normalize(it).contains(q) }
            })
        } else {
            val payment = pool.filter { it.payment }
            val groups = pool.filterNot { it.payment }.groupBy { b ->
                b.category?.let { key -> DefaultCategories.byKey(key)?.let { t -> parentName(key) ?: t.name } } ?: "Other"
            }.toSortedMap().map { (k, v) -> k to v }
            if (mode == IconSheetMode.PAYMENT) listOf("Banks, cards & wallets" to payment) + groups
            else groups + listOf("Banks, cards & wallets" to payment)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(76.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(horizontal = 16.dp).navigationBarsPadding(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (mode == IconSheetMode.PAYMENT) PaymentIcon(current ?: "none", 52.dp) else ItemIcon(current, name, category, size = 52.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Icon", style = MaterialTheme.typography.headlineSmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (mode == IconSheetMode.MERCHANT) OptionCard(
                            "Auto", Icons.Rounded.AutoAwesome, selected = currentRef is IconRef.Auto, Modifier.weight(1f),
                        ) { onSelect(null, null) }
                        OptionCard("Text", Icons.Rounded.TextFields, selected = showText || currentRef is IconRef.Text, Modifier.weight(1f)) { showText = !showText }
                        OptionCard("Photo", Icons.Rounded.AddAPhoto, selected = currentRef is IconRef.Image, Modifier.weight(1f)) { pickImage() }
                        if (mode == IconSheetMode.MERCHANT) OptionCard(
                            "Category", Icons.Rounded.Category, selected = currentRef is IconRef.None, Modifier.weight(1f),
                        ) { onSelect("", null) }
                    }
                    if (mode == IconSheetMode.MERCHANT && currentRef is IconRef.Auto) Text(
                        auto?.let { "Auto-detected: ${it.name}" } ?: "No logo recognized for \"${name.ifBlank { "…" }}\" — showing the category icon.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (showText) TextIconEditor(
                        text = text,
                        color = textColor,
                        onText = { text = it.take(IconRef.MAX_TEXT) },
                        onColor = { textColor = it },
                        onUse = { if (text.isNotBlank()) onSelect(IconRef.text(text, textColor), null) },
                    )
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        placeholder = { Text("Search ${catalog.withIcons.size} logos") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (mode == IconSheetMode.PAYMENT && query.isBlank()) {
                        Text("Symbols", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            PaymentGenericIcons.all.take(4).forEach { (key, label, _) ->
                                LogoCell(label, current == "generic:$key", Modifier.weight(1f), { onSelect("generic:$key", null) }) { PaymentIcon("generic:$key", 40.dp) }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            PaymentGenericIcons.all.drop(4).forEach { (key, label, _) ->
                                LogoCell(label, current == "generic:$key", Modifier.weight(1f), { onSelect("generic:$key", null) }) { PaymentIcon("generic:$key", 40.dp) }
                            }
                        }
                    }
                }
            }
            if (catalog.withIcons.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    // Build instructions only make sense to whoever is building the app.
                    if (com.jlees.budgey.BuildConfig.DEBUG) "No logos are bundled in this build yet. On your computer, run ./gradlew :app:fetchBrandIcons and rebuild."
                    else "No logos available in this version. You can still use a few letters or a photo.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
            sections.forEach { (title, brands) ->
                if (brands.isEmpty()) return@forEach
                item(span = { GridItemSpan(maxLineSpan) }, key = "h-$title") {
                    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                }
                items(brands, key = { "$title-${it.id}" }) { b ->
                    LogoCell(b.name, b.id == selectedBrandId, Modifier, { onSelect(IconRef.brand(b.id), b) }) { BrandAvatar(b, size = 44.dp) }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private fun parentName(key: String): String? =
    DefaultCategories.templates.firstOrNull { t -> t.key == key || t.children.any { it.key == key } }?.name

private fun initials(name: String): String =
    name.split(' ', '-', '&').filter { it.isNotBlank() }.take(IconRef.MAX_TEXT).joinToString("") { it.take(1).uppercase() }.ifEmpty { "" }

@Composable
private fun OptionCard(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null)
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TextIconEditor(text: String, color: Long, onText: (String) -> Unit, onColor: (Long) -> Unit, onUse: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextBadge(text.ifBlank { "?" }, Color(color), size = 48.dp)
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = onText,
                    label = { Text("Up to ${IconRef.MAX_TEXT} characters") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.weight(1f),
                )
            }
            ColorPicker(color.toInt(), { onColor(it.toLong() and 0xFFFFFFFFL) })
            Button(onClick = onUse, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Use this") }
        }
    }
}

@Composable
private fun LogoCell(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Column(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium) else Modifier)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon()
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}
