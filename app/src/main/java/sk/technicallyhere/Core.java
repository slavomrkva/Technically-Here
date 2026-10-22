package sk.technicallyhere;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.*;
import org.json.*;

/** Platform-independent attendance calculations, shared by UI, service and tests. */
public final class Core {
  private Core() {}

  public static JSONObject defaults() {
    return J.obj(
        "wifiLunchGraceVersion",
        1,
        "schemaVersion",
        1,
        "auto",
        true,
        "monitorWanted",
        false,
        "hold",
        false,
        "appMode",
        "",
        "kinds",
        new JSONArray(),
        "entries",
        new JSONArray(),
        "rules",
        new JSONArray(),
        "addons",
        new JSONArray(),
        "notes",
        new JSONObject(),
        "columns",
        new JSONArray());
  }

  public static boolean upgradeWifiLunchGrace(JSONObject state) {
    if (state.optInt("wifiLunchGraceVersion") >= 1) return false;
    for (JSONObject r : J.objects(J.array(state, "rules")))
      if (r.optString("source").equals("wifi")
          && r.optString("effect").equals("start")
          && r.optLong("confirmSec") == 60) J.put(r, "confirmSec", 3600);
    J.put(state, "wifiLunchGraceVersion", 1);
    return true;
  }

  public static JSONObject open(JSONObject state) {
    for (JSONObject e : J.objects(J.array(state, "entries"))) if (e.isNull("end")) return e;
    return null;
  }

  public static JSONObject kind(JSONObject state, String id) {
    JSONObject k = J.find(J.array(state, "kinds"), id);
    if (k == null) k = J.find(J.array(state, "deletedKinds"), id);
    return k == null ? J.obj("id", id, "name", "Archived activity") : k;
  }

  public static List<JSONObject> reportKinds(JSONObject state) {
    List<JSONObject> result = J.objects(J.array(state, "kinds"));
    result.addAll(J.objects(J.array(state, "deletedKinds")));
    return result;
  }

  public static void deleteActivity(JSONObject state, String id, long now) {
    JSONObject kind = J.find(J.array(state, "kinds"), id);
    J.require(kind != null, "This activity no longer exists.");
    JSONObject active = open(state);
    if (active != null && active.optString("kindId").equals(id)) {
      clockOut(state, now);
      J.put(state, "hold", true);
    }
    if (J.objects(J.array(state, "entries")).stream()
        .anyMatch(e -> e.optString("kindId").equals(id))) {
      JSONObject historical = J.copy(kind);
      J.put(historical, "deleted", true);
      JSONArray deleted = J.array(state, "deletedKinds");
      deleted.put(historical);
      J.put(state, "deletedKinds", deleted);
    }
    J.put(
        state,
        "kinds",
        J.arr(
            J.objects(J.array(state, "kinds")).stream()
                .filter(k -> !k.optString("id").equals(id))
                .collect(java.util.stream.Collectors.toList())));
    J.put(
        state,
        "rules",
        J.arr(
            J.objects(J.array(state, "rules")).stream()
                .filter(r -> !r.optString("kindId").equals(id))
                .collect(java.util.stream.Collectors.toList())));
  }

  public static void saveActivity(
      JSONObject state, String id, String name, int breakMin, boolean round, JSONObject trigger) {
    J.require(!name.trim().isEmpty() && name.length() <= 4000, "Enter an activity name.");
    J.require(breakMin >= 0 && breakMin <= 1440, "The break must be between 0 and 1440 minutes.");
    if (trigger != null) validateRule(trigger);
    JSONObject kind = J.find(J.array(state, "kinds"), id);
    if (kind == null) {
      kind = J.obj("id", id);
      J.array(state, "kinds").put(kind);
    }
    J.put(kind, "name", name.trim());
    J.put(kind, "breakMin", breakMin);
    J.put(kind, "round", round);
    for (JSONObject entry : J.objects(J.array(state, "entries")))
      if (entry.optString("kindId").equals(id)) J.put(entry, "kindName", name.trim());
    List<JSONObject> existing = activityTriggers(state, id);
    boolean same = trigger == null ? existing.isEmpty() : existing.size() == 1;
    if (trigger != null && same) {
      JSONObject previous = existing.get(0);
      for (String key : Arrays.asList("source", "match", "lat", "lng", "radiusM", "confirmSec"))
        if (!previous.optString(key).equals(trigger.optString(key))) same = false;
      // Numeric JSON representations (e.g. 200 vs 200.0) are equivalent.
      if (!same
          && previous.optString("source").equals(trigger.optString("source"))
          && previous.optString("match").equals(trigger.optString("match"))) {
        same = true;
        for (String key : Arrays.asList("lat", "lng", "radiusM", "confirmSec"))
          if (previous.optDouble(key) != trigger.optDouble(key)) same = false;
      }
    }
    if (!same) configureActivityTrigger(state, id, trigger);
    else if (trigger != null) J.put(existing.get(0), "name", trigger.optString("name"));
  }

