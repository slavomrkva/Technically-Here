package sk.technicallyhere;

import static org.junit.Assert.*;

import java.time.*;
import java.util.*;
import org.json.*;
import org.junit.Test;

/** Real automation and report calculations, including serialized process restarts. */
public class DepartureTest {
  private final ZoneId zone = ZoneId.of("Europe/Berlin");

  private long at(String time) {
    return LocalDateTime.parse("2026-10-21T" + time).atZone(zone).toInstant().toEpochMilli();
  }

  private JSONObject state(String source) {
    JSONObject s = Core.defaults();
    J.array(s, "kinds").put(J.obj("id", "work", "name", "Work", "breakMin", 45, "round", true));
    J.array(s, "rules")
        .put(
            J.obj(
                "id",
                "site",
                "name",
                "Site",
                "kindId",
                "work",
                "source",
                source,
                "effect",
                "start",
                "match",
                "Office",
                "lat",
                48,
                "lng",
                17,
                "radiusM",
                500,
                "confirmSec",
                3600));
    return s;
  }

  private Core.Signals wifi(String name) {
    Core.Signals v = new Core.Signals();
    v.wifiKnown = true;
    v.wifi = name;
    return v;
  }

  private void evaluate(JSONObject state, Core.Signals v, String time) {
    // A fresh map on every call models lost service memory; state must own the countdown.
    Core.evaluate(state, v, new HashMap<>(), at(time));
  }

  @Test
  public void lunchThenDepartureSurvivesSleepAndProcessRestartAndReportsOneShift() {
    JSONObject s = state("wifi_place");
    Core.Signals v = wifi("Office");
    evaluate(s, v, "05:49:42");
    v.wifi = "";
    evaluate(s, v, "11:30:00");
    s = J.copy(s);
    v.wifi = "Office";
    evaluate(s, v, "11:50:00");
    assertEquals(1, J.array(s, "entries").length());
    assertNotNull(Core.open(s));
    v.wifi = "";
    evaluate(s, v, "14:00:00");
    s = J.copy(s);
    evaluate(s, v, "14:20:00");
    s = J.copy(s);
    evaluate(s, v, "15:05:00");
    assertNull(Core.open(s));
    assertEquals(at("14:00:00"), J.array(s, "entries").optJSONObject(0).optLong("end"));
    List<Core.Row> rows =
        Core.rows(
            s,
            J.obj("template", Core.standardTemplate()),
            LocalDate.of(2026, 10, 21),
            2,
            at("20:00:00"),
            zone);
    assertEquals(1, rows.size());
    assertEquals("05:45", rows.get(0).from);
    assertEquals("14:00", rows.get(0).to);
    assertEquals(45 * 60_000L, rows.get(0).pause);
    assertEquals(450 * 60_000L, rows.get(0).net);
  }

  @Test
  public void wifiOnlyDepartureSurvivesRepeatedEvaluationGaps() {
    JSONObject s = state("wifi");
    Core.Signals v = wifi("Office");
    evaluate(s, v, "06:00:00");
    v.wifi = "";
    evaluate(s, v, "14:00:00");
    evaluate(s, v, "14:30:00");
    evaluate(s, v, "15:00:00");
    assertNull(Core.open(s));
    assertEquals(at("14:00:00"), J.array(s, "entries").optJSONObject(0).optLong("end"));
  }

  @Test
  public void unknownSensorsPreserveButCannotAloneConfirmDeparture() {
    JSONObject s = state("wifi_place");
    Core.Signals v = wifi("Office");
    evaluate(s, v, "06:00:00");
    v.wifi = "";
    evaluate(s, v, "14:00:00");
    s = J.copy(s);
    v.wifiKnown = false;
    evaluate(s, v, "15:10:00");
    assertNotNull(Core.open(s));
    v.wifiKnown = true;
    evaluate(s, v, "15:11:00");
    assertNull(Core.open(s));
    assertEquals(at("14:00:00"), J.array(s, "entries").optJSONObject(0).optLong("end"));
  }

