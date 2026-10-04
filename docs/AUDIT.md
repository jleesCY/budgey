# Code audit — October 2026

A full read-through of the app (data, scanning, main screens, settings/navigation/build) found the issues
below. **None of these are fixed yet.** They're ordered by how much they matter to someone using the app;
work down the list and tick items off (or delete them) as they land.

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
- [ ] **Bulk select acts on purchases you can't see** *(Back now clears the selection in 0.1.0; trimming hidden rows is still to do)* — `PurchasesScreen` / `PurchasesViewModel`. The
  selection survives searching/filtering, so "Set category" moves hidden rows and the delete count is
  wrong; Back leaves the screen instead of clearing the selection. Fix: `BackHandler(inSelection)`,
  trim the selection to visible rows, count the actual batch.
- [ ] **Suggested tips become the real tip in the check splitter** — `scan/ReceiptItems.kt`. Lines like
  "18% tip 8.10 / 20% tip 9.00" overwrite the tip, and a scan that only finds totals wipes hand-entered
  items. Fix: ignore lines with `%`/"suggested"; only replace items when the scan found some.
- [ ] **Words that start with a month are read as dates** — `scan/ReceiptParser.kt` (`monthAlt`).
  "WHOLE FOODS **MAR**KET 10" → Mar 10, "**Dec**af 2" → Dec 2. Fix: word-bounded month names; prefer
  dates that include a year.
- [ ] **Renewal reminders never arrive on Android 13+** — notification permission is only requested
  inside Settings, while reminders default to on (and Settings says "on"). Fix: ask in context (e.g. first
  subscription saved), show "On · notifications blocked" when denied, treat a disabled channel as blocked.
- [ ] **Settings keeps polling forever** — `SettingsViewModel` runs a `while (true)` loop every 4 s for
  the life of the process (tabs keep their view models), and one transient AICore error can silently
  switch your scanner back to Standard. Fix: a cold flow collected only while the Scanner page is shown;
  don't treat errors as "unsupported".
- [x] **Status-bar icons can be invisible** *(fixed in 0.1.0)* — when Budgey's theme differs from the phone's (e.g. app
  Light, phone Dark). Fix: `enableEdgeToEdge(SystemBarStyle.auto(...) { dark })` driven by the app theme.

## 🟠 Medium

**Money & data**
- [ ] **Decimal commas** — `domain/Money.parse`: "4,5" → $45.00, "1.234,56" → $1.23; trailing/Unicode
  minus and "(5.00)" read as positive; huge input overflows silently. Same family in the scanner's amount
  regex ("1.234,56", "1 234,56", "TOTAL – 23.45" read as negative). Fix: last separator is the decimal
  one; sign only when it touches the number; bounded digits.
- [ ] **dd/mm vs mm/dd** is always US order in scans; a "Dec 28" with no year scanned on Jan 2 is dropped.
  Fix: order from the phone's locale; roll yearless future dates back a year.
- [ ] **Keyword matching is substring-based** in the receipt parser ("apr" in April, "gal" in legal,
  "sale" in wholesale; "Regular coffee" makes a café receipt look like fuel). Fix: word boundaries;
  require a strong fuel signal.
- [ ] **"Renews today" reminders get lost** if you open the app before the 9 AM check (start-up auto-logs
  the payment first and moves the date). Fix: reminders before auto-logging at start-up.
- [ ] **Subscription save isn't atomic** (subscription, periods and history sync are separate
  transactions, and the history plan can go stale while the "Update past payments?" prompt is open) →
  possible duplicate payments. Fix: one transaction that recomputes the plan inside it.
- [ ] **Auto-log migration race** in `processDueSubscriptions` (two callers at start-up). Fix: a `Mutex`.
- [ ] **Import mixes price-history periods** (periods imported for subscriptions that were skipped;
  "Replace" keeps old periods). Fix: import periods only for imported subscriptions; clear on replace.
- [ ] **"Undo erase" isn't exact** — defaults are re-seeded before the restore, which then merges by name
  (custom icons/colours lost, deleted defaults return). Fix: erase, then restore with Replace and no merging.
- [ ] **Yearly totals off by a few cents** (`monthly × 12`, rounded twice) — Subscriptions header and
  `Periods.yearlyCost`. Also `BigDecimal(double)` makes some tips a cent high (`CheckSplit`, `Fx`) →
  use `BigDecimal.valueOf`.
- [ ] **Backups miss some settings** (animations, scanner, Wi-Fi-only downloads) and an old backup turns
  wallpaper colours on (`SettingsDto.dynamicColor` default). Export isn't a single-transaction snapshot.

**Scanning**
- [ ] **Vision AI warm-up is undone** as soon as the camera opens (`onTrimMemory(UI_HIDDEN)` releases
  the model), so every scan pays for a full model load. Fix: a short hold after warm-up / while reads queue.
- [ ] **Cancelled AI reads keep running** in the `:ai` process; `connect()` has no timeout; "model
  unloaded" is reported as a cancellation, so there's no fallback to Standard. A 95 %-downloaded model is
  treated as ready.
- [ ] **Scan results are lost if Android kills the app** while you're on the review screen or the editor
  it opened (review is only saved in `onCleared`; drafts are in memory). Fix: save to `PendingAdds` as soon
  as a scan finishes.
- [ ] **Scan screen dead ends** — no Cancel while working, "Try again" drops the photo, raw error text,
  no timeout on Gemini Nano.
