# User guide

## 1. Add an activity

Choose **Add activity** on **Today** or **Activities**. In one form, enter a name, a daily break, and optional 15-minute rounding.

Choose a trigger:

- **Manual**: use **Clock in** and **Clock out**.
- **Wi-Fi**: enter the exact network name, including capitalization, or choose **Use current Wi-Fi**.
- **GPS**: enter coordinates or choose **Use current location**, then set the radius. The default is 200 metres.
- **Wi-Fi or GPS**: either the selected connected network or a confirmed location inside the area can start or maintain tracking.

The default departure delay is 60 minutes. Save the form. Use **Edit activity** to change any setting later.

## 2. Enable background tracking

Open **Settings → Phone automation → Start background tracking**. Under **Reliable tracking**, check precise location, background location, and battery permissions. On Samsung, add the app to **Never sleeping apps** and disable permission removal for unused apps.

You can then close the app normally. The silent system notification belongs to the tracking service. A force stop, disabled location, or system restrictions can interrupt tracking.

Returning within the delay keeps the entry continuous. Once departure is confirmed, the end is saved at the first detected departure; the waiting hour is not added. Missing or inaccurate GPS alone does not confirm departure.

In **Wi-Fi or GPS** mode, a known disconnection from the selected Wi-Fi starts the departure delay when GPS is unavailable. A current GPS fix inside the area or reconnection to Wi-Fi cancels departure. Pending departure times survive service/process restarts and phone sleep. If all signals are unknown, the app waits for a usable observation before confirming departure. Stopping tracking or restoring a backup clears pending departures.

## 3. Enter or correct hours

On **Today**, select an activity and use **Clock in / Clock out**. Under **Hours**, add an entry retrospectively or tap a table cell to edit its time, activity, or note. The table also scrolls horizontally.

Enter local time as `2026-10-20 08:00`, using the phone's time zone. Leave the end empty for a running entry. **End now** inserts the current time. Entries cannot overlap or extend into the future.

Short entries rounded to zero remain visible with their original times. One break is deducted per local calendar day and activity. Changing an activity's break or rounding changes its calculated reports; original timestamps remain stored.

## 4. Export

Choose **Hours → Export**, then PDF or Markdown.

**Default** exports the displayed month. All fields and activities are selected initially; uncheck any you do not need.

If you have imported an addon, its template is offered for weekly export. Select the week. Switch to **Default** for the monthly report. The chosen template is remembered.

For an addon with a weekly shift, open **Details and signature**, select the week, and optionally choose its shift. Available fields and signature options depend on the addon.

## 5. Addons

Use **Settings → Addons → Import addon JSON** to load your own file. **Save AI guide** exports the format instructions for an AI assistant. An addon can create an activity, configure its trigger, and define a custom report. Updating the same addon preserves completed fields.

Neither the app nor this repository contains a preinstalled workplace addon. The small files under `test-fixtures/` contain synthetic data for tests only.

## 6. Delete and back up

Choose **Activities → Delete activity** and confirm. Its rules are removed, its running entry ends, and historical hours remain available in history and exports. Reimporting an addon may recreate its activity.

Save a full backup under **Settings → Backup and restore**. Restoring replaces current data and disables automation. Keep the backup outside the app before uninstalling or clearing its storage.
