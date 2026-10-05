# Code audit — October 2026

A full read-through of the app (data, scanning, main screens, settings/navigation/build) found the issues
below, ordered by how much they matter to someone using the app.

**Status (Oct 5, 2026 — Budgey 0.1.1):** every item is fixed (`[x]`) except four that are deliberately partial (`[~]`),
each with the reason: SQL-side totals, the v4/v5 schema JSON, moving every string into resources, and
the Compose BOM bump. Nothing here is verified on a device yet — build and run the app (and the
`MigrationTest` instrumented tests) before releasing.

Severity: 🔴 high (wrong data, lost data, crash) · 🟠 medium (wrong result in some cases, noticeable
slowness, privacy) · 🟡 low (edge cases, polish).

## 🔴 High

- [x] **Free-trial check wipes past payments** *(fixed: trials now only cover the current run, from its start date)* — `domain/Renewals.kt` `isFreeTrialDate`. A trial end date
  (or TRIAL status with no end date) counts *every* earlier date as free, including history periods, so
  editing the subscription offers to remove real past payments. Fix: dates before `anchorDate` are never
  trial dates. Add a test with a history period followed by a later trial.
- [x] **Double-tapping Save creates duplicates** *(fixed in 0.1.0)* — `PurchaseEditViewModel.save`, `SubscriptionEditViewModel.save`
  (and `logPaymentNow`). Each tap launches its own save with a fresh id, then `onBack` runs twice. Fix: a
  `saving` flag set before launching, buttons disabled while saving.
- [x] **Picking a picture that can't be read crashes the app** *(icon picker fixed in 0.1.0)* — `ui/components/ItemIconSheet.kt` (custom
  icon) and the new-payment-method save in `PaymentMethods.kt`. Fix: `runCatching` + a short message.
- [x] **Bulk select acts on purchases you can't see** *(fixed: only visible rows are selected, moved or deleted; the snackbar counts the batch)* — `PurchasesScreen` / `PurchasesViewModel`. The
  selection survives searching/filtering, so "Set category" moves hidden rows and the delete count is
  wrong; Back leaves the screen instead of clearing the selection. Fix: `BackHandler(inSelection)`,
  trim the selection to visible rows, count the actual batch.
- [x] **Suggested tips become the real tip in the check splitter** *(fixed: suggestions and tips printed after the total are ignored)* — `scan/ReceiptItems.kt`. Lines like
  "18% tip 8.10 / 20% tip 9.00" overwrite the tip, and a scan that only finds totals wipes hand-entered
  items. Fix: ignore lines with `%`/"suggested"; only replace items when the scan found some.
- [x] **Words that start with a month are read as dates** *(fixed: whole month names only)* — `scan/ReceiptParser.kt` (`monthAlt`).
  "WHOLE FOODS **MAR**KET 10" → Mar 10, "**Dec**af 2" → Dec 2. Fix: word-bounded month names; prefer
  dates that include a year.
- [x] **Renewal reminders never arrive on Android 13+** *(fixed: asked once when you have a subscription; Settings says "notifications blocked"; a turned-off channel counts as blocked; a tap after a denial opens system settings)* — notification permission is only requested
  inside Settings, while reminders default to on (and Settings says "on"). Fix: ask in context (e.g. first
  subscription saved), show "On · notifications blocked" when denied, treat a disabled channel as blocked.
- [x] **Settings keeps polling forever** *(fixed: rows refresh only while the Scanner page is open; a failed check is "unknown", never "unsupported")* — `SettingsViewModel` runs a `while (true)` loop every 4 s for
  the life of the process (tabs keep their view models), and one transient AICore error can silently
  switch your scanner back to Standard. Fix: a cold flow collected only while the Scanner page is shown;
  don't treat errors as "unsupported".
- [x] **Status-bar icons can be invisible** *(fixed in 0.1.0)* — when Budgey's theme differs from the phone's (e.g. app
  Light, phone Dark). Fix: `enableEdgeToEdge(SystemBarStyle.auto(...) { dark })` driven by the app theme.

## 🟠 Medium

