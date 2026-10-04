# Future ideas

A parking lot for features we've discussed but deliberately deferred. Each one notes why it's deferred
and roughly what it would take.

## Purchases
- **Split purchases.** One receipt spread across several categories (e.g. a Target run that's part
  groceries, part household). This would need a `purchase_splits` table (purchaseId, categoryId, amount),
  plus budgets and charts that read splits when they exist and the plain purchase otherwise.
  It's deferred to keep purchases simple for now.
- **Line items from receipts.** Have OCR pull out individual items (name, quantity, price), which would feed into splits.
- **Tags.** Free-form labels across categories, like "vacation-2026" or "work-reimbursable".
- **Recurring non-subscription expenses** (rent, utilities with variable amounts) with "expected vs. actual".
- **Recurring-charge detection.** Spot a merchant charged at a regular interval and offer to turn it into a
  subscription with one tap.
- **Swipe actions** on purchase rows (categorize / delete with Undo) and "select all" per day.
- **Saved filters.** Pin a filter set (e.g. "Coffee · this month") as a quick chip; search notes too.
- **CSV export** of the current filtered view (and CSV/XLSX import) for spreadsheets and taxes.

## Budgets
- **Rollover.** Carry unspent (or overspent) budget into the next period.
- **Custom budget periods.** A pay-cycle month, e.g. the 15th to the 14th.
- **Income tracking.** Income alongside expenses, giving net cash flow and savings rate.
- **Budget alerts.** Notify at 80% / 100% of a budget (reuses the reminders worker).
- **Pace marker.** A tick on each budget bar showing where spending "should" be today, plus a projected
  month-end total (including upcoming renewals on the calendar).

## Subscriptions
- **Price-change detection.** Flag when a logged charge differs from the stored price.
- **Cancel helpers.** A per-service "how to cancel" link or notes template.
- **Actions on reminder notifications:** "Mark cancelled", "Snooze", "Don't log this one".
- **Trial wrap-up.** "Trial ended — still subscribed?" for trials that have passed.

## Scanning
- **Crop & straighten** before reading (ML Kit Document Scanner or a manual crop) — fixes tilted receipts.
- **"Doesn't add up" check.** Compare items + tax + fees + tip (or subtotal + tax) with the total and
  highlight the mismatched lines in "View text".
- **Multi-page / long receipts** from several photos.
- **Currency detection** (€, £, ISO codes) feeding the multi-currency idea below.
- **Learn from corrections.** Remember per-merchant patterns locally (total label, date format).
- **Parser test corpus.** Anonymized "View text" dumps with expected answers, run as unit tests.
- **Check splitter extras:** round each share to $0.25/$1, share as a payment-request message, remember
  frequent people.

## Data & platform
- **Multi-currency.** Amounts are already stored with a single currency in mind. This would add a per-item
  currency and manual exchange rates (offline).
- **Scheduled auto-backups** to a user-chosen folder (Storage Access Framework, no server).
- **App lock** with biometrics or device credential.
- **Home-screen widget** showing month-to-date spending and the next renewal.
- **App shortcuts / Quick Settings tile** for "Scan" and "Add purchase".
- **Password-protected backups** (the zip contains receipt photos).
- **Translations** once strings move into resources.
- **Kotlin Multiplatform** (iOS/desktop). The domain/scan/backup code is already plain Kotlin.
- **Baseline Profile module** (macrobenchmark) for an even faster cold start.

## Settings worth adding (brainstorm)
- **Default date range** for the Purchases tab (this month, last 30 days, pay period…).
- **Pay-day / budget month start** (e.g. budgets reset on the 15th). Pairs with custom budget periods.
- **Hide amounts** (privacy mode: blur totals until tapped) for using the app in public.
- **App lock** (fingerprint / face / PIN) and "lock after N minutes".
- **Scheduled backups** to a folder you choose (daily / weekly, keep last N).
- **Budget alert thresholds** (notify at 80% / 100%) and quiet hours for all notifications.
- **Reminder time of day** (currently ~9 AM).
- **Default payment method** and **default category** for new purchases.
- **Scan behavior**: always open the editor vs. show the review screen; keep or discard the original photo.
- **Image quality**: smaller / balanced / sharper receipt pictures.
- **Currency & number format** (symbol, decimal style) once multi-currency lands.
- **Haptics & sounds** (a soft chirp on save — very Budgey).
- **Start tab** (Purchases or Calendar, etc.).
- **Compact list density** for long purchase lists.
- **Show cents** or round to whole dollars in charts and the calendar.