  public static String entryProblem(JSONArray entries, long now) {
    List<JSONObject> sorted = J.objects(entries);
    if (sorted.size() != entries.length()) return "Invalid entry.";
    sorted.sort(Comparator.comparingLong(e -> e.optLong("start")));
    Set<String> ids = new HashSet<>();
    long previousEnd = -1;
    for (JSONObject e : sorted) {
      long start = e.optLong("start", -1), end = J.end(e, Long.MAX_VALUE);
      if (!ids.add(e.optString("id")) || e.optString("id").isEmpty()) return "Duplicate entry ID.";
      if (start < 0) return "Invalid entry start.";
      if (start > now) return "An entry starts in the future.";
      if (end < start) return "An entry ends before it starts.";
      if (!e.isNull("end") && end > now) return "An entry ends in the future.";
      if (start < previousEnd) return "Entries overlap or more than one entry is running.";
      previousEnd = end;
    }
    return null;
  }

  public static void validate(JSONObject state, long now) {
    JSONArray kinds = J.array(state, "kinds"),
        entries = J.array(state, "entries"),
        rules = J.array(state, "rules"),
        addons = J.array(state, "addons");
    J.require(kinds.length() <= 1000, "Too many activities.");
    J.require(
        entries.length() <= 100000 && rules.length() <= 1000 && addons.length() <= 50,
        "Too many entries.");
    Set<String> ids = new HashSet<>();
    for (JSONObject k : reportKinds(state)) {
      J.require(
          !k.optString("id").isEmpty() && !k.optString("name").trim().isEmpty(),
          "Enter an activity name.");
      J.require(ids.add(k.optString("id")), "Duplicate activity.");
      J.require(k.optInt("breakMin", 0) >= 0 && k.optInt("breakMin", 0) <= 1440, "Invalid break.");
    }
    String problem = entryProblem(entries, now);
    J.require(problem == null, problem == null ? "" : problem);
    for (JSONObject e : J.objects(entries)) {
      J.require(ids.contains(e.optString("kindId")), "An entry refers to a missing activity.");
      J.require(e.optString("note").length() <= 4000, "The note is too long.");
    }
    Set<String> rids = new HashSet<>();
    for (JSONObject r : J.objects(rules)) {
      validateRule(r);
      J.require(
          J.find(kinds, r.optString("kindId")) != null, "A rule refers to a missing activity.");
      J.require(rids.add(r.optString("id")), "Duplicate rule.");
    }
    Set<String> aids = new HashSet<>();
    for (JSONObject a : J.objects(addons)) {
      validateTemplate(J.object(a, "template"));
      String signature = a.optString("signature");
      J.require(
          signature.length() <= 400000
              && (signature.isEmpty()
                  || signature.matches("data:image/png;base64,[A-Za-z0-9+/=]+")),
          "The signature is invalid or too large.");
      J.require(aids.add(J.object(a, "template").optString("id")), "Duplicate addon.");
    }
  }

  public static void validateRule(JSONObject r) {
    J.require(
        !r.optString("id").isEmpty() && !r.optString("name").trim().isEmpty(),
        "Enter a rule name.");
    J.require(
        java.util.Arrays.asList("place", "wifi", "bluetooth", "mode", "wifi_place")
            .contains(r.optString("source")),
        "Invalid rule source.");
    J.require(
        java.util.Arrays.asList("start", "stop").contains(r.optString("effect")),
        "Invalid rule action.");
    J.require(
        Double.isFinite(r.optDouble("lat"))
            && Math.abs(r.optDouble("lat")) <= 90
            && Double.isFinite(r.optDouble("lng"))
            && Math.abs(r.optDouble("lng")) <= 180,
        "Invalid coordinates.");
    J.require(
        r.optDouble("radiusM") >= 20 && r.optDouble("radiusM") <= 100000,
        "The radius must be between 20 and 100000 metres.");
    J.require(
        r.optLong("confirmSec", -1) >= 0 && r.optLong("confirmSec") <= 86400, "Invalid delay.");
    J.require(
        r.optString("source").equals("place") || !r.optString("match").trim().isEmpty(),
        "Enter a network, device or mode name.");
  }