- [ ] **OCR swallows cancellation** (`runCatching`) and recycles bitmaps ML Kit may still be reading.
- [ ] **Gemini Nano itemized reply gets cut off** on long receipts (256 tokens) and silently falls back.
- [ ] **AI prompts' example values leak into results** ("Cheeseburger", "...").

**Screens**
- [ ] **Rotating during "Update past payments?"** leaves the subscription stuck in edit mode (a UI lambda is
  stored in the view model). Fix: a one-shot "saved" event.
- [ ] **"Every N" custom cycle field can't be cleared** to type a new number.
- [ ] **Slow paths**: every purchase row recomposes on each search keystroke (rows take the whole UI
  state); the Calendar recomputes renewal history on the main thread per month page; the logo search
  re-normalizes ~950 names per keystroke.
- [ ] **"Clear all"/"Reset" filters** drop the category/payment method a filtered screen was opened for.
- [ ] **Contrast**: amber budget text (~1.8:1), the refund green in dark mode, raw category colours used as
  text; "ahead of pace" shown by colour only. Refunds are coloured differently on each screen.
- [ ] **Date fields can't be opened with TalkBack** or a keyboard.
- [x] **"Free trial ending soon"** also shows trials that ended long ago. *(fixed: ended trials get their own warning; older than 30 days are dropped)*
- [ ] **Sheets/dialogs lose input on rotation** (`remember` instead of `rememberSaveable`) in Categories,
  filters, category editor, price periods, payment methods, Settings, erase flow.
- [ ] **A deleted item opens as a blank screen** (and Edit → Save re-creates it). Needs a "not found" state.
- [ ] **Trend chart draws below its area** when refunds make the total negative.

**Settings, navigation & build**
- [ ] **Reopening from Recents replays** an old notification tap or share; a malformed share can crash
  start-up (`getParcelableExtra` on API 33, `ClassCastException`); `clipData`-only shares are ignored.
- [ ] **Text-size setting breaks Android 14's non-linear font scaling** (even at Default).
- [ ] **Privacy**: phone-to-phone transfer copies everything, including the post-erase safety copy, which
  is only pruned when Settings opens. Add `dataExtractionRules`; prune the safety copy at start-up.
- [x] **Release builds are signed with the debug key** *(0.1.0: release key from `keystore.properties`, debug key only as a fallback)*, so an install from another computer can't update
  (and uninstalling loses data). Add a release signing config from `keystore.properties`.
- [ ] **Filtered Purchases lists highlight the wrong tab**; re-tapping the current tab doesn't go to its
  root; fast double taps push screens twice (`dropUnlessResumed`).
- [ ] **Import screen**: Back mid-import deletes the unpacked photos while copying; double tap imports
  twice; Import is enabled with nothing selected.
- [x] **Font setting shows Google Sans Flex** *(0.1.0: build hints only show in debug builds; run `fetchFonts` before a release)* although the font isn't bundled; developer hints
  ("./gradlew …") are visible to users.
- [ ] **Wrong theme on the first frame** (white flash for Dark users).

## 🟡 Low

- [ ] Hand-set next-payment dates can later be offered for removal by the history sync.
- [ ] `deleteReceiptIfUnused` / clean-ups load whole tables (use `EXISTS` / projection queries).
- [ ] Every screen works from the entire purchase table; budgets re-filter it per category (fine now,
  slow with years of data — move totals into SQL).
- [ ] No migration tests; schema JSON for v4 and v5 is missing; the v4→5 payment-method migration is
  case-sensitive.
- [ ] Bad backup data is changed silently (unreadable dates become today; category cycles vanish).
- [ ] Compact money labels truncate instead of rounding; `NumberFormat` created on every call.
- [ ] Start-up parses the brand catalog on the main thread.
- [ ] Receipt import opens the picture three times and ignores EXIF flips.
- [ ] Rows on tilted receipt photos can pair the wrong label and amount (no de-skew).
- [ ] Refunds scanned from a receipt are saved as purchases.
- [ ] Currency converter: copying a de-DE result strips the wrong separator; "1,234.50" isn't accepted.
- [ ] Check-split save can be corrupted by two writers at once.
- [ ] Reminder job drifts from 9 AM (24 h periodic, no flex); offline days burn retries on rate updates.
- [ ] Settings messages are dropped when you're on another tab (`SharedFlow` without replay).
- [ ] Accessibility: unlabeled colour swatches, double focus stops on switch rows, small touch targets
  (legend chips, info buttons), category edit only by long-press without a label.
- [ ] Bulk delete/undo run item-by-item in separate coroutines (use one transaction).
- [ ] Custom date range is labelled "custom" in text ("spent custom").
- [ ] Price-history periods can't use a custom cycle.
- [ ] `imePadding` double-counts the navigation bar in the editors; no `ImeAction.Next` on fields.
- [ ] Strings aren't in resources (blocks translation; the notification channel name is shown by Android).
- [ ] Compose BOM and material3 alpha are mismatched; the "safe to accept upgrades" comment is wrong for
  Expressive APIs.
- [ ] Dead code: unused tap-to-pick number boxes, `OcrEngine.readRows`, `ScanDraft.switched`,
  `Spending.rolledUp/inRange`, always-true SDK checks (minSdk 31), the 118 KB legacy `brand_glyphs.json`,
  unused imports.

## Still to do

- **UI consistency pass** — not run yet. Compare top bars, button styles and wording, dialogs, sheets,
  spacing, typography, colours, empty states, snackbar vs toast, date/money formatting, icon families and
  terminology across every screen, then write the agreed conventions into `DEVELOPMENT.md`.
