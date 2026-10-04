# Budgey — developer guide

> The user-facing overview is in the main [README](../README.md). This page is for building and working on the code.

Budgey is a friendly little money bird: an offline budget tracker built with Kotlin, Jetpack Compose, and Material 3 Expressive.
Nothing goes to a bank or a server. The only network use is downloading an optional AI scanner model you pick in Settings → Scanner, and the optional daily currency-rate updates you can add in Settings → Tools (no personal data is sent; ML Kit's usage reporting is switched off).

## Getting started

1. Open this folder in **Android Studio** (any 2025.1+ release). Let it sync.
   - If sync says a version in `gradle/libs.versions.toml` can't be found, accept Studio's suggested
     version or bump it. These were pinned without network access.
   - The **Material 3 Expressive** APIs live in the `material3` alpha line (see the comment in the TOML).
2. *(Recommended)* Fetch brand logos (this is the only step that uses the network, and only at
   build time on your computer):
   ```
   ./gradlew :app:fetchBrandIcons            # logos for brands that don't have one yet
   ./gradlew :app:fetchBrandIcons -Prefresh  # re-download everything
   ```
   It looks up every brand in `assets/brands.json` (~950) in Simple Icons, then in Iconify's open icon
   sets, and writes `assets/brand_icons.json`. Brands it can't find are listed in
   `app/brand_icons_missing.txt`. To pin one by hand, add `"icon": "prefix:name"` (any id from
   icon-sets.iconify.design) to the brand. Brands without a logo show their category icon, never a letter.
   Similarly, `./gradlew :app:fetchFonts` bundles the **Google Sans Flex** font (Settings → Appearance → Font).
   Without it, the app uses the system font.
3. Run on your phone (minSdk 31 / Android 12+).
4. Unit tests: `./gradlew :app:testDebugUnitTest`. They cover the receipt parser, brand matching,
   filters, budget roll-ups and billing math.

5. *(Optional)* **AI scanners** — Settings → Scanner. Standard (built in) is the default. You can add:
   - **Gemini Nano** — Google's on-device AI, built into Android on supported phones. Budgey detects support
     automatically and only lists it where it works; if Android still has to fetch it, selecting it starts that.
   - **Vision AI** (Gemma 4 E2B, 2.59 GB) — looks at the original photo; needs 8 GB RAM.
   - After a Standard scan, the review screen offers **Rescan** (Gemini Nano if the phone has it, else
     Standard again) and **Advanced rescan** (Vision AI; if it isn't downloaded, it links to Scanner & AI
     models, and Back returns to the scan).
   - AI models only ever see the untouched photo. Budgey cleans up leftover/partial model files and
     their caches on launch, frees model memory when the app is backgrounded, and deletes a model's
     cache along with the model.

   Models download inside the app (Wi-Fi only by default, in the background, resumable) and can be deleted
   from the same screen. For development you can also run `./gradlew :app:fetchScanModel` /
   `:app:pushScanModel`, or import a `.litertlm` file.

> If `./gradlew` says "permission denied", run `chmod +x gradlew` once.

## What's in the app

| Area | Features |
|---|---|
| **Navigation** | Four tabs (Purchases, Subscriptions, Budgets & Categories, Calendar) plus a ☰ menu with **Settings** and **Tools**. Every add button is the same round **+** FAB (screen readers hear "Add purchase", "Add budget"…); on Purchases and Subscriptions it fans out Scan / Enter manually and turns into an ×. Unfinished new items (scan review or editor, as soon as any field differs from a blank form) are kept and offered as a separate **⟲ Resume** button just right of the + (4 dp gap; round outer ends, softer facing corners, so the pair reads as a pill) (`ui/components/AddFabMenu.kt`, `data/PendingAdds.kt`). **Purchases and subscriptions are separate flows:** there's no Purchase ⇄ Subscription switch in the editors, and scans started from a tab are that tab's kind (only an image shared in from another app asks "What is this?"). New items are added with the button at the bottom of the form (no Save in the top bar). Tapping a purchase or subscription opens a **read-only preview** first; the ✎ button switches to editing (Back leaves editing without saving). |
| **Tools** | **Check splitter**: scan a receipt or kiosk screen to pull in every item (uses your AI scanner when one is set up, otherwise the text reader), add people, and split **evenly**, by **custom** amounts or percentages, or **by item** (tap who had each item; shared items split evenly). Each person has a **Tip** switch, so the tip is split only between the people who chip in; by item, tax & fees follow what each person ordered and the tip can too. Rows with a count ("5 Burgers $50", "5x", "x5", "(5)", "5 @ 10.00", a QTY column; sizes like "12 oz" are not counts) are split into one item per unit (up to 20; leftover cents go to the first units) — `ReceiptItems.quantityOf` / `splitUnits`. The AI prompt asks for qty explicitly, a count left in the name is picked up, and unit prices are corrected when that makes the items match the subtotal. Every share is rounded **up** to the cent, and the extra is shown. Share the result as text. After a scan there's a photo preview (tap for full screen), **Rescan** (Gemini Nano if available, else Standard) and **Advanced Rescan** (Vision AI; links to Scanner & AI models if it isn't downloaded). Progress is saved as you go: leave and come back to **Continue** or **Start fresh**. New people start with an empty name (shown as "Person 2" etc. in results). **Tip calculator**: bill before tax, a 1–50% slider (20% default, shortcuts 10/15/18/20/22/25), and linked **percent** and **dollar** boxes — type either and the other follows; *Tip after tax* (off by default) adds a tax box and tips on bill + tax. Tips round up to the cent. **Currency converter** that works fully offline: ~160 currencies of central-bank rates are **built in** (refresh them before a release with `./gradlew :app:fetchFxRates`), and by default the converter never goes online. Settings → Tools has an optional **Daily rate updates** pack (download icon, ~10 KB; delete icon once installed) that keeps the rates current, updating once a day when online. Amounts show without currency prefixes. |
| **Purchases** (start screen) | A large centered chart at the top (5 styles: donut, pie, bars, cumulative trend, rings), with the total spent right under it. For the plain "this month" view it also shows the change vs. the same point last month and how much of your budgets is left (plus a safe daily amount). The chart style button sits on the chart, and the choice applies app-wide and is remembered. Tap a slice to drill into that category, ↑ to go back up. On the donut, pie and rings, **touch & hold** previews a category (donut: in the hole; pie/rings: a floating card; the slice pops out / others fade, and its legend chip lights up); keep holding and slide around the circle (rings: in and out) to preview others. Haptics: a firm tap (`VIRTUAL_KEY`) when picked up, a light tick (`SEGMENT_TICK`, `CLOCK_TICK` before Android 14) on every change. A drag that starts before the hold still scrolls the screen (`slicePicker` in `ui/components/Charts.kt`). Below that: quick date/category filter chips, nudges for free trials and uncategorized purchases, and fixed-size shortcut cards for **Upcoming renewals**, **Budgets** and **Last 7 days** (they open those tabs). Then the date-grouped list with daily totals. Search plus advanced filters: date presets or a custom range, categories (with or without sub-categories), uncategorized only, min/max amount, expenses/refunds, source (manual/scanned/subscription/imported), payment method, has receipt, and sort. Long-press for multi-select, then bulk-categorize or delete (with undo). |
| **Calendar** | A month grid you can swipe or step through with arrows, plus a Today button. Days are shaded by how much you spent, with the day number top-left, a small purchase-count badge top-right (capped at 99+), a soft ↻ mark under it on renewal days (upcoming or logged), and the day's total at the bottom. Tap a day to see its purchases and renewals, or add a purchase on that date. Day purchases use the same row as the Purchases list (`ui/purchases/PurchaseListItem.kt`). |
| **Add / edit purchase** | A **Purchase / Subscription** switch at the top of every new item flips between the two editors and keeps everything typed so far, in both directions. Live auto-icon from the merchant name; tap the icon to pick **Auto**, any of ~950 logos (each has a name and default category, so picking one also fills those in), **custom text** (up to 3 letters on a color), a **photo** (camera or gallery), or the **category** icon. Category suggestion (what you used last time for that merchant, otherwise the brand's default category). Merchant autocomplete, payment-method chips, refund toggle, receipt image, notes. Asks before saving a purchase without a category. |
| **Scanning** | One **Scan** button opens Android's chooser (camera, Photos, Files…), or use *Share → Budgey* from any app. OCR runs on-device with ML Kit's bundled model, on the full-resolution original. The parser knows three kinds of picture: **receipts**, **banking-app screenshots** (uses text size to find the headline amount and merchant, ignores the bank's own name, balances, rewards and "card ending" lines, reads "-$6.45" as a charge, cleans "SQ *", "TST*" etc.), and **fuel pumps / kiosk screens** (SALE / Total Sale, ignores gallons and price-per-gallon, restores a missing decimal point and cross-checks gallons × price). It pulls out the merchant, total, and date, and decides whether it's a **purchase or a subscription** (billing cycle, next billing date, trial end). Then a review screen opens the right editor pre-filled, and the image is attached. Attached images are downscaled to 1600 px and saved as WebP (about 100–250 KB instead of 3–5 MB). **$0 receipts:** a 0.00 counts only on a *Total* line (not subtotal/balance/change) of a receipt that shows a discount, coupon, comp, "free" or a negative amount; then it beats the AI's answer (small models tend to return the item price). The AI prompt says totals are after discounts and can be 0. $0 purchases can be saved. |
| **Categories / Budgets** | Categories are nested folders, as deep as you like. A folder view shows breadcrumbs, spending, budget progress (wavy expressive bar), sub-folders, subscriptions and recent purchases. Any category can have a weekly, monthly, quarterly or yearly budget, and a parent's budget includes all of its children. The Budgets view (shown first) lists every budget with "ahead of pace" and "over" states. Comes with 19 default categories (55 counting sub-categories), and "Add from defaults" re-adds any you deleted. Deleting a folder moves its contents up a level, so nothing is lost. |
| **Subscriptions** | Monthly and yearly totals, a "Coming up" row of fixed-size cards (logo, name, price per cycle, "renews in N days", date and category), a free-trial warning, and Active/Paused/Cancelled/All tabs. Cycles can be weekly through yearly or a custom "every N days/weeks/months/years". **Auto-log** (a switch on each subscription) turns each billing date into a purchase so budgets stay accurate (it backfills only from the next due date, never years of history). Each subscription shows its payment history and has a "Log payment" button. **Pause / cancel / resume without touching the past**: stopping records a "stopped on" date, and *Resume on a date…* turns the earlier run into a price-history period, then restarts billing on the chosen date in the same subscription. **Price history**: add earlier periods with their own dates, price and cycle (e.g. a student plan), and past payments are logged at those prices. Choosing *Only future* after a price change also keeps the old price as a history period. **Price = base + fees & taxes = total charged.** Editing the base or the fees recalculates the total; editing the total recalculates the fees. The base price is never overwritten, and the total is what gets logged. **The next payment is calculated automatically** from the start date and cycle (e.g. started Feb 20, monthly → Oct 20). Turn the switch off to set it by hand. **Past payments are backfilled.** A start date in the past logs a purchase for every billing date since then (skipping free-trial dates), and the calendar marks each one. **Editing a subscription syncs its history.** Changes to price, name, category, payment method, icon or schedule are offered for the already-logged auto payments, behind an "Update past payments?" prompt. Choosing *Only future* leaves the past untouched. Payments logged by hand are never changed. **Renewal reminders** are local notifications, checked daily around 9 AM. The lead time is set in Settings (on the day / 1 / 2 / 3 / 7 days before), and each subscription can override it or turn it off. Free trials get at least 2 days' warning. **Free trials:** nothing is billed, shown or auto-logged during a trial. Paid billing starts on the trial's end date and the rhythm counts from there (start Sep 1 + trial to Dec 1 → Dec 1, Jan 1, …), via `Renewals.paidFrom` / `nextPaidDate` (a trial with no end date is never billed; a *future* trial date left on an Active subscription is ignored, and switching a trial to Active before its end clears it). A trial that reaches its end date stays marked as a trial (no silent conversion) so it gets flagged: a **TRIAL_ENDED** notification (once, for trials that ended in the last 30 days), a red card per subscription on the Subscriptions page with **Keep it** (→ Active, end date kept so history knows the trial was free) and **Cancel it…** (opens the editor), "Trial ended … · now paid" in the list, and a nudge on Purchases. Earlier price-history periods are never treated as trial time. |
| **Payment methods** | Saved in Settings → Payments: name, optional last 4 digits, and an icon. Icons use the same picker as purchases: a bank/card/wallet logo (84 of them, listed first), any other logo, a symbol (cash, debit, gift card…), custom text, or your own photo. The icon is auto-picked from the name. Purchases and subscriptions pick from the list (with an "Add payment method" shortcut), so names are never mistyped. New purchases default to the method you last used at that merchant. Purchases can be filtered by method, and each method shows its usage. Old typed names were converted automatically. |
| **Settings** (☰ → Settings) | **Animations** switch (on by default; off makes every transition instant). Notifications (reminders on/off, lead time, send a test reminder). Font (system or Google Sans Flex, regular or rounded, bundled for offline use), text size (Small → Extra large, applied on top of the phone setting). Theme (system/light/dark), budgie-colored accent swatches or Material You, pure-black AMOLED mode, week start, uncategorized reminder. **Shrink saved pictures** re-compresses images from older versions. **Export everything** to a `.zip`. **Selective import**: tick categories in a tree (their purchases, subscriptions and budgets come with them), choose where they go, merge categories with the same name, skip lookalike duplicates, and pick a conflict rule (keep mine / replace / keep both). **Erase all data** takes three deliberate steps (see what will be lost + "Export first", tick "I understand", type ERASE and wait out a 5-second countdown), and even then a safety copy stays on the phone for 7 days (*Restore erased data*). |
| **Budgey look** | Budgie-green default theme with a sunny-yellow accent, swatches named after budgie color varieties, a budgie-hugging-a-coin app icon (with a themed-icon version), and the little bird perched on empty states and the Purchases title. |

## Making a release

1. **Bump the version** at the top of `app/build.gradle.kts`: `appVersionName` (e.g. `"0.2.0"`) and
   `appVersionCode` (+1 every release — Android won't install an APK over one with the same or a higher code).
2. **One-time: create a release key** and keep it safe (back it up — losing it means people can't update):
   ```
   keytool -genkeypair -v -keystore ~/budgey-release.jks -alias budgey -keyalg RSA -keysize 4096 -validity 10000
   ```
   Then create `keystore.properties` in the project root (git-ignored):
   ```
   storeFile=/Users/you/budgey-release.jks
   storePassword=…
   keyAlias=budgey
   keyPassword=…
   ```
   Without it, release builds are signed with the computer's debug key (fine for testing, not for publishing —
   a debug-signed install can't be updated by a properly signed one without uninstalling, which erases data).
3. **Fetch the bundled data, then build** (from the project root):
   ```
   ./gradlew clean
   ./gradlew :app:fetchBrandIcons -Prefresh :app:fetchFonts :app:fetchFxRates
   ./gradlew clean :app:assembleRelease :app:testDebugUnitTest
   ```
4. The APK is **`app/build/outputs/apk/release/Budgey-<version>-release.apk`** — named from `appVersionName`
   via `base.archivesName` in `app/build.gradle.kts`.
5. **Publish**: commit, tag (`git tag v0.1.0 && git push --tags`), then on GitHub → Releases → *Draft a new
   release* → pick the tag, paste the notes from `docs/releases/`, and attach the APK. Add the release to
   [CHANGELOG.md](../CHANGELOG.md).

## Architecture & key decisions (and why)

- **Native Kotlin + Compose.** First-class Material 3 Expressive support. The data/domain layers are
  plain Kotlin (no Android types in `domain/`, `scan/ReceiptParser`, `icons/BrandMatcher`), so a
  move to **Kotlin Multiplatform** later mostly means moving files.
- **Room (SQLite)** for storage. Records use **UUID string IDs** instead of auto-increment ints, which lets
  exports from different phones merge without collisions and re-imports be recognized as duplicates.
- **Money is `Long` cents.** No floating-point drift. Currency is fixed to USD for now, but all formatting
  goes through `domain/Money.kt`, and the export records the currency.
- **Filtering happens in memory** rather than through dynamic SQL. For a personal budget (tens of thousands of rows)
  it's instant, and it makes adding new filter types trivial.
- **Budgets live on categories** (`budgetCents` + `budgetPeriod`): categories are folders
  and a budget is optional on any of them.
- **Brand icons** have two parts. `assets/brands.json` (~950 merchants, banks and services with aliases,
  brand colors and default categories) does the matching, offline, and you can edit it. Everyday-word
  names (Target, Ring, Zip, Current…) are marked `strict`, so they only match when they're the whole
  merchant name. `assets/brand_icons.json` holds the logos fetched by the Gradle task from
  [Simple Icons](https://simpleicons.org) (CC0) and Iconify's open icon sets; full-color and outline
  ones are SVGs rendered with AndroidSVG. The matcher handles
  bank-statement noise like `SQ *STARBUCKS #1234` and `AMZN Mktp US*2K4`.
- **Scanning = ML Kit bundled OCR + our own parser.** It rebuilds receipt *rows* from text bounding boxes,
  because OCR often splits "TOTAL" and "23.45" into separate columns. You always get an editable review.
- **Export format** is a `.zip` holding `data.json` (documented, versioned `schemaVersion`) plus
  `receipts/*.jpg`. Dates are ISO strings, money is cents, colors are `#AARRGGBB`, and each category
  carries its full name path. Any future iOS, desktop or web version can read it. See
  `data/backup/BackupModels.kt`.
- **Manual DI** (`AppContainer`) instead of Hilt. That means fewer build plugins for now, and it's easy to swap later.
- `android:allowBackup="false"`: your data only leaves the phone when you export it.

## Project layout

```
app/src/main/java/com/jlees/budgey/
  data/        Room entities & DAOs, repository, settings (DataStore), receipts, defaults, backup/
  domain/      Money, periods & billing cycles, category tree, filters, budget math (pure Kotlin)
  icons/       Brand catalog + matcher
  scan/        OCR engine (ML Kit), receipt parser, scan draft hand-off
  reminders/   Renewal reminder worker (WorkManager) + notifications
  ui/          theme/, components/ (charts, avatars, pickers…), navigation/, and one package per section (home, calendar, …)
app/src/main/assets/brands.json   ← add merchants here
```

## Performance notes

- **Judge speed on a release build.** Debug builds of Compose apps are much slower: the app is marked debuggable, R8 and the
  baseline profiles aren't applied, and wireless ADB adds overhead on top. In Android Studio, open **Build Variants** and pick
  `release` (signed with your release key, or this computer's debug key if there isn't one — see below). The release build is a separate app from the debug one
  (`.debug` suffix), so export/import to move data between them.
- Screen data is computed off the main thread (`flowOn(Dispatchers.Default)`), lists use keys and content types, navigation uses
  short 120–180 ms fades instead of the 700 ms default, and chart animations only redraw the canvas.

## Storage & memory rules

Every file the app writes has one owner, and is deleted as soon as its owner is done with it:

| File | Where | Freed when |
|---|---|---|
| Receipt photos | `filesDir/receipts` | Owned by the saved purchase/subscription, the editor session (`data/ReceiptSession.kt`) or an unfinished add (`PendingAdds`). Replacing or removing a photo in the editor deletes the session's copy right away; leaving without saving deletes photos attached during that visit; saving deletes the replaced photo; deleting an item deletes its photo unless another row uses it; bulk-deleted purchases free theirs when the Undo snackbar goes away; Discard on a scan or new item deletes its photo. |
| Icon pictures | `filesDir/icons` | Swept at launch when no purchase, subscription, payment method, category or unfinished add uses them. |
| Camera captures | `cacheDir/camera` | Deleted once copied (scan review, check splitter, editor) or when you pick from Photos instead (`data/TempFiles.kt`). |
| AI model input | `cacheDir/scan` | Unique name per scan; deleted in `finally` (also on failure or leaving mid-scan). |
| Unpacked backup | `cacheDir/import` | Streamed (never read whole into memory); deleted after a successful import, when leaving the import screen, and after the safety restore. |
| Check-split photo | `filesDir/tools` | One kept; others deleted when the screen closes. |

**Launch sweep** (`BudgeyApp.cleanupStorage`) catches leftovers from crashes or force-stops, but only touches files older than
the app's launch, so something you're adding in the first seconds is never swept. Bitmaps are recycled in `finally` blocks
(OCR passes, model input, Gemini Nano, re-compress), and rendered brand logos live in a 12 MB LRU cache that's cleared when
Android asks the app to trim memory.

## Known gaps / next ideas

- **Known issues:** the October 2026 code audit's findings, ranked, are in [AUDIT.md](AUDIT.md) (not fixed yet).
- **Feature ideas:** see [FUTURE_IDEAS.md](../FUTURE_IDEAS.md).
- Database is at schema **v6**: v2 reminderDays, v3 listPriceCents, v4 feesCents, v5 payment_methods + paymentMethodId, v6 subscription_periods + subscriptions.endDate. Migrations exist for each step; any schema change needs a new `Migration` in `AppDatabase.kt`.