  /** Only Wi-Fi/GPS starts belong to the per-activity editor. Other rules stay intact. */
  public static List<JSONObject> activityTriggers(JSONObject state, String kindId) {
    List<JSONObject> result = new ArrayList<>();
    for (JSONObject r : J.objects(J.array(state, "rules")))
      if (r.optString("kindId").equals(kindId)
          && r.optString("effect").equals("start")
          && Arrays.asList("wifi", "place", "wifi_place").contains(r.optString("source")))
        result.add(r);
    return result;
  }

  public static void configureActivityTrigger(JSONObject state, String kindId, JSONObject trigger) {
    JSONObject k = J.find(J.array(state, "kinds"), kindId);
    J.require(k != null && !k.optBoolean("archived"), "This activity is not active.");
    if (trigger != null) {
      validateRule(trigger);
      J.require(
          trigger.optString("kindId").equals(kindId)
              && trigger.optString("effect").equals("start")
              && Arrays.asList("wifi", "place", "wifi_place").contains(trigger.optString("source")),
          "Invalid activity trigger.");
    }
    Set<String> removed = new HashSet<>();
    for (JSONObject r : activityTriggers(state, kindId)) removed.add(r.optString("id"));
    List<JSONObject> rules = J.objects(J.array(state, "rules"));
    rules.removeIf(r -> removed.contains(r.optString("id")));
    if (trigger != null) {
      JSONObject replacement = J.copy(trigger);
      // A new ID also discards any old departure countdown in the monitoring service.
      J.put(replacement, "id", J.id());
      rules.add(replacement);
      trigger = replacement;
    }
    JSONObject active = open(state);
    if (active != null && removed.contains(active.optString("ruleId"))) {
      J.put(active, "ruleId", trigger == null ? "manual" : trigger.optString("id"));
      J.put(active, "ruleName", trigger == null ? "Manual" : trigger.optString("name"));
    }
    J.put(state, "rules", J.arr(rules));
  }

  private static void keys(JSONArray array) {
    Set<String> keys = new HashSet<>();
    for (JSONObject v : J.objects(array)) {
      String key = v.optString("key");
      J.require(
          key.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")
              && !java.util.Arrays.asList("constructor", "prototype", "__proto__").contains(key),
          "Invalid field key.");
      J.require(keys.add(key), "Duplicate keys.");
      J.require(
          !v.optString("label").isEmpty() && v.optString("label").length() <= 240,
          "A field label is missing.");
    }
    J.require(keys.size() == array.length(), "Invalid fields.");
  }