**Money & data**
- [x] **Decimal commas** *(fixed)* — `domain/Money.parse`: "4,5" → $45.00, "1.234,56" → $1.23; trailing/Unicode
  minus and "(5.00)" read as positive; huge input overflows silently. Same family in the scanner's amount
  regex ("1.234,56", "1 234,56", "TOTAL – 23.45" read as negative). Fix: last separator is the decimal
  one; sign only when it touches the number; bounded digits.
- [x] **dd/mm vs mm/dd** *(fixed: follows the phone's region, with a sanity check; yearless dates roll back a year)* is always US order in scans; a "Dec 28" with no year scanned on Jan 2 is dropped.
  Fix: order from the phone's locale; roll yearless future dates back a year.
- [x] **Keyword matching is substring-based** *(fixed: whole words)* in the receipt parser ("apr" in April, "gal" in legal,
  "sale" in wholesale; "Regular coffee" makes a café receipt look like fuel). Fix: word boundaries;
  require a strong fuel signal.
- [x] **"Renews today" reminders get lost** *(fixed: start-up sends reminders before auto-logging)* if you open the app before the 9 AM check (start-up auto-logs
  the payment first and moves the date). Fix: reminders before auto-logging at start-up.
- [x] **Subscription save isn't atomic** *(fixed: one transaction)* (subscription, periods and history sync are separate
  transactions, and the history plan can go stale while the "Update past payments?" prompt is open) →
  possible duplicate payments. Fix: one transaction that recomputes the plan inside it.
- [x] **Auto-log migration race** *(fixed: Mutex)* in `processDueSubscriptions` (two callers at start-up). Fix: a `Mutex`.
- [x] **Import mixes price-history periods** *(fixed)* (periods imported for subscriptions that were skipped;
  "Replace" keeps old periods). Fix: import periods only for imported subscriptions; clear on replace.
- [x] **"Undo erase" isn't exact** *(fixed: exact restore)* — defaults are re-seeded before the restore, which then merges by name
  (custom icons/colours lost, deleted defaults return). Fix: erase, then restore with Replace and no merging.
- [x] **Yearly totals off by a few cents** *(fixed)* (`monthly × 12`, rounded twice) — Subscriptions header and
  `Periods.yearlyCost`. Also `BigDecimal(double)` makes some tips a cent high (`CheckSplit`, `Fx`) →
  use `BigDecimal.valueOf`.
- [x] **Backups miss some settings** *(fixed)* (animations, scanner, Wi-Fi-only downloads) and an old backup turns
  wallpaper colours on (`SettingsDto.dynamicColor` default). Export isn't a single-transaction snapshot.

**Scanning**
- [x] **Vision AI warm-up is undone** *(fixed: kept 2 min after a warm-up)* as soon as the camera opens (`onTrimMemory(UI_HIDDEN)` releases
  the model), so every scan pays for a full model load. Fix: a short hold after warm-up / while reads queue.
- [x] **Cancelled AI reads keep running** *(fixed)* in the `:ai` process; `connect()` has no timeout; "model
  unloaded" is reported as a cancellation, so there's no fallback to Standard. A 95 %-downloaded model is
  treated as ready.
- [x] **Scan results are lost if Android kills the app** *(fixed: saved for Resume as soon as they show)* while you're on the review screen or the editor
  it opened (review is only saved in `onCleared`; drafts are in memory). Fix: save to `PendingAdds` as soon
  as a scan finishes.
- [x] **Scan screen dead ends** *(fixed: Cancel, Try again with the same photo, friendly errors)* — no Cancel while working, "Try again" drops the photo, raw error text,
  no timeout on Gemini Nano.
- [x] **OCR swallows cancellation** *(fixed)* (`runCatching`) and recycles bitmaps ML Kit may still be reading.
- [x] **Gemini Nano itemized reply gets cut off** *(fixed: 768 tokens for items, and the fallback is noted)* on long receipts (256 tokens) and silently falls back.
- [x] **AI prompts' example values leak into results** *(fixed: placeholders only, echoed ones dropped)* ("Cheeseburger", "...").

**Screens**
- [x] **Rotating during "Update past payments?"** *(fixed)* leaves the subscription stuck in edit mode (a UI lambda is
  stored in the view model). Fix: a one-shot "saved" event.
