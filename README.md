<p align="center">
  <img src="docs/images/budgey-icon.svg" alt="Budgey app icon: a budgie hugging a gold coin" width="140">
</p>

<h1 align="center">Budgey</h1>

<p align="center">
  A friendly, private budget tracker for Android.<br>
  Track purchases, budgets and subscriptions — scan receipts, split checks, and keep everything on your phone.
</p>

<p align="center">
  <img alt="Version 0.1.0" src="https://img.shields.io/badge/version-0.1.0-F28C28">
  <img alt="Android 12+" src="https://img.shields.io/badge/Android-12%2B-3F9B3A">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF">
  <img alt="Material 3 Expressive" src="https://img.shields.io/badge/Material%203-Expressive-FFC83D">
  <img alt="License: MIT" src="https://img.shields.io/badge/License-MIT-2F6FB5">
</p>

---

## How it's built

Budgey is a native Android app written in **Kotlin** with **Jetpack Compose** and **Material 3 Expressive**.
It is offline-first: data lives in a local **Room (SQLite)** database, settings in **DataStore**, and money
is stored as whole cents so totals never drift. The domain logic (money, billing cycles, budget math, the
receipt parser, check splitting) is plain Kotlin with no Android dependencies, so it is unit-tested and
ready to share with other platforms later. Receipt scanning runs on the phone with **ML Kit** text
recognition plus Budgey's own parser, and optional AI scanners (**Gemini Nano** where the phone has it, or
a downloadable **Gemma 4** vision model via **LiteRT-LM**) run on-device too — the AI model runs in its own
process so a model crash can never take the app down. Background work (renewal reminders, optional
currency-rate updates) uses **WorkManager**.

Building from source, project layout and design decisions: **[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)**.

---

## Features

### Purchases
- A big overview chart (donut, pie, bars, trend or rings) with the month's total, change vs. last month,
  and how much of your budgets is left — tap a slice to drill into that category. On the donut, pie and
  rings, **touch and hold** a slice to preview that category (amount and share), then slide your finger
  around to preview others, with a crisp haptic tick each time it changes.
- The round **+** button to scan a receipt or type one in. Every purchase gets a logo automatically from ~950
  known merchants, banks and services, or pick your own (logo, a few letters, or a photo).
- Tapping a purchase opens a read-only preview first, so nothing changes by accident; tap ✎ to edit.
- Left a new purchase or subscription half-done (or a scan on its review screen)? A separate **⟲ Resume**
  button appears right beside the **+** (the two form a pill) to pick up exactly where you left off — as
  soon as you've entered anything at all.
- Purchases and subscriptions are added separately: + on Purchases (scan or by hand) always makes a
  purchase, + on Subscriptions always a subscription.
- Search and filters: dates, categories, amounts, payment method, source, receipts — plus multi-select to
  categorize or delete in bulk (with undo).

### Scanning receipts and screenshots
- One button opens your camera or photos. Budgey reads **receipts**, **banking-app screenshots** and
  **gas-pump or kiosk screens**, and works out the merchant, total, date — and whether it's a purchase or
  a subscription.
- Not quite right? **Rescan**, or **Advanced Rescan** with an AI model, from the review screen.
- Fully discounted receipts (a 100% off coupon, a comped item) come out as **$0.00**, not the item's price,
  and $0 purchases can be saved.
- Optional on-device AI scanners in Settings → Scanner & AI models. Your photos never leave the phone.

### Budgets & Categories
- Categories are folders you can nest as deep as you like.
- Give any category a weekly, monthly, quarterly or yearly budget; parent budgets include their children.
- The Budgets view shows what's on pace, ahead or over.

### Subscriptions
- See what you spend per month and per year, what's coming up next, and when free trials end.
- **Free trials** are free: no renewals show (or get logged) during the trial; billing starts the day it
  ends. When a trial ends you get a notification and a warning on the Subscriptions page — **Keep it**
  or cancel it.
- Payments can be logged automatically on each billing date, so budgets stay accurate.
- Price history (e.g. a student discount that ended), pause / cancel / resume without losing the past.
- Renewal reminders a day (or a few days) ahead.

### Calendar
- A month view shaded by how much you spent each day, with a purchase count and renewal markers.
- Tap a day to see everything on it, or add a purchase on that date. Purchases there look exactly like
  the Purchases list: category, payment method, and receipt / subscription marks.

### Tools (☰ → Tools)
- **Check splitter** — scan the bill to pull in every item, add people, then split **evenly**, by **custom**
  amounts or percentages, or **by item** (shared items split between whoever had them). A row that stands
  for several of the same thing ("5 Burgers … $50", "Burger x5", "5 @ 10.00") becomes one item per unit, so
  each burger can go to a different person. Choose who chips in
  for the tip. Everyone's share is rounded up to the cent. Progress is saved if you leave, and you can share
  the result with the group.
- **Tip calculator** — type a percentage or an amount and the other updates; tip before or after tax.
- **Currency converter** — about 160 currencies built in, works fully offline. Optionally add daily rate
  updates (a ~10 KB download) in Settings → Tools.

### Make it yours
- Light, dark or pure-black themes, budgie-colored accents or Material You colors.
- Google Sans Flex or your system font, adjustable text size, and an Animations switch.
- Saved payment methods with their own icons.

---

## Your data stays yours

- **No accounts, no ads, no analytics.** Everything is stored on your phone.
- Budgey only uses the internet when *you* choose to download an AI scanner model or turn on daily
  currency-rate updates — and those requests carry no personal data.
- **Export** everything (including receipt photos) to a single `.zip`, and **import** it again — all of it,
  or just the categories you pick — on the same or another phone.
- Erasing all data takes several deliberate steps, and a safety copy is kept for 7 days.

---

## Install

Budgey isn't on the Play Store yet. To install it:

1. Download **`Budgey-<version>-release.apk`** from the latest release on this repository's
   **[Releases](../../releases/latest)** page.
2. On your phone, open the APK and allow installing from that source when asked.
3. Later versions install right over it — your data stays.

Requires **Android 12** or newer. AI scanning needs a phone with at least 8 GB of RAM (or one with Gemini Nano).

> Already running a copy you built yourself in Android Studio? It may be signed with a different key, so
> Android won't update it with the release APK. **Settings → Export** a backup, uninstall, install the
> release, then **Import**.

Want to build it yourself? See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) — including
[how to make a release](docs/DEVELOPMENT.md#making-a-release).

---

## What's new

See the [changelog](CHANGELOG.md) — the latest is **[0.1.0](docs/releases/v0.1.0.md)**, the first release.

## Roadmap

Ideas being considered next are collected in [FUTURE_IDEAS.md](FUTURE_IDEAS.md). Known issues from the
latest code audit, ranked by severity, are tracked in [docs/AUDIT.md](docs/AUDIT.md).

## License

Budgey is released under the [MIT License](LICENSE).

Brand logos come from [Simple Icons](https://simpleicons.org) (CC0) and open icon sets via
[Iconify](https://iconify.design); all trademarks belong to their owners. Exchange rates come from
[Frankfurter](https://frankfurter.dev) (European Central Bank and other central banks). AI models:
Gemma 4 (Apache 2.0) and Gemini Nano (built into supported phones).
