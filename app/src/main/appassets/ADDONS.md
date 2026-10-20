# AI addons for Technically Here

The base app includes no workplace addon or employer form. Users import their own JSON file through **Settings → Addons → Import addon JSON**. Addons are declarative data, not executable plugins. Do not put personal data or credentials in a shared template; users can complete profile fields in the app.

## Minimal example

```json
{
  "schemaVersion": 1,
  "version": "1.0.0",
  "id": "work-report",
  "name": "Work report",
  "title": "Weekly time report",
  "locale": "en-GB",
  "signature": true,
  "shifts": ["S1", "S2", "S3"],
  "shiftScope": "week",
  "fields": [{"key": "worker", "label": "Name", "default": ""}],
  "weekFields": [],
  "columns": [
    {"key": "date", "label": "Date", "source": "date"},
    {"key": "from", "label": "From", "source": "from"},
    {"key": "to", "label": "To", "source": "to"},
    {"key": "break", "label": "Break (min)", "source": "pause"},
    {"key": "hours", "label": "Hours", "source": "hours"},
    {"key": "shift", "label": "Shift", "source": "shift"}
  ],
  "pdf": {
    "orientation": "portrait",
    "sections": [
      {"type": "text", "text": "Weekly time report", "size": 14},
      {"type": "fields", "keys": ["worker"], "columns": 1},
      {"type": "table", "totalLabel": "Total"},
      {"type": "signature", "label": "Signature", "nameField": "worker"}
    ]
  }
}
```

## Limits and identity

- `schemaVersion`: currently 1.
- `id`: a stable identifier, starting with a letter, followed by letters, digits, `_`, or `-`; up to 64 characters.
- Reimporting the same ID updates its template and preserves completed values, signatures, and day/week data.
- The complete imported file must be no larger than 256,000 bytes.
- Up to 60 profile fields, 20 columns, 12 weekly fields, 20 shifts, and 50 activity definitions.
- Supported report locales: `en-GB`, `en-US`, `sk-SK`, and `de-DE`. English is the app default. A custom report can use its own locale.

## Fields, shifts, and filtering

Profile `fields` use `key`, `label`, and an optional `default`. `readonly: true` hides a field from the editor and makes exports use its default, ignoring any older saved override.

`weekFields` define optional text values for each ISO week. An empty array hides weekly notes.

`signature: true` enables drawing, storing, and clearing a PNG signature in the app. Signature and profile fields are optional unless your own workflow requires them.

`shifts` defines the available labels. `shiftScope: "week"` puts one optional shift for the selected week in **Details and signature**. It is stored as `addon.weeks[ISO-week].shift` and applies to every row in that week. Without this setting, shifts are stored per day. An empty shift remains valid.

Optional `kindIds` filters the report to activity IDs. Without an explicit filter, addons that create activities default to those activities. Otherwise all activities are included. `fixedActivities: true` hides the activity selector and sets its filter to the addon's activities when imported. Deleted historical activities remain reportable.

## Activities and automatic tracking

An optional `activities` array can create activities:

```json
{
  "key": "work",
  "name": "Work",
  "breakMin": 30,
  "round": true,
  "trigger": {
    "source": "wifi_place",
    "match": "Example WiFi",
    "lat": 48.0,
    "lng": 17.0,
    "radiusM": 200,
    "confirmSec": 3600
  }
}
```

Activity IDs are derived from the addon ID and activity key. Reimporting an existing activity preserves its settings. Explicitly reimporting after deletion can recreate it.

Without a trigger, tracking is manual. Supported addon trigger sources are:

- `wifi`: exact, case-sensitive SSID in `match`.
- `place`: coordinates in `lat` and `lng`, with `radiusM` between 20 and 100,000 metres.
- `wifi_place`: Wi-Fi **or** GPS. Either confirmed signal can maintain presence. Departure requires Wi-Fi to be confirmed disconnected and GPS to be confirmed outside the area.

`confirmSec` is the departure delay, from 0 to 86,400 seconds, default 3600. A return cancels departure. When the delay completes, the end is saved at the first detected departure. Unknown or inaccurate location does not confirm departure; an unknown combined state cancels its pending departure timer.

Deleting an activity removes its rules and ends its running entry. If hours exist, historical calculation metadata is retained in `deletedKinds` for reports and backups. The deleted activity is no longer offered for a new clock-in.

## Report columns and time calculations

Column sources: `date`, `week` (ISO week number), `year` (calendar year), `from`, `to`, `pause` (minutes), `hours`, `activity`, `shift`, `note`, and `text`. `note` reads the underlying time entry's note. `text` reads addon-specific daily text keyed by the column key. A text column with `readonly: true` exports only its `default`.

Each row represents a recorded block, split at local midnight. Gaps between blocks never count as work. `24:00` marks the end of a day. One daily break is deducted per activity, progressively from the first blocks, capped by their duration. Overnight entries can therefore receive a break on both calendar days.

Rounding is calculated by the app and never overwrites original timestamps. Running entries are rounded after they end. Closed entries rounded to zero remain visible with their original times and zero hours, without consuming the daily break. Totals use precise durations rather than rounded display strings.

## Flowing PDF sections

`pdf.orientation` is `portrait` or `landscape`. `pdf.sections` supports:

- `text`: fixed `text`, optional `size`.
- `fields`: profile field `keys`, optional `title`, and `columns` of 1 or 2.
- `table`: exactly one section, with an optional `totalLabel`.
- `weekFields`: the weekly text fields.
- `signature`: optional labels such as `label`, `dateLabel`, `otherLabel`, and a valid `nameField`.

The same sections and data feed Markdown exports. A custom template selected from **Hours → Export** is weekly. **Default** switches to the standard monthly report with field and activity selection.

## Fixed-form PDF canvas

Optional `pdf.canvas` defines a fixed form with a JPEG or PNG background and positioned data. Business content and images belong to the external template, never to the app code.

- `width` and `height`: page dimensions in PDF points, 200–2000.
- `background`: `x`, `y`, `width`, `height`, and `image` as a base64 JPEG/PNG data URI. The decoded image must not exceed 4 million pixels.
- `fields` and `weekFields`: boxes with a valid field `key`, `x`, `y`, `width`, `height`, optional `fontSize` (6–30), `align` (`left`, `center`, `right`), and `erase` for covering preprinted values. The same profile key can be placed more than once.
- `table`: `y`, `rowHeight`, and positioned `columns` matching the template's column keys. Column layouts use `x`, `width`, optional `fontSize` and `align`, optional `dateFormat` (`dd.MM` or `dd.MM.yyyy`), and `trimZeros` for hours. `total` is the totals box.
- Optional boxes: `signature`, `exportDate`, and `pageNumber`.

A week with at most one entry per day uses seven calendar slots. When a day has multiple blocks, records are packed chronologically, seven per page. Extra pages are created only after those slots fill. Gaps are never merged into work, entries are not discarded, and the overall total appears only on the final page.

Long text wraps or shrinks to six points. If it still cannot fit, export reports an error rather than silently truncating it. Native Canvas rendering is testable locally; actual Android PDF bytes require the instrumented device test.