- [x] **"Every N" custom cycle field can't be cleared** *(fixed: edited as text, limited per unit)* to type a new number.
- [x] **Slow paths** *(fixed: rows get just their category and method; search waits 250 ms after typing; Calendar months are worked out off the UI thread and cached; logo names are normalized once and the search waits for typing to pause)*: every purchase row recomposes on each search keystroke (rows take the whole UI
  state); the Calendar recomputes renewal history on the main thread per month page; the logo search
  re-normalizes ~950 names per keystroke.
- [x] **"Clear all"/"Reset" filters** *(fixed: they go back to what the screen was opened for)* drop the category/payment method a filtered screen was opened for.
- [x] **Contrast** *(fixed: budget words use readable colours and say "ahead of pace"; one refund colour everywhere, readable in light and dark; the chart preview's percentage is darkened/lightened until readable)*: amber budget text (~1.8:1), the refund green in dark mode, raw category colours used as
  text; "ahead of pace" shown by colour only. Refunds are coloured differently on each screen.
- [x] **Date fields can't be opened with TalkBack** *(fixed: a TalkBack double tap, Enter or Space opens the calendar)* or a keyboard.
- [x] **"Free trial ending soon"** also shows trials that ended long ago. *(fixed: ended trials get their own warning; older than 30 days are dropped)*
- [x] **Sheets/dialogs lose input on rotation** *(fixed: their fields and open/closed state use rememberSaveable; the few data classes they hold are Serializable)* (`remember` instead of `rememberSaveable`) in Categories,
  filters, category editor, price periods, payment methods, Settings, erase flow.
- [x] **A deleted item opens as a blank screen** *(fixed: a "This purchase / subscription is gone" screen, and saving never re-creates a deleted item)* (and Edit → Save re-creates it). Needs a "not found" state.
- [x] **Trend chart draws below its area** *(fixed: scales to cover negative totals, with a zero line)* when refunds make the total negative.

**Settings, navigation & build**
- [x] **Reopening from Recents replays** *(fixed: launches from Recents are ignored; shares are read safely, including clipData-only ones; file:// and our own files are refused)* an old notification tap or share; a malformed share can crash
  start-up (`getParcelableExtra` on API 33, `ClassCastException`); `clipData`-only shares are ignored.
- [x] **Text-size setting breaks Android 14's non-linear font scaling** *(fixed: Default leaves Android's scaling alone; other sizes cap at 2×)* (even at Default).
- [x] **Privacy** *(fixed: transfers skip the safety copy, models and resume drafts; no cloud backup; the safety copy is pruned at start-up and can be deleted early)*: phone-to-phone transfer copies everything, including the post-erase safety copy, which
  is only pruned when Settings opens. Add `dataExtractionRules`; prune the safety copy at start-up.
- [x] **Release builds are signed with the debug key** *(0.1.0: release key from `keystore.properties`, debug key only as a fallback)*, so an install from another computer can't update
  (and uninstalling loses data). Add a release signing config from `keystore.properties`.
- [x] **Filtered Purchases lists highlight the wrong tab** *(fixed: the tab you came from stays highlighted; re-tapping a tab goes to its first screen; screens open only once per tap)*; re-tapping the current tab doesn't go to its
  root; fast double taps push screens twice (`dropUnlessResumed`).
- [x] **Import screen** *(fixed: Back waits while importing, the import runs to the end even if you leave, one tap = one import, Import needs a selection)*: Back mid-import deletes the unpacked photos while copying; double tap imports
  twice; Import is enabled with nothing selected.
- [x] **Font setting shows Google Sans Flex** *(0.1.0: build hints only show in debug builds; run `fetchFonts` before a release)* although the font isn't bundled; developer hints
  ("./gradlew …") are visible to users.
- [x] **Wrong theme on the first frame** *(fixed: settings are read before the first frame)* (white flash for Dark users).

## 🟡 Low

- [x] Hand-set next-payment dates can later be offered for removal by the history sync.
- [x] `deleteReceiptIfUnused` / clean-ups load whole tables (use `EXISTS` / projection queries).
- [~] Every screen works from the entire purchase table; budgets re-filter it per category (fine now,
  slow with years of data — move totals into SQL). *(partly fixed: budgets now total every category in one pass per period instead of re-filtering per category. Moving totals into SQL is a bigger restructuring of every screen's data flow, best done when it's actually needed)*
- [~] No migration tests; schema JSON for v4 and v5 is missing; the v4→5 payment-method migration is
  case-sensitive. *(partly fixed: migration tests (v1→6, v3→6) and the case-insensitive v4→5 fix are done. The v4 and v5 schema JSON can't be regenerated after the fact; the tests start from v1 and v3, which exist)*
- [x] Bad backup data is changed silently (unreadable dates become today; category cycles vanish). *(fixed: damaged rows are skipped and counted)*
- [x] Compact money labels truncate instead of rounding; `NumberFormat` created on every call.
- [x] Start-up parses the brand catalog on the main thread.
- [x] Receipt import opens the picture three times and ignores EXIF flips.
- [x] Rows on tilted receipt photos can pair the wrong label and amount (no de-skew). *(fixed: de-skewed by the median line angle)*
- [x] Refunds scanned from a receipt are saved as purchases.
- [x] Currency converter: copying a de-DE result strips the wrong separator; "1,234.50" isn't accepted.
- [x] Check-split save can be corrupted by two writers at once.
- [x] Reminder job drifts from 9 AM (24 h periodic, no flex); offline days burn retries on rate updates.
- [x] Settings messages are dropped when you're on another tab (`SharedFlow` without replay). *(fixed: a Channel holds them until shown)*
- [x] Accessibility: unlabeled colour swatches, double focus stops on switch rows, small touch targets
  (legend chips, info buttons), category edit only by long-press without a label. *(fixed: colour swatches are 48 dp, named ("Dark blue") and announced as choices; switch and checkbox rows are one focus stop; the scanner info button is full size; legend chips are buttons; long-press actions are named ("Edit category", "Select"))*
- [x] Bulk delete/undo run item-by-item in separate coroutines (use one transaction).
- [x] Custom date range is labelled "custom" in text ("spent custom"). *(fixed: "from Mar 3 to Apr 2")*
- [x] Price-history periods can't use a custom cycle.
- [x] `imePadding` double-counts the navigation bar in the editors; no `ImeAction.Next` on fields. *(fixed: the Scaffold padding is consumed first in every editor and tool; name fields and the first price fields move on with Next)*
- [~] Strings aren't in resources (blocks translation; the notification channel name is shown by Android). *(partly fixed: the notification channel name and description are in `strings.xml`. Moving every string is a large separate job, left for when translation is planned)*
- [~] Compose BOM and material3 alpha are mismatched; the "safe to accept upgrades" comment is wrong for
  Expressive APIs. *(partly fixed: the comment in `libs.versions.toml` now says not to move material3 off
  the alpha line. The versions themselves weren't changed: Gradle already resolves Compose UI/foundation
  up to what material3 1.4.0-alpha15 needs, and a version bump can't be build-tested here — do it in
  Android Studio by moving the BOM to the release that matches the material3 alpha.)*
- [x] Dead code: unused tap-to-pick number boxes, `OcrEngine.readRows`, `ScanDraft.switched`,
  `Spending.rolledUp/inRange`, always-true SDK checks (minSdk 31), the 118 KB legacy `brand_glyphs.json`,
  unused imports. *(fixed: all removed — plus the unused payment-method queries. `brand_glyphs.json` is
  also excluded from the APK in `build.gradle.kts`, so a stale copy can't ship; delete it from
  `app/src/main/assets/` if it's still there. Imports these fixes made unused were removed; for any others run
  Code → Optimize Imports in Android Studio — the compiler doesn't report them.)*

## Still to do

- [x] **UI consistency pass** *(done: dialogs, scan-text views, day headings, refund colours,
  feedback and touch targets were made consistent; the agreed conventions are in
  [DEVELOPMENT.md → UI conventions](DEVELOPMENT.md#ui-conventions))*. Compared top bars, button
  styles and wording, dialogs, sheets, spacing, typography, colours, empty states, snackbar vs toast,
  date/money formatting, icon families and terminology across every screen.
