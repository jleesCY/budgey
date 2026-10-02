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

## Budgets
- **Rollover.** Carry unspent (or overspent) budget into the next period.
- **Custom budget periods.** A pay-cycle month, e.g. the 15th to the 14th.
- **Income tracking.** Income alongside expenses, giving net cash flow and savings rate.
- **Budget alerts.** Notify at 80% / 100% of a budget (reuses the reminders worker).

## Subscriptions
- **Price-change detection.** Flag when a logged charge differs from the stored price.
- **Cancel helpers.** A per-service "how to cancel" link or notes template.

## Data & platform
- **Multi-currency.** Amounts are already stored with a single currency in mind. This would add a per-item
  currency and manual exchange rates (offline).
- **Scheduled auto-backups** to a user-chosen folder (Storage Access Framework, no server).
- **App lock** with biometrics or device credential.
- **Home-screen widget** showing month-to-date spending and the next renewal.
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
