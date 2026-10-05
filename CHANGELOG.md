# Changelog

All notable changes to Budgey. Versions follow [semantic versioning](https://semver.org); the 0.x series
is early and may still change how things work. Full release notes for each version live in
[docs/releases/](docs/releases/).

## [0.1.1] — 2026-10-05

Fixes from the October 2026 code audit ([docs/AUDIT.md](docs/AUDIT.md)).

- **Money & dates**: decimal commas ("4,5", "1.234,56"), negatives in parentheses, and grouped thousands
  are read correctly; yearly totals no longer drift by a few cents; compact amounts round instead of
  truncating.
- **Scanning**: dates follow your phone's day/month order; words like "Decaf" or "Mayo" aren't dates;
  dashes aren't minus signs; refunds are recognised and saved as refunds; suggested tips don't become
  the tip; tilted photos are straightened; Cancel and "Try again" on the scan screen; scan results
  survive Android closing the app in the background; the AI model stays loaded while the camera is open.
- **Subscriptions**: saving is all-or-nothing; "renews today" reminders aren't skipped; hand-set
  payment dates are kept; the "Every N" field can be cleared and retyped; earlier price periods can use
  a custom cycle; rotating during "Update past payments?" no longer gets stuck.
- **Notifications**: Budgey asks once when you add your first subscription, says when notifications
  are blocked, and a tap after "Don't allow" opens system settings.
- **Purchases**: bulk actions only touch the purchases you can see; "Clear all" keeps the category the
  list was opened for; custom date ranges read "from Mar 3 to Apr 2"; typing in search stays smooth.
- **Backups**: all settings are included; damaged rows are skipped and counted; "Undo erase" restores
  exactly; the safety copy can be deleted early and never goes to a new phone.
- **Everywhere**: deleted items open as "This purchase is gone" instead of a blank screen; a negative
  trend chart has a zero line; readable budget and refund colours; date fields, colour swatches and
  switch rows work with TalkBack; dialogs and sheets keep what you typed when the phone rotates; no
  white flash for dark-theme users; the selected tab stays right and re-tapping it goes to its top.

## [0.1.0] — 2026-10-04

First public release.

- **Purchases**: overview chart (donut, pie, bars, trend, rings) with drill-down and touch-and-hold previews,
  automatic merchant logos, read-only previews, ⟲ Resume for half-finished adds, search, filters, bulk edit
  with undo.
- **Scanning**: on-device receipt / screenshot / pump-screen reading with ML Kit and Budgey's parser;
  optional on-device AI (Gemini Nano, Vision AI); View text, Rescan and Advanced Rescan; $0 totals for
  fully discounted receipts.
- **Budgets & categories**: nested folders with weekly / monthly / quarterly / yearly budgets that roll up.
- **Subscriptions**: totals, upcoming renewals, reminders, automatic payment logging, price history,
  pause / cancel / resume, and free trials that bill only after they end (with an ended-trial warning).
- **Calendar**: spending heat map, purchase counts, renewal marks, day lists.
- **Tools**: check splitter (even, custom or by item; multi-unit rows split per unit), tip calculator,
  offline currency converter.
- **Data**: everything stays on the phone; export / import as a `.zip` with receipt photos; safety copy
  before erasing.

[0.1.1]: docs/releases/v0.1.1.md
[0.1.0]: docs/releases/v0.1.0.md