  public static void validateTemplate(JSONObject t) {
    J.require(t.optInt("schemaVersion", 1) == 1, "Unsupported addon version.");
    J.require(
        t.optString("id").matches("[A-Za-z][A-Za-z0-9_-]{0,63}") && !t.optString("name").isEmpty(),
        "Invalid addon ID or name.");
    J.require(
        t.optJSONArray("fields") != null
            && t.optJSONArray("columns") != null
            && t.optJSONArray("shifts") != null,
        "The addon must define fields, columns and shifts.");
    JSONArray fields = J.array(t, "fields"),
        columns = J.array(t, "columns"),
        week = J.array(t, "weekFields");
    J.require(
        fields.length() <= 60
            && columns.length() > 0
            && columns.length() <= 20
            && week.length() <= 12
            && J.array(t, "shifts").length() <= 20,
        "The addon is too large.");
    keys(fields);
    keys(columns);
    keys(week);
    J.require(
        java.util.Arrays.asList("sk-SK", "de-DE", "en-GB", "en-US")
            .contains(t.optString("locale", "en-GB")),
        "Unsupported report locale.");
    Set<String> fieldKeys = new HashSet<>();
    for (JSONObject f : J.objects(fields)) fieldKeys.add(f.optString("key"));
    for (JSONObject c : J.objects(columns))
      J.require(
          java.util.Arrays.asList(
                  "week",
                  "shift",
                  "date",
                  "from",
                  "to",
                  "pause",
                  "hours",
                  "text",
                  "activity",
                  "note",
                  "year")
              .contains(c.optString("source")),
          "Unsupported column source.");
    JSONArray activities = J.array(t, "activities");
    J.require(
        !t.has("activities") || t.optJSONArray("activities") != null, "Invalid addon activities.");
    J.require(
        activities.length() <= 50 && J.objects(activities).size() == activities.length(),
        "Too many or invalid addon activities.");
    Set<String> activityKeys = new HashSet<>();
    for (JSONObject activity : J.objects(activities)) {
      J.require(
          activity.optString("key").matches("[A-Za-z][A-Za-z0-9_-]{0,63}")
              && activityKeys.add(activity.optString("key")),
          "Invalid or duplicate addon activity key.");
      J.require(
          !activity.optString("name").trim().isEmpty()
              && activity.optString("name").length() <= 240,
          "Enter a name for the addon activity.");
      J.require(
          activity.optInt("breakMin", 0) >= 0 && activity.optInt("breakMin", 0) <= 1440,
          "Invalid addon break.");
      if (activity.has("trigger")) validateRule(addonTrigger(activity, "preview"));
    }
    if (t.has("pdf")) {
      JSONArray sections = J.array(J.object(t, "pdf"), "sections");
      J.require(J.objects(sections).size() == sections.length(), "Invalid PDF section.");
      J.require(
          java.util.Arrays.asList("portrait", "landscape")
              .contains(J.object(t, "pdf").optString("orientation", "portrait")),
          "Invalid PDF orientation.");
      J.require(sections.length() > 0 && sections.length() <= 20, "Invalid PDF sections.");
      int tables = 0;
      for (JSONObject section : J.objects(sections)) {
        String type = section.optString("type");
        J.require(
            java.util.Arrays.asList("text", "fields", "table", "weekFields", "signature")
                .contains(type),
            "Invalid PDF section.");
        if (type.equals("table")) tables++;
        if (type.equals("fields")) {
          J.require(
              section.optInt("columns", 2) >= 1 && section.optInt("columns", 2) <= 2,
              "PDF field sections support one or two columns.");
          for (String key : J.strings(J.array(section, "keys")))
            J.require(fieldKeys.contains(key), "The PDF refers to a missing field.");
        }
        if (type.equals("signature") && section.has("nameField"))
          J.require(
              fieldKeys.contains(section.optString("nameField")),
              "The signature name field is missing.");
      }
      J.require(tables == 1, "The PDF must contain exactly one table.");
      if (J.object(t, "pdf").has("canvas")) FormLayout.validate(t);
    }
  }

  private static JSONObject addonTrigger(JSONObject activity, String kindId) {
    JSONObject r = J.copy(J.object(activity, "trigger"));
    J.require(
        Arrays.asList("wifi", "place", "wifi_place").contains(r.optString("source")),
        "Addon triggers support Wi-Fi or GPS.");
    J.put(r, "id", J.id());
    J.put(r, "name", activity.optString("name"));
    J.put(r, "kindId", kindId);
    J.put(r, "effect", "start");
    if (!r.has("lat")) J.put(r, "lat", 0);
    if (!r.has("lng")) J.put(r, "lng", 0);
    if (!r.has("radiusM")) J.put(r, "radiusM", 200);
    if (!r.has("confirmSec")) J.put(r, "confirmSec", 3600);
    return r;
  }

  public static String fieldValue(JSONObject addon, JSONObject field) {
    return field.optBoolean("readonly")
        ? field.optString("default")
        : J.object(addon, "values").optString(field.optString("key"), field.optString("default"));
  }

  public static void install(JSONObject state, JSONObject template) {
    validateTemplate(template);
    List<String> activityIds = new ArrayList<>();
    for (JSONObject activity : J.objects(J.array(template, "activities"))) {
      String id = "addon-" + template.optString("id") + "-" + activity.optString("key");
      activityIds.add(id);
      if (J.find(J.array(state, "kinds"), id) == null) {
        J.put(
            state,
            "deletedKinds",
            J.arr(
                J.objects(J.array(state, "deletedKinds")).stream()
                    .filter(k -> !k.optString("id").equals(id))
                    .collect(java.util.stream.Collectors.toList())));
        J.array(state, "kinds")
            .put(
                J.obj(
                    "id",
                    id,
                    "name",
                    activity.optString("name"),
                    "breakMin",
                    activity.optInt("breakMin", 0),
                    "round",
                    activity.optBoolean("round", false)));
        if (activity.has("trigger")) J.array(state, "rules").put(addonTrigger(activity, id));
      }
    }
    List<JSONObject> addons = J.objects(J.array(state, "addons"));
    for (JSONObject a : addons)
      if (J.object(a, "template").optString("id").equals(template.optString("id"))) {
        J.put(a, "template", J.copy(template));
        if (template.optBoolean("fixedActivities")) J.put(a, "kindIds", J.arr(activityIds));
        return;
      }
    JSONObject values = new JSONObject();
    for (JSONObject f : J.objects(J.array(template, "fields")))
      J.put(values, f.optString("key"), f.optString("default"));
    JSONObject a =
        J.obj(
            "id",
            J.id(),
            "template",
            J.copy(template),
            "values",
            values,
            "weeks",
            new JSONObject(),
            "days",
            new JSONObject(),
            "signature",
            "");
    if (template.has("kindIds")) J.put(a, "kindIds", J.array(template, "kindIds"));
    else if (!activityIds.isEmpty()) J.put(a, "kindIds", J.arr(activityIds));
    J.array(state, "addons").put(a);
  }

