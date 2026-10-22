package sk.technicallyhere;

import static org.junit.Assert.*;

import java.time.*;
import java.util.*;
import org.json.*;
import org.junit.Test;

public class CoreTest {
  private final ZoneId zone = ZoneId.of("Europe/Berlin");

  @Test
  public void standardReportUsesEnglishLabelsAndDecimalPoint() {
    JSONObject template = Core.standardTemplate();
    assertEquals("en-GB", template.optString("locale"));
    assertEquals("Time report", template.optString("title"));
    assertEquals("Date", J.array(template, "columns").optJSONObject(0).optString("label"));
    assertEquals(
        "1.50", Core.decimal(90 * 60_000L, Locale.forLanguageTag(template.optString("locale"))));
  }

  @Test
  public void deletingActivityPreservesHistoryCalculationsAndBackup() {
    JSONObject state = withKinds();
    J.put(Core.kind(state, "kind-work"), "breakMin", 45);
    J.put(Core.kind(state, "kind-work"), "round", true);
    J.array(state, "entries").put(entry("past", "08:02", "17:02"));
    J.array(state, "rules")
        .put(rule("wifi", "wifi", "start", "Office"))
        .put(rule("stop", "wifi", "stop", "Home"));
    JSONObject other = rule("other", "wifi", "start", "Other");
    J.put(other, "kindId", "kind-other");
    J.array(state, "rules").put(other);
    JSONObject report = J.obj("template", Core.standardTemplate());
    LocalDate day = LocalDate.of(2026, 10, 19);
    long before = Core.rows(state, report, day, 1, at("20:00"), zone).get(0).net;
    String entries = J.array(state, "entries").toString();
    Core.deleteActivity(state, "kind-work", at("20:00"));
    Core.validate(state, at("20:00"));
    assertNull(J.find(J.array(state, "kinds"), "kind-work"));
    assertEquals(entries, J.array(state, "entries").toString());
    assertEquals(before, Core.rows(state, report, day, 1, at("20:00"), zone).get(0).net);
    assertEquals("Work", Core.kind(state, "kind-work").optString("name"));
    assertEquals(1, J.array(state, "rules").length());
    assertEquals("other", J.array(state, "rules").optJSONObject(0).optString("id"));
    assertEquals(2, Core.reportKinds(state).size());
    JSONObject restored =
        Core.fromBackup(
            J.obj("format", "technically-here-backup", "version", 1, "data", state), at("20:00"));
    assertNull(J.find(J.array(restored, "kinds"), "kind-work"));
    assertEquals(before, Core.rows(restored, report, day, 1, at("20:00"), zone).get(0).net);
  }