  @Test
  public void currentGpsInsideAreaKeepsShiftDespiteDisconnectedWifi() {
    JSONObject s = state("wifi_place");
    Core.Signals v = wifi("Office");
    evaluate(s, v, "06:00:00");
    v.wifi = "";
    evaluate(s, v, "14:00:00");
    v.locationValid = true;
    v.lat = 48;
    v.lng = 17;
    v.accuracy = 5;
    v.fixAt = at("15:10:00");
    evaluate(s, v, "15:10:00");
    assertNotNull(Core.open(s));
    v.locationValid = false;
    evaluate(s, v, "15:11:00");
    evaluate(s, v, "16:00:00");
    assertNotNull(Core.open(s));
    evaluate(s, v, "16:11:00");
    assertNull(Core.open(s));
    assertEquals(at("15:11:00"), J.array(s, "entries").optJSONObject(0).optLong("end"));
  }

  @Test
  public void wifiReconnectAfterSleepCancelsDepartureBeforeCheckingDeadline() {
    JSONObject s = state("wifi");
    Core.Signals v = wifi("Office");
    evaluate(s, v, "06:00:00");
    v.wifi = "";
    evaluate(s, v, "14:00:00");
    s = J.copy(s);
    v.wifi = "Office";
    evaluate(s, v, "15:30:00");
    assertNotNull(Core.open(s));
    v.wifi = "";
    evaluate(s, v, "16:00:00");
    evaluate(s, v, "16:59:00");
    assertNotNull(Core.open(s));
    evaluate(s, v, "17:00:00");
    assertNull(Core.open(s));
    assertEquals(at("16:00:00"), J.array(s, "entries").optJSONObject(0).optLong("end"));
  }

  @Test
  public void changedRuleCannotReuseOldDepartureTime() {
    JSONObject s = state("wifi");
    Core.Signals v = wifi("Office");
    evaluate(s, v, "06:00:00");
    v.wifi = "";
    evaluate(s, v, "14:00:00");
    J.put(J.array(s, "rules").optJSONObject(0), "match", "New office");
    evaluate(s, v, "15:10:00");
    assertNotNull(Core.open(s));
    evaluate(s, v, "16:10:00");
    assertNull(Core.open(s));
    assertEquals(at("15:10:00"), J.array(s, "entries").optJSONObject(0).optLong("end"));
  }

  @Test
  public void manualNewShiftCannotInheritPreviousCountdown() {
    JSONObject s = state("wifi");
    Core.Signals v = wifi("Office");
    evaluate(s, v, "06:00:00");
    v.wifi = "";
    evaluate(s, v, "14:00:00");
    Core.clockOut(s, at("14:10:00"));
    Core.clockIn(s, "work", "site", "Site", "manual", at("14:20:00"));
    evaluate(s, v, "15:00:00");
    assertNotNull(Core.open(s));
    evaluate(s, v, "16:00:00");
    assertNull(Core.open(s));
    assertEquals(at("15:00:00"), J.array(s, "entries").optJSONObject(1).optLong("end"));
  }

  @Test
  public void gpsOnlyStillNeedsAnObservedDepartureAndNeverStartsFromUnknownSignals() {
    JSONObject s = state("place");
    Core.Signals v = new Core.Signals();
    evaluate(s, v, "06:00:00");
    assertNull(Core.open(s));
    v.locationValid = true;
    v.lat = 48;
    v.lng = 17;
    v.accuracy = 5;
    v.fixAt = at("06:01:00");
    evaluate(s, v, "06:01:00");
    assertNotNull(Core.open(s));
    evaluate(s, v, "14:00:00"); // A stale indoor fix alone is not a departure.
    evaluate(s, v, "15:00:00");
    assertNotNull(Core.open(s));
    v.lat = 49;
    v.fixAt = at("15:01:00");
    evaluate(s, v, "15:01:00");
    v.locationValid = false;
    evaluate(s, v, "16:01:00");
    assertNotNull(Core.open(s));
    v.locationValid = true;
    v.fixAt = at("16:02:00");
    evaluate(s, v, "16:02:00");
    assertNull(Core.open(s));
    assertEquals(at("15:01:00"), J.array(s, "entries").optJSONObject(0).optLong("end"));
  }

  @Test
  public void backupRestoreDiscardsAutomationEvidence() {
    JSONObject s = state("wifi");
    Core.Signals v = wifi("Office");
    evaluate(s, v, "06:00:00");
    v.wifi = "";
    evaluate(s, v, "14:00:00");
    assertTrue(s.has("automationPending"));
    JSONObject restored =
        Core.fromBackup(
            J.obj("format", "technically-here-backup", "version", 1, "data", s), at("15:00:00"));
    assertFalse(restored.has("automationPending"));
    assertFalse(restored.optBoolean("auto"));
    assertNotNull(Core.open(restored));
  }
}