  public static JSONObject fromBackup(JSONObject raw, long now) {
    J.require(
        raw.optString("format").equals("technically-here-backup") && raw.optInt("version") == 1,
        "Unsupported backup.");
    JSONObject state = J.copy(J.object(raw, "data"));
    for (JSONObject e : J.objects(J.array(state, "entries")))
      if (J.find(J.array(state, "kinds"), e.optString("kindId")) == null
          && J.find(J.array(state, "deletedKinds"), e.optString("kindId")) == null)
        J.array(state, "kinds")
            .put(
                J.obj(
                    "id",
                    e.optString("kindId"),
                    "name",
                    e.optString("kindName"),
                    "archived",
                    true));
    validate(state, now);
    J.put(state, "auto", false);
    J.put(state, "monitorWanted", false);
    J.put(state, "hold", true);
    state.remove("automationPending");
    J.put(state, "schemaVersion", 1);
    return state;
  }

  public static void clockIn(
      JSONObject state, String kindId, String ruleId, String ruleName, String how, long now) {
    String problem = entryProblem(J.array(state, "entries"), now);
    J.require(problem == null, problem == null ? "" : problem);
    JSONObject k = J.find(J.array(state, "kinds"), kindId);
    J.require(k != null && !k.optBoolean("archived"), "Add an active activity first.");
    state.remove("automationPending");
    JSONObject old = open(state);
    if (old != null) J.put(old, "end", now);
    J.array(state, "entries")
        .put(
            J.obj(
                "id",
                J.id(),
                "ruleId",
                ruleId,
                "ruleName",
                ruleName,
                "kindId",
                kindId,
                "kindName",
                k.optString("name"),
                "start",
                now,
                "end",
                null,
                "how",
                how));
  }

  public static void clockOut(JSONObject state, long at) {
    state.remove("automationPending");
    JSONObject e = open(state);
    if (e != null) J.put(e, "end", Math.max(e.optLong("start"), at));
  }

  public static final class Signals {
    public double lat, lng, accuracy;
    public long fixAt;
    public boolean locationValid, wifiKnown, btKnown;
    public String wifi = "", mode = "";
    public final Set<String> bluetooth = new HashSet<>();
  }

  public static Boolean live(JSONObject rule, Signals s, boolean slack, long now) {
    switch (rule.optString("source")) {
      case "place":
        return gpsLive(rule, s, slack, now);
      case "wifi":
        return wifiLive(rule, s);
      case "wifi_place":
        Boolean wifi = wifiLive(rule, s), gps = gpsLive(rule, s, slack, now);
        if (Boolean.TRUE.equals(wifi) || Boolean.TRUE.equals(gps)) return true;
        if (Boolean.FALSE.equals(wifi) && Boolean.FALSE.equals(gps)) return false;
        return null;
      case "bluetooth":
        if (!s.btKnown) return null;
        for (String name : s.bluetooth) if (match(name, rule.optString("match"))) return true;
        return false;
      default:
        return match(s.mode, rule.optString("match"));
    }
  }

  private static Boolean wifiLive(JSONObject rule, Signals s) {
    return !s.wifiKnown ? null : !s.wifi.isEmpty() && s.wifi.equals(rule.optString("match"));
  }

  private static Boolean gpsLive(JSONObject rule, Signals s, boolean slack, long now) {
    if (!s.locationValid
        || now - s.fixAt > 90000
        || s.fixAt > now + 5000
        || !Double.isFinite(s.accuracy)
        || s.accuracy < 0
        || s.accuracy > Math.max(30, rule.optDouble("radiusM"))) return null;
    return distance(s.lat, s.lng, rule.optDouble("lat"), rule.optDouble("lng"))
        <= rule.optDouble("radiusM") + (slack ? 25 : 0);
  }