  @Test
  public void deletingRunningActivityStopsItAndUnusedActivityLeavesNoHistoricalKind() {
    JSONObject state = withKinds();
    Core.clockIn(state, "kind-work", "manual", "Manual", "manual", at("08:00"));
    Core.deleteActivity(state, "kind-work", at("12:00"));
    Core.validate(state, at("20:00"));
    assertNull(Core.open(state));
    assertEquals(at("12:00"), J.array(state, "entries").optJSONObject(0).optLong("end"));
    assertTrue(state.optBoolean("hold"));
    assertEquals(1, J.array(state, "deletedKinds").length());
    Core.deleteActivity(state, "kind-other", at("13:00"));
    assertEquals(0, J.array(state, "kinds").length());
    assertEquals(1, J.array(state, "deletedKinds").length());
    try {
      Core.clockIn(state, "kind-work", "manual", "Manual", "manual", at("14:00"));
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void unifiedSavePreservesUnchangedTriggerAndActiveOwner() {
    JSONObject state = withKinds();
    JSONObject trigger = rule("wifi", "wifi", "start", "Office");
    J.array(state, "rules").put(trigger);
    Core.clockIn(state, "kind-work", "wifi", "Wifi", "auto", at("08:00"));
    Core.saveActivity(state, "kind-work", "New name", 30, true, J.copy(trigger));
    assertEquals("wifi", Core.activityTriggers(state, "kind-work").get(0).optString("id"));
    assertEquals("wifi", Core.open(state).optString("ruleId"));
    assertEquals(30, Core.kind(state, "kind-work").optInt("breakMin"));
    assertEquals("New name", Core.open(state).optString("kindName"));
    Core.saveActivity(state, "kind-work", "New name", 30, true, null);
    assertEquals("manual", Core.open(state).optString("ruleId"));
    assertTrue(Core.activityTriggers(state, "kind-work").isEmpty());
    Core.validate(state, at("20:00"));
  }

  @Test
  public void reinstallingAddonCanRecreateDeletedActivityWithoutDuplicateMetadata() {
    JSONObject state = Core.defaults(), t = template();
    J.put(t, "activities", J.arr(Arrays.asList(J.obj("key", "work", "name", "Work"))));
    Core.install(state, t);
    String id = J.array(state, "kinds").optJSONObject(0).optString("id");
    Core.clockIn(state, id, "manual", "Manual", "manual", at("08:00"));
    Core.deleteActivity(state, id, at("09:00"));
    Core.install(state, t);
    Core.validate(state, at("20:00"));
    assertNotNull(J.find(J.array(state, "kinds"), id));
    assertEquals(0, J.array(state, "deletedKinds").length());
    assertEquals(1, J.array(state, "entries").length());
  }

  @Test
  public void weeklyShiftOverridesDailyValuesAndStaysWithinItsWeek() {
    JSONObject state = withKinds();
    LocalDate monday = LocalDate.of(2026, 10, 19);
    for (int i = 0; i < 9; i++) {
      long start = monday.plusDays(i).atTime(8, 0).atZone(zone).toInstant().toEpochMilli();
      J.array(state, "entries")
          .put(
              J.obj(
                  "id",
                  "week-" + i,
                  "kindId",
                  "kind-work",
                  "start",
                  start,
                  "end",
                  start + 3_600_000L));
    }
    JSONObject addon =
        J.obj(
            "template",
            J.obj("shiftScope", "week", "locale", "de-DE"),
            "weeks",
            J.obj(
                Core.weekKey(monday),
                J.obj("shift", "S2"),
                Core.weekKey(monday.plusWeeks(1)),
                J.obj("shift", "S1")),
            "days",
            J.obj(monday.toString(), J.obj("shift", "S3")));
    List<Core.Row> first = Core.rows(state, addon, monday, 7, at("20:00"), zone);
    assertEquals(7, first.size());
    for (Core.Row row : first) assertEquals("S2", row.shift);
    List<Core.Row> second = Core.rows(state, addon, monday.plusWeeks(1), 7, at("20:00"), zone);
    assertEquals(2, second.size());
    for (Core.Row row : second) assertEquals("S1", row.shift);
    J.put(J.object(J.object(addon, "weeks"), Core.weekKey(monday)), "shift", "");
    assertEquals("", Core.rows(state, addon, monday, 7, at("20:00"), zone).get(0).shift);
  }

  @Test
  public void roundedZeroEntriesStayVisibleAndDoNotConsumeDailyBreak() {
    JSONObject state = withKinds();
    JSONObject kind = Core.kind(state, "kind-work");
    J.put(kind, "round", true);
    J.put(kind, "breakMin", 15);
    J.array(state, "entries")
        .put(J.obj("id", "short", "kindId", "kind-work", "start", at("08:01"), "end", at("08:02")));
    J.array(state, "entries")
        .put(
            J.obj("id", "normal", "kindId", "kind-work", "start", at("08:10"), "end", at("09:00")));
    List<Core.Row> rows =
        Core.rows(
            state,
            J.obj("template", Core.standardTemplate()),
            LocalDate.of(2026, 10, 19),
            1,
            at("20:00"),
            zone);
    assertEquals(2, rows.size());
    assertEquals("short", rows.get(0).entryId);
    assertEquals("08:01", rows.get(0).from);
    assertEquals(0, rows.get(0).net);
    assertEquals(0, rows.get(0).pause);
    assertEquals(15 * 60_000L, rows.get(1).pause);
    assertEquals(30 * 60_000L, rows.get(1).net);
  }

  @Test
  public void runningRoundedEntryIsVisibleBeforeNextQuarterHour() {
    JSONObject state = withKinds();
    J.put(Core.kind(state, "kind-work"), "round", true);
    J.array(state, "entries")
        .put(J.obj("id", "open", "kindId", "kind-work", "start", at("08:09"), "end", null));
    List<Core.Row> rows =
        Core.rows(
            state,
            J.obj("template", Core.standardTemplate()),
            LocalDate.of(2026, 10, 19),
            1,
            at("08:10"),
            zone);
    assertEquals(1, rows.size());
    assertEquals("08:09", rows.get(0).from);
    assertEquals(60_000L, rows.get(0).net);
  }

  private JSONObject withKinds() {
    JSONObject s = Core.defaults();
    J.array(s, "kinds")
        .put(J.obj("id", "kind-work", "name", "Work", "breakMin", 0, "round", false))
        .put(J.obj("id", "kind-other", "name", "Other", "breakMin", 0, "round", false));
    return s;
  }

  @Test
  public void freshInstallIsEmptyAndAddonCanCreateActivitiesWithoutDuplicates() {
    JSONObject state = Core.defaults();
    Core.validate(state, at("20:00"));
    assertEquals(0, J.array(state, "kinds").length());
    assertThrows(
        IllegalArgumentException.class,
        () -> Core.clockIn(state, "missing", "manual", "", "manual", at("09:00")));
    assertEquals(0, J.array(state, "entries").length());
    JSONObject t = template();
    J.put(
        t,
        "activities",
        J.arr(
            Arrays.asList(
                J.obj(
                    "key",
                    "work",
                    "name",
                    "My work",
                    "trigger",
                    J.obj("source", "wifi", "match", "Office")))));
    Core.install(state, t);
    Core.validate(state, at("20:00"));
    assertEquals(1, J.array(state, "kinds").length());
    assertEquals(3600, J.array(state, "rules").optJSONObject(0).optInt("confirmSec"));
    JSONObject kind = J.array(state, "kinds").optJSONObject(0);
    J.put(kind, "name", "Custom name");
    J.put(J.array(state, "rules").optJSONObject(0), "confirmSec", 120);
    Core.install(state, t);
    assertEquals(1, J.array(state, "kinds").length());
    assertEquals("Custom name", kind.optString("name"));
    assertEquals(120, J.array(state, "rules").optJSONObject(0).optInt("confirmSec"));
    assertEquals(1, J.array(state, "addons").length());
  }

  @Test
  public void invalidAddonActivityCannotPartiallyInstall() {
    JSONObject state = Core.defaults();
    JSONObject t = template();
    J.put(t, "activities", J.arr(Arrays.asList(J.obj("key", "bad", "name", "", "breakMin", -1))));
    assertThrows(IllegalArgumentException.class, () -> Core.install(state, t));
    assertEquals(Core.defaults().toString(), state.toString());
  }

  private long at(String time) {
    return LocalDateTime.parse("2026-10-19T" + time).atZone(zone).toInstant().toEpochMilli();
  }

  private JSONObject entry(String id, String start, String end) {
    return J.obj(
        "id",
        id,
        "ruleId",
        "site",
        "ruleName",
        "Site",
        "kindId",
        "kind-work",
        "kindName",
        "Work",
        "start",
        at(start),
        "end",
        end == null ? null : at(end),
        "how",
        "auto");
  }

  private JSONObject rule(String id, String source, String effect, String match) {
    return J.obj(
        "id",
        id,
        "name",
        id,
        "source",
        source,
        "effect",
        effect,
        "kindId",
        "kind-work",
        "lat",
        48,
        "lng",
        17,
        "radiusM",
        100,
        "confirmSec",
        0,
        "match",
        match);
  }

  @Test
  public void perActivityTriggerPreservesOtherRulesAndTransfersOpenOwner() {
    JSONObject state = withKinds();
    JSONObject other = rule("other", "wifi", "start", "Elsewhere");
    J.put(other, "kindId", "kind-other");
    J.put(
        state,
        "rules",
        J.arr(
            Arrays.asList(
                rule("site", "place", "start", ""),
                other,
                rule("bt", "bluetooth", "start", "Car"),
                rule("stop", "wifi", "stop", "Home"))));
    J.array(state, "entries")
        .put(entry("closed", "07:00", "08:00"))
        .put(entry("open", "09:00", null));
    String closed = J.array(state, "entries").optJSONObject(0).toString();
    Core.configureActivityTrigger(
        state, "kind-work", rule("replacement", "wifi", "start", "Office"));
    assertEquals(4, J.array(state, "rules").length());
    JSONObject trigger = Core.activityTriggers(state, "kind-work").get(0);
    assertNotEquals("site", trigger.optString("id"));
    assertEquals(trigger.optString("id"), Core.open(state).optString("ruleId"));
    assertNotNull(J.find(J.array(state, "rules"), "bt"));
    assertNotNull(J.find(J.array(state, "rules"), "stop"));
    assertNotNull(J.find(J.array(state, "rules"), "other"));
    Core.configureActivityTrigger(state, "kind-work", null);
    assertEquals("manual", Core.open(state).optString("ruleId"));
    assertEquals(closed, J.array(state, "entries").optJSONObject(0).toString());
    assertEquals(3, J.array(state, "rules").length());
  }

  @Test
  public void wifiUsesExactSsidAndUnknownDoesNotEndShift() {
    JSONObject r = rule("wifi", "wifi", "start", "Office");
    Core.Signals signals = new Core.Signals();
    assertNull(Core.live(r, signals, false, at("10:00")));
    signals.wifiKnown = true;
    signals.wifi = "office";
    assertEquals(Boolean.FALSE, Core.live(r, signals, false, at("10:00")));
    signals.wifi = "Office";
    assertEquals(Boolean.TRUE, Core.live(r, signals, false, at("10:00")));
    signals.wifi = "";
    assertEquals(Boolean.FALSE, Core.live(r, signals, false, at("10:00")));
  }

  @Test
  public void invalidActivityTriggerDoesNotRemoveExistingRule() {
    JSONObject state = withKinds();
    J.array(state, "rules").put(rule("site", "place", "start", ""));
    String before = state.toString();
    JSONObject bad = rule("bad", "place", "start", "");
    J.put(bad, "radiusM", -1);
    assertThrows(
        IllegalArgumentException.class,
        () -> Core.configureActivityTrigger(state, "kind-work", bad));
    assertEquals(before, state.toString());
  }

  @Test
  public void wifiLunchGraceMigratesOnceAndPreservesCustomDelays() {
    JSONObject state = withKinds();
    state.remove("wifiLunchGraceVersion");
    JSONObject wifi = rule("wifi", "wifi", "start", "Office");
    J.put(wifi, "confirmSec", 60);
    JSONObject custom = rule("custom", "wifi", "start", "Other");
    J.put(custom, "confirmSec", 120);
    JSONObject gps = rule("gps", "place", "start", "");
    J.put(gps, "confirmSec", 60);
    J.put(state, "rules", J.arr(Arrays.asList(wifi, custom, gps)));
    assertTrue(Core.upgradeWifiLunchGrace(state));
    assertEquals(3600, wifi.optInt("confirmSec"));
    assertEquals(120, custom.optInt("confirmSec"));
    assertEquals(60, gps.optInt("confirmSec"));
    J.put(wifi, "confirmSec", 60);
    assertFalse(Core.upgradeWifiLunchGrace(state));
    assertEquals(60, wifi.optInt("confirmSec"));
  }

  @Test
  public void lunchWithinHourKeepsOneContinuousWifiEntry() {
    JSONObject state = withKinds();
    JSONObject wifi = rule("wifi", "wifi", "start", "Office");
    J.put(wifi, "confirmSec", 3600);
    J.array(state, "rules").put(wifi);
    Core.Signals signals = new Core.Signals();
    signals.wifiKnown = true;
    signals.wifi = "Office";
    Map<String, Long> pending = new HashMap<>();
    Core.evaluate(state, signals, pending, at("09:00"));
    signals.wifi = "";
    Core.evaluate(state, signals, pending, at("12:00"));
    Core.evaluate(state, signals, pending, at("12:59"));
    assertNotNull(Core.open(state));
    signals.wifi = "Office";
    Core.evaluate(state, signals, pending, at("12:59"));
    assertTrue(pending.isEmpty());
    assertEquals(1, J.array(state, "entries").length());
    signals.wifi = "";
    Core.evaluate(state, signals, pending, at("16:00"));
    Core.evaluate(state, signals, pending, at("17:00"));
    assertNull(Core.open(state));
    assertEquals(at("16:00"), J.array(state, "entries").optJSONObject(0).optLong("end"));
  }

  @Test
  public void markdownExportsTableTotalsAndEscapesUserText() {
    JSONObject state = withKinds();
    J.array(state, "entries").put(entry("one", "08:00", "12:00"));
    JSONObject t = template();
    J.put(
        t,
        "columns",
        J.arr(
            Arrays.asList(
                J.obj("key", "activity", "label", "Activity", "source", "activity"),
                J.obj("key", "hours", "label", "Hours", "source", "hours"))));
    J.put(t, "weekFields", J.arr(Arrays.asList(J.obj("key", "note", "label", "Note"))));
    JSONObject addon =
        J.obj(
            "template",
            t,
            "values",
            J.obj("project", "A|B\n<script>"),
            "weeks",
            J.obj(Core.weekKey(LocalDate.of(2026, 10, 19)), J.obj("note", "Done")));
    List<Core.Row> rows = Core.rows(state, addon, LocalDate.of(2026, 10, 19), 1, at("20:00"), zone);
    String md = ReportMarkdown.write(addon, rows, LocalDate.of(2026, 10, 19));
    assertTrue(md.contains("A\\|B<br>&lt;script&gt;"));
    assertFalse(md.contains("<script>"));
    assertTrue(md.contains("| Activity | Hours |"));
    assertTrue(md.contains("| Total | 4.00 |"));
    assertTrue(md.contains("Done"));
  }

  @Test
  public void combinedWifiGpsUsesThreeValuedOr() {
    JSONObject r = rule("combined", "wifi_place", "start", "Office");
    Boolean[] states = {Boolean.TRUE, Boolean.FALSE, null};
    for (Boolean wifi : states)
      for (Boolean gps : states) {
        Core.Signals v = signals(at("10:00"));
        v.wifiKnown = wifi != null;
        v.wifi = Boolean.TRUE.equals(wifi) ? "Office" : "";
        v.locationValid = gps != null;
        v.lat = Boolean.TRUE.equals(gps) ? 48 : 49;
        Boolean expected =
            Boolean.TRUE.equals(wifi) || Boolean.TRUE.equals(gps)
                ? Boolean.TRUE
                : Boolean.FALSE.equals(wifi) && Boolean.FALSE.equals(gps) ? Boolean.FALSE : null;
        assertEquals("wifi=" + wifi + " gps=" + gps, expected, Core.live(r, v, false, at("10:00")));
      }
  }

  @Test
  public void combinedTriggerKeepsSingleShiftAndWaitsForBothSignalsToLeave() {
    JSONObject state = withKinds();
    JSONObject r = rule("combined", "wifi_place", "start", "Office");
    J.put(r, "confirmSec", 3600);
    Core.configureActivityTrigger(state, "kind-work", r);
    Core.Signals v = signals(at("09:00"));
    v.wifi = "";
    Map<String, Long> pending = new HashMap<>();
    Core.evaluate(state, v, pending, at("09:00"));
    assertNotNull(Core.open(state));
    v.wifi = "Office";
    v.lat = 49;
    v.fixAt = at("10:00");
    Core.evaluate(state, v, pending, at("10:00"));
    assertEquals(1, J.array(state, "entries").length());
    assertTrue(pending.isEmpty());
    v.wifi = "";
    v.lat = 48;
    v.fixAt = at("11:00");
    Core.evaluate(state, v, pending, at("11:00"));
    assertNotNull(Core.open(state));
    assertTrue(pending.isEmpty());
    v.lat = 49;
    v.fixAt = at("12:00");
    Core.evaluate(state, v, pending, at("12:00"));
    v.fixAt = at("12:59");
    Core.evaluate(state, v, pending, at("12:59"));
    assertNotNull(Core.open(state));
    v.wifi = "Office";
    Core.evaluate(state, v, pending, at("12:59"));
    assertTrue(pending.isEmpty());
    v.wifi = "";
    v.fixAt = at("16:00");
    Core.evaluate(state, v, pending, at("16:00"));
    v.fixAt = at("17:00");
    Core.evaluate(state, v, pending, at("17:00"));
    assertNull(Core.open(state));
    assertEquals(1, J.array(state, "entries").length());
    assertEquals(at("16:00"), J.array(state, "entries").optJSONObject(0).optLong("end"));
  }

  @Test
  public void missingGpsDoesNotResetObservedWifiDepartureOfCombinedTrigger() {
    JSONObject state = withKinds();
    JSONObject r = rule("combined", "wifi_place", "start", "Office");
    J.put(r, "confirmSec", 60);
    Core.configureActivityTrigger(state, "kind-work", r);
    Core.Signals v = signals(at("09:00"));
    Map<String, Long> pending = new HashMap<>();
    Core.evaluate(state, v, pending, at("09:00"));
    v.wifi = "";
    v.lat = 49;
    v.fixAt = at("12:00");
    Core.evaluate(state, v, pending, at("12:00"));
    v.locationValid = false;
    Core.evaluate(state, v, pending, at("12:01"));
    assertNull(Core.open(state));
    assertEquals(at("12:00"), J.array(state, "entries").optJSONObject(0).optLong("end"));
  }

  @Test
  public void addonCanInstallCombinedTrigger() {
    JSONObject t = template();
    J.put(
        t,
        "activities",
        J.arr(
            Arrays.asList(
                J.obj(
                    "key",
                    "work",
                    "name",
                    "Work",
                    "trigger",
                    J.obj(
                        "source",
                        "wifi_place",
                        "match",
                        "Office",
                        "lat",
                        48,
                        "lng",
                        17,
                        "radiusM",
                        150)))));
    JSONObject state = Core.defaults();
    Core.install(state, t);
    Core.validate(state, at("20:00"));
    JSONObject r = J.array(state, "rules").optJSONObject(0);
    assertEquals("wifi_place", r.optString("source"));
    assertEquals(3600, r.optInt("confirmSec"));
    J.put(J.object(J.array(t, "activities").optJSONObject(0), "trigger"), "match", "");
    assertThrows(IllegalArgumentException.class, () -> Core.validateTemplate(t));
  }

  @Test
  public void historyRowsKeepEditableEntryIdentityAndNotesAcrossMidnight() {
    JSONObject state = withKinds();
    JSONObject e = entry("night", "22:00", null);
    J.put(e, "end", at("23:00") + 3 * 3600000L);
    J.put(e, "note", "Job A|B");
    J.array(state, "entries").put(e);
    List<Core.Row> rows =
        Core.rows(
            state,
            J.obj("template", Core.standardTemplate()),
            LocalDate.of(2026, 10, 19),
            2,
            at("23:00") + 4 * 3600000L,
            zone);
    assertEquals(2, rows.size());
    for (Core.Row row : rows) {
      assertEquals("night", row.entryId);
      assertEquals("Job A|B", row.cell(J.obj("source", "note"), Locale.ROOT));
      assertEquals("2026", row.year);
    }
    assertEquals(4 * 3600000L, rows.stream().mapToLong(r -> r.net).sum());
  }

  @Test
  public void selectedExportOmitsUncheckedFieldsAndRespectsActivityFilter() {
    JSONObject state = withKinds();
    JSONObject a = entry("work", "08:00", "10:00"), b = entry("other", "11:00", "12:00");
    J.put(b, "kindId", "kind-other");
    J.put(a, "note", "Hidden note");
    J.array(state, "entries").put(a).put(b);
    JSONObject template =
        Core.selectedStandardTemplate(new HashSet<>(Arrays.asList("activity", "hours")));
    Core.validateTemplate(template);
    JSONObject addon = J.obj("template", template, "kindIds", J.arr(Arrays.asList("kind-work")));
    List<Core.Row> rows = Core.rows(state, addon, LocalDate.of(2026, 10, 19), 1, at("20:00"), zone);
    String md = ReportMarkdown.write(addon, rows, LocalDate.of(2026, 10, 19));
    assertEquals(1, rows.size());
    assertTrue(md.contains("2.00"));
    assertFalse(md.contains("Hidden note"));
    assertFalse(md.contains("Note"));
    assertFalse(md.contains("08:00"));
    assertThrows(
        IllegalArgumentException.class,
        () -> Core.selectedStandardTemplate(Collections.emptySet()));
  }

  @Test
  public void exampleAddonCreatesConfiguredActivityAndAllowsEmptyShiftAndSignature()
      throws Exception {
    java.nio.file.Path file = java.nio.file.Paths.get("../test-fixtures/form-template.json");
    if (!java.nio.file.Files.exists(file))
      file = java.nio.file.Paths.get("test-fixtures/form-template.json");
    JSONObject template =
        J.parse(
            new String(
                java.nio.file.Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8));
    JSONObject state = Core.defaults();
    Core.install(state, template);
    Core.validate(state, at("20:00"));
    JSONObject kind = J.array(state, "kinds").optJSONObject(0),
        trigger = J.array(state, "rules").optJSONObject(0);
    assertEquals("Example", kind.optString("name"));
    assertEquals(45, kind.optInt("breakMin"));
    assertTrue(kind.optBoolean("round"));
    assertEquals("wifi_place", trigger.optString("source"));
    assertEquals("Example WiFi", trigger.optString("match"));
    assertEquals(48.0, trigger.optDouble("lat"), .00000001);
    assertEquals(17.0, trigger.optDouble("lng"), .00000001);
    assertEquals(500, trigger.optInt("radiusM"));
    assertEquals(3600, trigger.optInt("confirmSec"));
    JSONObject addon = J.array(state, "addons").optJSONObject(0);
    assertEquals("", addon.optString("signature"));
    J.array(state, "entries")
        .put(
            J.obj(
                "id",
                "example-entry",
                "kindId",
                kind.optString("id"),
                "start",
                at("08:00"),
                "end",
                at("17:00")));
    List<Core.Row> rows = Core.rows(state, addon, LocalDate.of(2026, 10, 19), 7, at("20:00"), zone);
    assertEquals("", rows.get(0).shift);
    assertEquals(7, FormLayout.pages(rows, LocalDate.of(2026, 10, 19)).get(0).size());
    Core.install(state, template);
    assertEquals(1, J.array(state, "kinds").length());
  }

  @Test
  public void fixedWeeklyFormKeepsSeparatedBlocksTogetherWithoutCountingGap() {
    JSONObject state = withKinds();
    J.put(J.array(state, "kinds").optJSONObject(0), "breakMin", 45);
    J.array(state, "entries").put(entry("am", "08:00", "12:00")).put(entry("pm", "13:00", "17:00"));
    List<Core.Row> rows =
        Core.rows(
            state,
            J.obj("template", Core.standardTemplate()),
            LocalDate.of(2026, 10, 19),
            7,
            at("20:00"),
            zone);
    List<List<Core.Row>> pages = FormLayout.pages(rows, LocalDate.of(2026, 10, 19));
    assertEquals(1, pages.size());
    assertEquals("am", pages.get(0).get(0).entryId);
    assertEquals("pm", pages.get(0).get(1).entryId);
    assertNull(pages.get(0).get(2));
    assertEquals(26100000L, rows.stream().mapToLong(r -> r.net).sum());
  }

  @Test
  public void fixedWeeklyFormPacksSevenRecordsPerPageWithoutLosingAny() {
    LocalDate monday = LocalDate.of(2026, 10, 19);
    List<Core.Row> rows = new ArrayList<>();
    for (int i = 14; i >= 0; i--) {
      Core.Row row = new Core.Row();
      row.entryId = "record-" + i;
      row.dayKey = monday.plusDays(6).toString();
      row.from = String.format(Locale.ROOT, "%02d:00", i);
      row.net = 60_000;
      rows.add(row);
    }
    List<List<Core.Row>> pages = FormLayout.pages(rows, monday);
    assertEquals(3, pages.size());
    Set<String> ids = new HashSet<>();
    long total = 0;
    for (List<Core.Row> page : pages) {
      assertEquals(7, page.size());
      for (Core.Row row : page)
        if (row != null) {
          assertTrue(ids.add(row.entryId));
          total += row.net;
        }
    }
    assertEquals(15, ids.size());
    assertEquals(15 * 60_000L, total);
    assertEquals("record-0", pages.get(0).get(0).entryId);
    assertEquals("record-7", pages.get(1).get(0).entryId);
    assertEquals("record-14", pages.get(2).get(0).entryId);
    assertNull(pages.get(2).get(1));
  }

  @Test
  public void fixedWeeklyFormPreservesCalendarForOneRecordPerDay() {
    LocalDate monday = LocalDate.of(2026, 10, 19);
    List<Core.Row> rows = new ArrayList<>();
    for (int day : new int[] {1, 4, 6}) {
      Core.Row row = new Core.Row();
      row.dayKey = monday.plusDays(day).toString();
      row.entryId = "day-" + day;
      rows.add(row);
    }
    List<List<Core.Row>> pages = FormLayout.pages(rows, monday);
    assertEquals(1, pages.size());
    assertNull(pages.get(0).get(0));
    assertEquals("day-1", pages.get(0).get(1).entryId);
    assertEquals("day-6", pages.get(0).get(6).entryId);
    assertEquals(1, FormLayout.pages(Collections.emptyList(), monday).size());
  }

  private JSONObject template() {
    return J.obj(
        "schemaVersion",
        1,
        "id",
        "custom",
        "name",
        "Custom",
        "locale",
        "en-GB",
        "fields",
        J.arr(java.util.Arrays.asList(J.obj("key", "project", "label", "Project"))),
        "shifts",
        new JSONArray(),
        "columns",
        J.arr(
            java.util.Arrays.asList(
                J.obj("key", "day", "label", "Date", "source", "date"),
                J.obj("key", "net", "label", "Hours", "source", "hours"))));
  }

  private Core.Signals signals(long now) {
    Core.Signals s = new Core.Signals();
    s.lat = 48;
    s.lng = 17;
    s.accuracy = 5;
    s.fixAt = now;
    s.locationValid = true;
    s.wifiKnown = true;
    s.wifi = "Office";
    s.btKnown = true;
    return s;
  }

  @Test
  public void splitShiftsDoNotCountTheGap() {
    JSONObject state = withKinds();
    J.put(J.find(J.array(state, "kinds"), "kind-work"), "breakMin", 30);
    J.put(
        state,
        "entries",
        J.arr(
            java.util.Arrays.asList(
                entry("one", "08:00", "12:00"), entry("two", "14:00", "18:00"))));
    List<Core.Row> rows =
        Core.rows(
            state, J.obj("template", template()), LocalDate.of(2026, 10, 19), 1, at("20:00"), zone);
    assertEquals(2, rows.size());
    assertEquals((long) (7.5 * 3600000), rows.stream().mapToLong(r -> r.net).sum());
    assertEquals(1800000, rows.stream().mapToLong(r -> r.pause).sum());
  }

  @Test
  public void simultaneousStopBlocksRestart() {
    JSONObject state = withKinds();
    J.put(state, "entries", J.arr(java.util.Arrays.asList(entry("one", "08:00", null))));
    J.put(state, "appMode", "Stop");
    J.put(
        state,
        "rules",
        J.arr(
            java.util.Arrays.asList(
                rule("site", "wifi", "start", "Office"), rule("stop", "mode", "stop", "Stop"))));
    Map<String, Long> pending = new HashMap<>();
    for (int i = 0; i < 10; i++)
      Core.evaluate(state, signals(at("18:00")), pending, at("18:00") + i * 1000);
    assertNull(Core.open(state));
    assertEquals(1, J.array(state, "entries").length());
  }

  @Test
  public void missingGpsDoesNotStop() {
    JSONObject state = withKinds();
    J.put(state, "entries", J.arr(java.util.Arrays.asList(entry("one", "08:00", null))));
    J.put(state, "rules", J.arr(java.util.Arrays.asList(rule("site", "place", "start", ""))));
    Core.Signals s = signals(at("18:00"));
    s.locationValid = false;
    Core.evaluate(state, s, new HashMap<>(), at("18:00"));
    assertNotNull(Core.open(state));
  }

  @Test
  public void staleAndInaccurateGpsAreUnknown() {
    JSONObject rule = rule("site", "place", "start", "");
    Core.Signals s = signals(at("18:00"));
    assertNull(Core.live(rule, s, false, at("18:02")));
    s.accuracy = 500;
    assertNull(Core.live(rule, s, false, at("18:00")));
  }

  @Test
  public void graceBackdatesConfirmedDeparture() {
    JSONObject state = withKinds();
    J.put(state, "entries", J.arr(java.util.Arrays.asList(entry("one", "08:00", null))));
    JSONObject r = rule("site", "wifi", "start", "Office");
    J.put(r, "confirmSec", 60);
    J.put(state, "rules", J.arr(java.util.Arrays.asList(r)));
    Core.Signals s = signals(at("18:00"));
    s.wifi = "";
    Map<String, Long> pending = new HashMap<>();
    Core.evaluate(state, s, pending, at("18:00"));
    assertNotNull(Core.open(state));
    Core.evaluate(state, s, pending, at("18:01"));
    assertNull(Core.open(state));
    assertEquals(at("18:00"), J.array(state, "entries").optJSONObject(0).optLong("end"));
  }

  @Test
  public void reconnectCancelsDeparture() {
    JSONObject state = withKinds();
    J.put(state, "entries", J.arr(java.util.Arrays.asList(entry("one", "08:00", null))));
    JSONObject r = rule("site", "wifi", "start", "Office");
    J.put(r, "confirmSec", 60);
    J.put(state, "rules", J.arr(java.util.Arrays.asList(r)));
    Core.Signals s = signals(at("18:00"));
    s.wifi = "";
    Map<String, Long> pending = new HashMap<>();
    Core.evaluate(state, s, pending, at("18:00"));
    s.wifi = "Office";
    Core.evaluate(state, s, pending, at("18:01"));
    assertNotNull(Core.open(state));
    assertTrue(pending.isEmpty());
  }

  @Test
  public void manualHoldBlocksEveryStart() {
    JSONObject state = withKinds();
    J.put(state, "hold", true);
    J.put(state, "rules", J.arr(java.util.Arrays.asList(rule("site", "wifi", "start", "Office"))));
    Core.Signals s = signals(at("18:00"));
    Core.evaluate(state, s, new HashMap<>(), at("18:00"));
    assertNull(Core.open(state));
    s.wifi = "";
    Core.evaluate(state, s, new HashMap<>(), at("18:00"));
    assertFalse(state.optBoolean("hold"));
  }

  @Test
  public void liveSiblingPreservesOpenShift() {
    JSONObject state = withKinds();
    J.put(state, "entries", J.arr(java.util.Arrays.asList(entry("one", "08:00", null))));
    J.put(
        state,
        "rules",
        J.arr(
            java.util.Arrays.asList(
                rule("site", "place", "start", ""), rule("wifi", "wifi", "start", "Office"))));
    Core.Signals s = signals(at("18:00"));
    s.lat = 49;
    Map<String, Long> pending = new HashMap<>();
    pending.put("site", at("17:00"));
    Core.evaluate(state, s, pending, at("18:00"));
    assertNotNull(Core.open(state));
    assertEquals("wifi", Core.open(state).optString("ruleId"));
  }

  @Test
  public void unknownWifiDoesNotMeanDisconnect() {
    Core.Signals s = signals(at("18:00"));
    s.wifiKnown = false;
    assertNull(Core.live(rule("site", "wifi", "start", "Office"), s, false, at("18:00")));
  }

  @Test
  public void bluetoothMatchesMacOrName() {
    Core.Signals s = signals(at("18:00"));
    s.bluetooth.add("AA:BB:CC:DD:EE:FF");
    assertEquals(
        Boolean.TRUE,
        Core.live(rule("site", "bluetooth", "start", "aa:bb:cc:dd:ee:ff"), s, false, at("18:00")));
    s.btKnown = false;
    assertNull(Core.live(rule("site", "bluetooth", "start", "Radio"), s, false, at("18:00")));
  }

  @Test
  public void rejectsOverlapsAndMultipleOpenEntries() {
    assertNotNull(
        Core.entryProblem(
            J.arr(
                java.util.Arrays.asList(
                    entry("one", "08:00", "15:00"), entry("two", "14:00", "18:00"))),
            at("20:00")));
    assertNotNull(
        Core.entryProblem(
            J.arr(
                java.util.Arrays.asList(entry("one", "08:00", null), entry("two", "14:00", null))),
            at("20:00")));
  }

  @Test
  public void rejectsNegativeOrFutureTimes() {
    assertNotNull(
        Core.entryProblem(
            J.arr(java.util.Arrays.asList(entry("one", "08:00", "07:00"))), at("20:00")));
    assertNotNull(
        Core.entryProblem(
            J.arr(java.util.Arrays.asList(entry("one", "08:00", "21:00"))), at("20:00")));
  }

  @Test
  public void dstBoundsUseCalendarDays() {
    for (LocalDate day :
        java.util.Arrays.asList(LocalDate.of(2026, 3, 29), LocalDate.of(2026, 10, 25))) {
      JSONObject state = withKinds();
      JSONObject e = entry("one", "08:00", "09:00");
      J.put(e, "start", day.atTime(23, 0).atZone(zone).toInstant().toEpochMilli());
      J.put(e, "end", day.atTime(23, 30).atZone(zone).toInstant().toEpochMilli());
      J.put(state, "entries", J.arr(java.util.Arrays.asList(e)));
      assertEquals(
          1800000,
          Core.rows(state, J.obj("template", template()), day, 1, Long.MAX_VALUE / 2, zone)
              .get(0)
              .net);
    }
  }

  @Test
  public void overnightSplitsAtLocalMidnight() {
    JSONObject state = withKinds();
    JSONObject e = entry("one", "22:00", null);
    J.put(
        e, "end", LocalDateTime.parse("2026-10-20T06:00").atZone(zone).toInstant().toEpochMilli());
    J.put(state, "entries", J.arr(java.util.Arrays.asList(e)));
    List<Core.Row> rows =
        Core.rows(
            state,
            J.obj("template", template()),
            LocalDate.of(2026, 10, 19),
            2,
            at("20:00") + 86400000,
            zone);
    assertEquals(2, rows.size());
    assertEquals("24:00", rows.get(0).to);
    assertEquals("00:00", rows.get(1).from);
    assertEquals(8 * 3600000, rows.stream().mapToLong(r -> r.net).sum());
  }

  @Test
  public void isoWeekYearIsNotCalendarYear() {
    assertEquals("2026-W01", Core.weekKey(LocalDate.of(2025, 12, 29)));
    assertEquals("2020-W53", Core.weekKey(LocalDate.of(2021, 1, 1)));
  }

  @Test
  public void addonUpgradePreservesValuesAndIdentity() {
    JSONObject state = withKinds();
    JSONObject template = template();
    Core.install(state, template);
    JSONObject installed = J.array(state, "addons").optJSONObject(0);
    String id = installed.optString("id");
    J.put(J.object(installed, "values"), "project", "My project");
    J.put(template, "version", "2.0.0");
    Core.install(state, template);
    assertEquals(1, J.array(state, "addons").length());
    installed = J.array(state, "addons").optJSONObject(0);
    assertEquals(id, installed.optString("id"));
    assertEquals("My project", J.object(installed, "values").optString("project"));
  }

  @Test
  public void addonFiltersActivities() {
    JSONObject state = withKinds();
    J.put(state, "entries", J.arr(java.util.Arrays.asList(entry("one", "08:00", "12:00"))));
    JSONObject addon = J.obj("template", template(), "kindIds", new JSONArray());
    assertTrue(Core.rows(state, addon, LocalDate.of(2026, 10, 19), 1, at("20:00"), zone).isEmpty());
  }

  @Test
  public void rejectsUnsupportedAddonVersionAndDuplicateKeys() {
    JSONObject t = template();
    J.put(t, "schemaVersion", 5);
    assertThrows(IllegalArgumentException.class, () -> Core.validateTemplate(t));
    J.put(t, "schemaVersion", 1);
    J.array(t, "columns").put(J.array(t, "columns").optJSONObject(0));
    assertThrows(IllegalArgumentException.class, () -> Core.validateTemplate(t));
  }

  @Test
  public void backupRestoreDisablesAutomationAndPreservesData() {
    JSONObject state = withKinds();
    J.put(state, "entries", J.arr(java.util.Arrays.asList(entry("one", "08:00", "12:00"))));
    Core.install(state, template());
    JSONObject restored =
        Core.fromBackup(
            J.obj("format", "technically-here-backup", "version", 1, "data", state), at("20:00"));
    assertFalse(restored.optBoolean("auto"));
    assertFalse(restored.optBoolean("monitorWanted"));
    assertEquals(J.array(state, "entries").toString(), J.array(restored, "entries").toString());
    assertEquals(J.array(state, "addons").toString(), J.array(restored, "addons").toString());
  }

  @Test
  public void arrivalOriginSurvivesManualDeparture() {
    JSONObject state = withKinds();
    J.put(state, "entries", J.arr(java.util.Arrays.asList(entry("one", "08:00", null))));
    Core.clockOut(state, at("18:00"));
    assertEquals("auto", J.array(state, "entries").optJSONObject(0).optString("how"));
  }
}
