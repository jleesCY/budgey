# Changelog

All notable changes to Budgey. Versions follow [semantic versioning](https://semver.org); the 0.x series
is early and may still change how things work. Full release notes for each version live in
[docs/releases/](docs/releases/).

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

[0.1.0]: docs/releases/v0.1.0.md
