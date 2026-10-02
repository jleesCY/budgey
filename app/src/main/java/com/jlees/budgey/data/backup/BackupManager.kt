package com.jlees.budgey.data.backup

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.room.withTransaction
import com.jlees.budgey.BuildConfig
import com.jlees.budgey.data.AppSettings
import com.jlees.budgey.data.AppFont
import com.jlees.budgey.data.TextSize
import com.jlees.budgey.data.BudgetRepository
import com.jlees.budgey.data.ChartType
import com.jlees.budgey.data.SettingsRepository
import com.jlees.budgey.data.ThemeMode
import com.jlees.budgey.data.db.CategoryEntity
import com.jlees.budgey.data.db.PurchaseEntity
import com.jlees.budgey.data.db.PaymentMethodEntity
import com.jlees.budgey.data.db.SubscriptionPeriodEntity
import com.jlees.budgey.data.db.displayName
import com.jlees.budgey.data.db.PurchaseSource
import com.jlees.budgey.data.db.SubscriptionEntity
import com.jlees.budgey.data.db.SubscriptionStatus
import com.jlees.budgey.data.db.newId
import com.jlees.budgey.domain.BudgetPeriod
import com.jlees.budgey.domain.CategoryTree
import com.jlees.budgey.domain.CycleUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** What to do when an imported record already exists (same id). */
enum class DuplicateStrategy(val label: String, val description: String) {
    SKIP("Keep mine", "Existing items are left untouched"),
    REPLACE("Replace", "Imported items overwrite existing ones"),
    KEEP_BOTH("Keep both", "Imported items are added as copies"),
}

/** The user's choices on the import screen. */
data class ImportPlan(
    /** Backup category ids to import. Purchases/subscriptions in these categories come along. */
    val categoryIds: Set<String>,
    val includeUncategorizedPurchases: Boolean,
    val includeUncategorizedSubscriptions: Boolean,
    val includeSettings: Boolean,
    val duplicates: DuplicateStrategy = DuplicateStrategy.SKIP,
    /** Reuse an existing category with the same name at the same place instead of creating a twin. */
    val mergeCategoriesByName: Boolean = true,
    /** Skip purchases with identical date + merchant + amount to one already here. */
    val skipLookalikePurchases: Boolean = true,
    /** Where selected top-level categories land (null = top level). */
    val destinationParentId: String? = null,
)

data class ImportResult(
    val categoriesAdded: Int = 0,
    val categoriesMerged: Int = 0,
    val purchasesAdded: Int = 0,
    val purchasesSkipped: Int = 0,
    val subscriptionsAdded: Int = 0,
    val subscriptionsSkipped: Int = 0,
    val settingsApplied: Boolean = false,
)

/** A parsed backup waiting for the user to pick what to import. */
class LoadedBackup(val file: BackupFile, val receiptsDir: File, val iconsDir: File) {
    val categoryTree = CategoryTree(file.categories.map { it.toEntity() })
}