  private static boolean match(String a, String b) {
    return !a.trim().isEmpty() && a.trim().equalsIgnoreCase(b.trim());
  }

  public static double distance(double lat, double lng, double lat2, double lng2) {
    double p1 = Math.toRadians(lat),
        p2 = Math.toRadians(lat2),
        dp = p2 - p1,
        dl = Math.toRadians(lng2 - lng);
    double x =
        Math.pow(Math.sin(dp / 2), 2) + Math.cos(p1) * Math.cos(p2) * Math.pow(Math.sin(dl / 2), 2);
    return 6371000 * 2 * Math.asin(Math.min(1, Math.sqrt(x)));
  }

  /** Departure observations are saved atomically with entries, not in service memory. */
  public static void evaluate(JSONObject state, Signals signals, long now) {
    evaluate(state, signals, new HashMap<>(), now);
  }

  /** The map mirrors pending observations for callers; persisted state is authoritative. */
  public static void evaluate(
      JSONObject state, Signals signals, Map<String, Long> pending, long now) {
    pending.clear();
    JSONObject entry = open(state), checkpoint = J.object(state, "automationPending");
    String rules = J.array(state, "rules").toString();
    if (entry != null
        && entry.optString("id").equals(checkpoint.optString("entryId"))
        && entry.optLong("start") == checkpoint.optLong("entryStart", -1)
        && entry.optString("ruleId").equals(checkpoint.optString("owner"))
        && rules.equals(checkpoint.optString("rules"))) {
      JSONObject times = J.object(checkpoint, "since");
      for (Iterator<String> keys = times.keys(); keys.hasNext(); ) {
        String id = keys.next();
        long first = times.optLong(id, -1);
        if (first >= entry.optLong("start") && first <= now) pending.put(id, first);
      }
    }
    evaluateRules(state, signals, pending, now);
    entry = open(state);
    if (entry == null || pending.isEmpty()) state.remove("automationPending");
    else {
      JSONObject times = new JSONObject();
      for (Map.Entry<String, Long> time : pending.entrySet())
        J.put(times, time.getKey(), time.getValue());
      J.put(
          state,
          "automationPending",
          J.obj(
              "entryId",
              entry.optString("id"),
              "entryStart",
              entry.optLong("start"),
              "owner",
              entry.optString("ruleId"),
              "rules",
              rules,
              "since",
              times));
    }
  }

  private static void evaluateRules(
      JSONObject state, Signals signals, Map<String, Long> pending, long now) {
    if (!state.optBoolean("auto", true) || entryProblem(J.array(state, "entries"), now) != null) {
      pending.clear();
      return;
    }
    signals.mode = state.optString("appMode");
    JSONObject open = open(state);
    List<JSONObject> rules = new ArrayList<>();
    Map<String, Boolean> live = new HashMap<>();
    boolean blocking = false, allOff = true;
    for (JSONObject r : J.objects(J.array(state, "rules")))
      if (!kind(state, r.optString("kindId")).optBoolean("archived")) {
        rules.add(r);
        Boolean on =
            live(
                r,
                signals,
                open != null && r.optString("id").equals(open.optString("ruleId")),
                now);
        // In combined mode, a known Wi-Fi departure can use the configured grace
        // period when GPS is unavailable. A current GPS fix inside still keeps
        // the entry running; unknown sensors alone never establish departure.
        if (on == null
            && open != null
            && r.optString("id").equals(open.optString("ruleId"))
            && r.optString("source").equals("wifi_place")
            && Boolean.FALSE.equals(wifiLive(r, signals))) on = false;
        live.put(r.optString("id"), on);
        if (r.optString("effect").equals("stop") && Boolean.TRUE.equals(on)) blocking = true;
        if (r.optString("effect").equals("start") && !Boolean.FALSE.equals(on)) allOff = false;
      }
    if (state.optBoolean("hold") && allOff) J.put(state, "hold", false);
    JSONObject chosen = null;
    for (JSONObject r : rules)
      if (r.optString("effect").equals("start")
          && Boolean.TRUE.equals(live.get(r.optString("id")))) {
        if (chosen == null) chosen = r;
        else if (r.optString("source").equals("place")
            && (!chosen.optString("source").equals("place")
                || distance(signals.lat, signals.lng, r.optDouble("lat"), r.optDouble("lng"))
                    < distance(
                        signals.lat,
                        signals.lng,
                        chosen.optDouble("lat"),
                        chosen.optDouble("lng")))) chosen = r;
      }
    if (open == null) {
      pending.clear();
      if (!blocking && !state.optBoolean("hold") && chosen != null)
        clockIn(
            state,
            chosen.optString("kindId"),
            chosen.optString("id"),
            chosen.optString("name"),
            "auto",
            now);
      return;
    }
    String owner = open.optString("ruleId");
    if (!blocking && Boolean.FALSE.equals(live.get(owner)))
      for (JSONObject sibling : rules)
        if (sibling.optString("effect").equals("start")
            && sibling.optString("kindId").equals(open.optString("kindId"))
            && Boolean.TRUE.equals(live.get(sibling.optString("id")))) {
          J.put(open, "ruleId", sibling.optString("id"));
          J.put(open, "ruleName", sibling.optString("name"));
          pending.clear();
          return;
        }
    Set<String> ending = new HashSet<>();
    long stopAt = Long.MAX_VALUE;
    for (JSONObject r : rules) {
      String id = r.optString("id");
      Boolean on = live.get(id);
      boolean stop = r.optString("effect").equals("stop");
      if (on == null && (stop || id.equals(owner)) && pending.containsKey(id)) {
        // Missing observations neither cancel evidence nor confirm a departure.
        ending.add(id);
        continue;
      }
      if (on != null && ((stop && on) || (!stop && id.equals(owner) && !on))) {
        ending.add(id);
        long first = pending.containsKey(id) ? pending.get(id) : now;
        pending.put(id, first);
        if (now - first >= r.optLong("confirmSec") * 1000L) stopAt = Math.min(stopAt, first);
      }
    }
    pending.keySet().retainAll(ending);
    if (stopAt != Long.MAX_VALUE) {
      clockOut(state, stopAt);
      pending.clear();
      return;
    }
    if (!blocking
        && !state.optBoolean("hold")
        && chosen != null
        && !chosen.optString("kindId").equals(open.optString("kindId"))) {
      clockIn(
          state,
          chosen.optString("kindId"),
          chosen.optString("id"),
          chosen.optString("name"),
          "auto",
          now);
      pending.clear();
    }
  }

  public static long dayStart(LocalDate day, ZoneId zone) {
    return day.atStartOfDay(zone).toInstant().toEpochMilli();
  }

  public static String weekKey(LocalDate day) {
    return String.format(
        Locale.ROOT,
        "%04d-W%02d",
        day.get(WeekFields.ISO.weekBasedYear()),
        day.get(WeekFields.ISO.weekOfWeekBasedYear()));
  }

  public static LocalDate monday(LocalDate day) {
    return day.minusDays(day.getDayOfWeek().getValue() - 1);
  }

  public static String duration(long ms) {
    long min = Math.max(0, Math.round(ms / 60000.0));
    return (min / 60) + " h " + (min % 60) + " min";
  }

  public static String decimal(long ms, Locale locale) {
    return String.format(locale, "%.2f", ms / 3600000.0);
  }

  public static final class Row {
    public String dayKey, date, from, to, week, activity, kindId, shift, entryId, note, year;
    public long net, pause;
    public JSONObject texts;

    public String cell(JSONObject column, Locale locale) {
      String key = column.optString("source");
      switch (key) {
        case "week":
          return week;
        case "year":
          return year == null ? "" : year;
        case "shift":
          return shift;
        case "date":
          return date;
        case "from":
          return from;
        case "to":
          return to;
        case "pause":
          return pause == 0 ? "" : String.valueOf(pause / 60000);
        case "hours":
          return decimal(net, locale);
        case "activity":
          return activity;
        case "note":
          return note == null ? "" : note;
        default:
          return column.optBoolean("readonly")
              ? column.optString("default")
              : texts.optString(column.optString("key"));
      }
    }
  }