class BackupManager(
    private val context: Context,
    private val repository: BudgetRepository,
    private val settings: SettingsRepository,
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun suggestedFileName(): String = "budgey-backup-${LocalDate.now()}.zip"

    /** Dumps everything (all records, settings, receipt images) into a zip at [uri]. */
    suspend fun export(uri: Uri): Int = withContext(Dispatchers.IO) {
        val db = repository.database
        val categories = db.categoryDao().getAll()
        val tree = CategoryTree(categories)
        val purchases = db.purchaseDao().getAll()
        val subs = db.subscriptionDao().getAll()
        val methods = db.paymentMethodDao().getAll()
        val periods = db.subscriptionPeriodDao().getAll()
        val s = settings.current()
        val backup = BackupFile(
            appVersion = BuildConfig.VERSION_NAME,
            exportedAt = Instant.now().toString(),
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            categories = categories.map { it.toDto(tree.path(it.id).map { p -> p.name }) },
            purchases = purchases.map { it.toDto() },
            subscriptions = subs.map { it.toDto() },
            settings = s.toDto(),
            paymentMethods = methods.map { it.toDto() },
            subscriptionPeriods = periods.map { it.toDto() },
        )
        // Custom pictures used as icons by payment methods, purchases and subscriptions.
        val iconNames = (methods.map { it.icon } + purchases.mapNotNull { it.brandKey } + subs.mapNotNull { it.brandKey })
            .filter { it.startsWith("image:") }.map { it.removePrefix("image:") }.toSet()
        val receiptNames = (purchases.mapNotNull { it.receiptFile } + subs.mapNotNull { it.receiptFile }).toSet()
        val out = context.contentResolver.openOutputStream(uri, "wt")
            ?: error("Couldn't open the export location")
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("data.json"))
            zip.write(json.encodeToString(BackupFile.serializer(), backup).toByteArray())
            zip.closeEntry()
            receiptNames.forEach { name ->
                val f = repository.receipts.file(name)
                if (f.exists()) {
                    zip.putNextEntry(ZipEntry("receipts/$name"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            iconNames.forEach { name ->
                val f = repository.paymentIcons.file(name)
                if (f.exists()) {
                    zip.putNextEntry(ZipEntry("icons/$name"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        purchases.size + subs.size + categories.size
    }

    /** Reads a backup zip (or a bare data.json) without changing anything. */
    suspend fun load(uri: Uri): LoadedBackup = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "import").apply { deleteRecursively(); mkdirs() }
        val receiptsDir = File(tmp, "receipts").apply { mkdirs() }
        val iconsDir = File(tmp, "icons").apply { mkdirs() }
        var dataJson: String? = null
        val input = context.contentResolver.openInputStream(uri) ?: error("Couldn't open file")
        val bytes = input.use { it.readBytes() }
        val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        if (isZip) {
            ZipInputStream(bytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    when {
                        name == "data.json" -> dataJson = zip.readBytes().decodeToString()
                        name.startsWith("receipts/") && !entry.isDirectory -> {
                            val safe = File(name).name // guards against ../ paths
                            File(receiptsDir, safe).outputStream().use { zip.copyTo(it) }
                        }
                        name.startsWith("icons/") && !entry.isDirectory -> {
                            File(iconsDir, File(name).name).outputStream().use { zip.copyTo(it) }
                        }
                    }
                    entry = zip.nextEntry
                }
            }
        } else {
            dataJson = bytes.decodeToString()
        }
        val text = dataJson ?: error("This file doesn't contain budget data (data.json missing)")
        val file = json.decodeFromString(BackupFile.serializer(), text)
        require(file.format == BACKUP_FORMAT || file.format in LEGACY_BACKUP_FORMATS) { "Not a Budgey backup" }
        require(file.schemaVersion <= BACKUP_SCHEMA_VERSION) {
            "This backup was made by a newer version of the app (schema ${file.schemaVersion}). Update the app first."
        }
        LoadedBackup(file, receiptsDir, iconsDir)
    }

    suspend fun import(backup: LoadedBackup, plan: ImportPlan): ImportResult = withContext(Dispatchers.IO) {
        val db = repository.database
        val file = backup.file
        var result = ImportResult()

        db.withTransaction {
            val existingCats = db.categoryDao().getAll().toMutableList()
            val existingById = existingCats.associateBy { it.id }.toMutableMap()
            val catIdMap = HashMap<String, String>() // backup id -> local id

            // ---- Categories, parents first ----
            val selected = backup.categoryTree.flattened().map { it.first }.filter { it.id in plan.categoryIds }
            for (cat in selected) {
                val parentLocal = cat.parentId?.let { catIdMap[it] }
                    ?: if (cat.parentId != null && cat.parentId in plan.categoryIds) null else plan.destinationParentId
                val sameName = if (plan.mergeCategoriesByName) existingCats.firstOrNull {
                    it.parentId == parentLocal && it.name.equals(cat.name, ignoreCase = true)
                } else null
                val sameId = existingById[cat.id]
                when {
                    sameName != null -> {
                        catIdMap[cat.id] = sameName.id
                        if (plan.duplicates == DuplicateStrategy.REPLACE || (sameName.budgetCents == null && cat.budgetCents != null)) {
                            val merged = sameName.copy(
                                budgetCents = cat.budgetCents ?: sameName.budgetCents,
                                budgetPeriod = if (cat.budgetCents != null) cat.budgetPeriod else sameName.budgetPeriod,
                                icon = if (plan.duplicates == DuplicateStrategy.REPLACE) cat.icon else sameName.icon,
                                color = if (plan.duplicates == DuplicateStrategy.REPLACE) cat.color else sameName.color,
                            )
                            db.categoryDao().upsert(merged)
                        }
                        result = result.copy(categoriesMerged = result.categoriesMerged + 1)
                    }
                    sameId != null && plan.duplicates == DuplicateStrategy.SKIP -> {
                        catIdMap[cat.id] = sameId.id
                        result = result.copy(categoriesMerged = result.categoriesMerged + 1)
                    }
                    else -> {
                        val id = if (sameId != null && plan.duplicates == DuplicateStrategy.KEEP_BOTH) newId() else cat.id
                        val entity = cat.copy(id = id, parentId = parentLocal)
                        db.categoryDao().upsert(entity)
                        existingCats += entity
                        existingById[id] = entity
                        catIdMap[cat.id] = id
                        result = result.copy(categoriesAdded = result.categoriesAdded + 1)
                    }
                }
            }

            fun mapCategory(backupCatId: String?): Pair<Boolean, String?> = when {
                backupCatId == null -> false to null
                backupCatId in catIdMap -> true to catIdMap[backupCatId]
                else -> false to null
            }

            // ---- Custom icon pictures (payment methods, purchases, subscriptions) ----
            backup.iconsDir.listFiles()?.forEach { src ->
                val dst = repository.paymentIcons.file(src.name)
                if (!dst.exists()) src.copyTo(dst)
            }

            // ---- Payment methods: always brought in, merged by id or (case-insensitive) name ----
            val methodIdMap = HashMap<String, String>()
            val localMethods = db.paymentMethodDao().getAll().toMutableList()
            for (dto in file.paymentMethods) {
                val match = localMethods.firstOrNull { it.id == dto.id }
                    ?: localMethods.firstOrNull { it.name.equals(dto.name, ignoreCase = true) && it.last4 == dto.last4 }
                if (match != null) {
                    methodIdMap[dto.id] = match.id
                } else {
                    val entity = dto.toEntity()
                    db.paymentMethodDao().upsert(entity)
                    localMethods += entity
                    methodIdMap[dto.id] = entity.id
                    if (entity.icon.startsWith("image:")) {
                        val name = entity.icon.removePrefix("image:")
                        val src = File(backup.iconsDir, name)
                        val dst = repository.paymentIcons.file(name)
                        if (src.exists() && !dst.exists()) src.copyTo(dst)
                    }
                }
            }
            /** Linked id, or — for older backups with only a typed name — a saved method with that name (created if needed). */
            suspend fun mapMethod(id: String?, name: String): String? {
                id?.let { methodIdMap[it] }?.let { return it }
                val n = name.trim()
                if (n.isEmpty()) return null
                localMethods.firstOrNull { it.displayName.equals(n, true) || it.name.equals(n, true) }?.let { return it.id }
                val created = PaymentMethodEntity(name = n, sortOrder = localMethods.size)
                db.paymentMethodDao().upsert(created)
                localMethods += created
                return created.id
            }

            // ---- Subscriptions ----
            val subIdMap = HashMap<String, String>()
            val existingSubs = db.subscriptionDao().getAll().associateBy { it.id }
            for (dto in file.subscriptions) {
                val (inSelected, localCat) = mapCategory(dto.categoryId)
                val include = inSelected || (dto.categoryId == null && plan.includeUncategorizedSubscriptions) ||
                    (dto.categoryId != null && dto.categoryId !in backup.categoryTree.byId && plan.includeUncategorizedSubscriptions)
                if (!include) continue
                val existing = existingSubs[dto.id]
                if (existing != null && plan.duplicates == DuplicateStrategy.SKIP) {
                    subIdMap[dto.id] = existing.id
                    result = result.copy(subscriptionsSkipped = result.subscriptionsSkipped + 1)
                    continue
                }
                val id = if (existing != null && plan.duplicates == DuplicateStrategy.KEEP_BOTH) newId() else dto.id
                subIdMap[dto.id] = id
                db.subscriptionDao().upsert(dto.toEntity().copy(id = id, categoryId = localCat, paymentMethodId = mapMethod(dto.paymentMethodId, dto.paymentMethod)))
                copyReceipt(backup, dto.receiptFile)
                result = result.copy(subscriptionsAdded = result.subscriptionsAdded + 1)
            }

            // ---- Price-history periods of the subscriptions that were imported ----
            val existingPeriodIds = db.subscriptionPeriodDao().getAll().map { it.id }.toSet()
            val newPeriods = file.subscriptionPeriods.mapNotNull { dto ->
                val subId = subIdMap[dto.subscriptionId] ?: return@mapNotNull null
                if (dto.id in existingPeriodIds && plan.duplicates == DuplicateStrategy.SKIP) return@mapNotNull null
                val id = if (dto.id in existingPeriodIds && plan.duplicates == DuplicateStrategy.KEEP_BOTH) newId() else dto.id
                dto.toEntity()?.copy(id = id, subscriptionId = subId)
            }
            if (newPeriods.isNotEmpty()) db.subscriptionPeriodDao().upsertAll(newPeriods)

            // ---- Purchases ----
            val existingPurchases = db.purchaseDao().getAll()
            val existingPurchaseIds = existingPurchases.associateBy { it.id }
            val lookalikes = existingPurchases.map { Triple(it.date, it.merchant.lowercase(), it.amountCents) }.toHashSet()
            for (dto in file.purchases) {
                val (inSelected, localCat) = mapCategory(dto.categoryId)
                val include = inSelected || (dto.categoryId == null && plan.includeUncategorizedPurchases) ||
                    (dto.categoryId != null && dto.categoryId !in backup.categoryTree.byId && plan.includeUncategorizedPurchases)
                if (!include) continue
                val entity = dto.toEntity()
                val existing = existingPurchaseIds[dto.id]
                val skipLookalike = existing == null && plan.skipLookalikePurchases &&
                    Triple(entity.date, entity.merchant.lowercase(), entity.amountCents) in lookalikes
                if ((existing != null && plan.duplicates == DuplicateStrategy.SKIP) || skipLookalike) {
                    result = result.copy(purchasesSkipped = result.purchasesSkipped + 1)
                    continue
                }
                val id = if (existing != null && plan.duplicates == DuplicateStrategy.KEEP_BOTH) newId() else dto.id
                db.purchaseDao().upsert(
                    entity.copy(
                        id = id,
                        categoryId = localCat,
                        subscriptionId = dto.subscriptionId?.let { subIdMap[it] ?: it },
                        paymentMethodId = mapMethod(dto.paymentMethodId, dto.paymentMethod),
                    )
                )
                copyReceipt(backup, dto.receiptFile)
                result = result.copy(purchasesAdded = result.purchasesAdded + 1)
            }
        }

        val settingsDto = file.settings
        if (plan.includeSettings && settingsDto != null) {
            settings.update { it.applyDto(settingsDto) }
            result = result.copy(settingsApplied = true)
        }
        result
    }

    private fun copyReceipt(backup: LoadedBackup, name: String?) {
        if (name == null) return
        val src = File(backup.receiptsDir, name)
        val dst = repository.receipts.file(name)
        if (src.exists() && !dst.exists()) src.copyTo(dst)
    }
}

// ---------------- Mapping ----------------

private fun colorToHex(c: Int) = "#%08X".format(c)
private fun hexToColor(s: String, fallback: Int): Int =
    runCatching { s.removePrefix("#").toLong(16).let { if (s.length <= 7) it or 0xFF000000 else it }.toInt() }
        .getOrDefault(fallback)

private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
    name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

private fun date(s: String?): LocalDate? = s?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

fun CategoryEntity.toDto(path: List<String>) = CategoryDto(
    id, name, parentId, path, icon, colorToHex(color), budgetCents, budgetPeriod.name, sortOrder, templateKey, createdAt, updatedAt,
)

fun CategoryDto.toEntity() = CategoryEntity(
    id = id, name = name, parentId = parentId, icon = icon, color = hexToColor(color, 0xFF6750A4.toInt()),
    budgetCents = budgetCents, budgetPeriod = enumOr(budgetPeriod, BudgetPeriod.MONTHLY), sortOrder = sortOrder,
    templateKey = templateKey, createdAt = createdAt, updatedAt = updatedAt,
)

fun PurchaseEntity.toDto() = PurchaseDto(
    id, merchant, amountCents, date.toString(), categoryId, note, paymentMethod, brandKey, receiptFile,
    source.name, subscriptionId, createdAt, updatedAt, paymentMethodId,
)

fun PaymentMethodEntity.toDto() = PaymentMethodDto(id, name, icon, last4, sortOrder, archived, createdAt)

fun PaymentMethodDto.toEntity() = PaymentMethodEntity(
    id = id, name = name, icon = icon, last4 = last4, sortOrder = sortOrder, archived = archived, createdAt = createdAt,
)

fun PurchaseDto.toEntity() = PurchaseEntity(
    id = id, merchant = merchant, amountCents = amountCents, date = date(date) ?: LocalDate.now(),
    categoryId = categoryId, note = note, paymentMethod = paymentMethod, brandKey = brandKey, receiptFile = receiptFile,
    source = enumOr(source, PurchaseSource.IMPORT), subscriptionId = subscriptionId, createdAt = createdAt, updatedAt = updatedAt,
)

fun SubscriptionEntity.toDto() = SubscriptionDto(
    id, name, amountCents, cycleUnit.name, cycleCount, anchorDate.toString(), nextDueDate.toString(), categoryId, brandKey,
    status.name, autoLog, trialEndDate?.toString(), paymentMethod, note, receiptFile, createdAt, updatedAt,
    reminderDays, listPriceCents, taxRatePercent, feesCents, paymentMethodId, endDate?.toString(),
)

fun SubscriptionPeriodEntity.toDto() = SubscriptionPeriodDto(
    id, subscriptionId, startDate.toString(), endDate.toString(), amountCents, cycleUnit.name, cycleCount, label, createdAt,
)

fun SubscriptionPeriodDto.toEntity(): SubscriptionPeriodEntity? {
    val s = date(startDate) ?: return null
    val e = date(endDate) ?: return null
    return SubscriptionPeriodEntity(
        id = id, subscriptionId = subscriptionId, startDate = s, endDate = e, amountCents = amountCents,
        cycleUnit = enumOr(cycleUnit, CycleUnit.MONTH), cycleCount = cycleCount.coerceAtLeast(1), label = label, createdAt = createdAt,
    )
}

fun SubscriptionDto.toEntity(): SubscriptionEntity {
    val anchor = date(anchorDate) ?: LocalDate.now()
    return SubscriptionEntity(
        id = id, name = name, amountCents = amountCents, cycleUnit = enumOr(cycleUnit, CycleUnit.MONTH),
        cycleCount = cycleCount.coerceAtLeast(1), anchorDate = anchor, nextDueDate = date(nextDueDate) ?: anchor,
        categoryId = categoryId, brandKey = brandKey, status = enumOr(status, SubscriptionStatus.ACTIVE), autoLog = autoLog,
        trialEndDate = date(trialEndDate), paymentMethod = paymentMethod, note = note, receiptFile = receiptFile,
        createdAt = createdAt, updatedAt = updatedAt, reminderDays = reminderDays,
        listPriceCents = listPriceCents, taxRatePercent = taxRatePercent, endDate = date(endDate),
        feesCents = feesCents ?: listPriceCents?.let { amountCents - it },
    )
}

fun AppSettings.toDto() = SettingsDto(
    themeMode.name, dynamicColor, colorToHex(seedColor), amoled, chartType.name, firstDayOfWeek.name,
    true, nudgeUncategorized, renewalReminders, reminderDaysBefore, font.name, roundedFont, textSize.name,
)

fun AppSettings.applyDto(d: SettingsDto) = copy(
    themeMode = enumOr(d.themeMode, ThemeMode.SYSTEM),
    dynamicColor = d.dynamicColor,
    seedColor = hexToColor(d.seedColor, seedColor),
    amoled = d.amoled,
    chartType = enumOr(d.chartType, ChartType.DONUT),
    firstDayOfWeek = enumOr(d.firstDayOfWeek, DayOfWeek.SUNDAY),
    nudgeUncategorized = d.nudgeUncategorized,
    renewalReminders = d.renewalReminders,
    reminderDaysBefore = d.reminderDaysBefore.coerceIn(0, 14),
    font = enumOr(d.font, AppFont.GOOGLE_SANS_FLEX),
    roundedFont = d.roundedFont,
    textSize = enumOr(d.textSize, TextSize.DEFAULT),
)