  public static List<Row> rows(
      JSONObject state, JSONObject addon, LocalDate first, int days, long now, ZoneId zone) {
    List<Row> rows = new ArrayList<>();
    JSONObject template = J.object(addon, "template");
    Locale locale = Locale.forLanguageTag(template.optString("locale", "en-GB"));
    JSONArray filters =
        addon.has("kindIds") ? J.array(addon, "kindIds") : template.optJSONArray("kindIds");
    Set<String> allowed = filters == null ? null : new HashSet<>(J.strings(filters));
    List<JSONObject> entries = J.objects(J.array(state, "entries"));
    entries.sort(Comparator.comparingLong(e -> e.optLong("start")));
    for (int dayIndex = 0; dayIndex < days; dayIndex++) {
      LocalDate day = first.plusDays(dayIndex);
      long a = dayStart(day, zone), b = dayStart(day.plusDays(1), zone);
      Map<String, Long> breaks = new HashMap<>();
      JSONObject extra = J.object(J.object(addon, "days"), day.toString());
      for (JSONObject entry : entries) {
        String kindId = entry.optString("kindId");
        if (allowed != null && !allowed.contains(kindId)) continue;
        JSONObject kind = kind(state, kindId);
        long rawStart = entry.optLong("start"), rawEnd = J.end(entry, now);
        long start = rawStart, end = rawEnd;
        // Running entries use observed time; apply rounding once their end is known.
        if (kind.optBoolean("round") && !entry.isNull("end")) {
          start = Math.round(start / 900000.0) * 900000L;
          end = Math.round(end / 900000.0) * 900000L;
        }
        boolean zero = end <= start;
        if (zero) {
          // Keep short/instant entries editable, exactly once on their original day.
          if (rawStart < a || rawStart >= b) continue;
          start = rawStart;
          end = Math.min(rawEnd, b);
        }
        start = Math.max(start, a);
        end = Math.min(end, b);
        if (!zero && end <= start) continue;
        long duration = zero ? 0 : end - start;
        long
            remaining =
                breaks.containsKey(kindId)
                    ? breaks.get(kindId)
                    : kind.optInt("breakMin", 0) * 60000L,
            cut = Math.min(duration, remaining);
        breaks.put(kindId, remaining - cut);
        Row r = new Row();
        r.entryId = entry.optString("id");
        r.note = entry.optString("note");
        r.dayKey = day.toString();
        r.date = day.format(DateTimeFormatter.ofPattern("dd.MM.yyyy", locale));
        r.from =
            Instant.ofEpochMilli(start).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"));
        r.to =
            end == b
                ? "24:00"
                : Instant.ofEpochMilli(end)
                    .atZone(zone)
                    .format(DateTimeFormatter.ofPattern("HH:mm"));
        r.week = String.valueOf(day.get(WeekFields.ISO.weekOfWeekBasedYear()));
        r.year = String.valueOf(day.getYear());
        r.activity = kind.optString("name");
        r.kindId = kindId;
        r.net = duration - cut;
        r.pause = cut;
        r.shift =
            template.optString("shiftScope").equals("week")
                ? J.object(J.object(addon, "weeks"), weekKey(monday(day))).optString("shift")
                : extra.optString("shift");
        r.texts = J.object(extra, "texts");
        rows.add(r);
      }
    }
    return rows;
  }

  /** One definition for the native history table and its PDF/Markdown exports. */
  public static JSONObject standardTemplate() {
    List<JSONObject> columns = new ArrayList<>();
    String[][] labels = {
      {"date", "Date"},
      {"activity", "Activity"},
      {"week", "Week"},
      {"year", "Year"},
      {"from", "From"},
      {"to", "To"},
      {"pause", "Break (min)"},
      {"hours", "Hours"},
      {"note", "Note"}
    };
    for (String[] pair : labels)
      columns.add(J.obj("key", pair[0], "label", pair[1], "source", pair[0]));
    return J.obj(
        "id",
        "hodiny",
        "name",
        "Time report",
        "title",
        "Time report",
        "locale",
        "en-GB",
        "signature",
        false,
        "fields",
        new JSONArray(),
        "shifts",
        new JSONArray(),
        "columns",
        J.arr(columns),
        "pdf",
        J.obj(
            "orientation",
            "landscape",
            "sections",
            J.arr(
                Arrays.asList(
                    J.obj("type", "text", "text", "Time report", "size", 14),
                    J.obj("type", "table", "totalLabel", "Total")))));
  }

  public static JSONObject selectedStandardTemplate(Set<String> included) {
    JSONObject template = standardTemplate();
    List<JSONObject> columns = J.objects(J.array(template, "columns"));
    columns.removeIf(c -> !included.contains(c.optString("source")));
    J.require(!columns.isEmpty(), "Select at least one field.");
    J.put(template, "columns", J.arr(columns));
    return template;
  }

  public static long todayNet(JSONObject state, long now, ZoneId zone) {
    long total = 0;
    JSONObject all = J.obj("template", J.obj("locale", "en-GB"));
    for (Row r :
        rows(state, all, Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), 1, now, zone))
      total += r.net;
    return total;
  }
}
