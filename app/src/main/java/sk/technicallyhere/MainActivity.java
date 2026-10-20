package sk.technicallyhere;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.*;
import java.util.*;
import org.json.*;

public final class MainActivity extends Activity {
  private static final int PAPER = 0xfff4f7fa,
      CARD = 0xffffffff,
      INK = 0xff152c3c,
      MUTE = 0xff607586,
      RUST = 0xff087f83;
  private Repository repo;
  private LinearLayout root, content;
  private ScrollView scroll;
  private int tab = 0;
  private boolean addonsSettings = false, diagnosticsExpanded = false;
  private Integer pendingScrollPosition = null;
  private String selectedKind = "";
  private final Set<String> expandedKinds = new HashSet<>();
  private LocalDate reportMonday = Core.monday(LocalDate.now());
  private LocalDate historyMonth = LocalDate.now().withDayOfMonth(1);
  private int historyScrollX = 0;
  private HorizontalScrollView historyTableScroll;
  private TextView timer, today, status;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private boolean pendingStart = false, advancedRules = false;
  private LocationListener oneShot;
  private AlertDialog reportDialog;
  private JSONObject exportAddon;
  private List<Core.Row> exportRows;
  private LocalDate exportMonday;
  private String exportKind = "pdf", exportText = "";
  private final Runnable refresh = () -> render();
  private final Runnable clock =
      new Runnable() {
        public void run() {
          updateClock();
          handler.postDelayed(this, 1000);
        }
      };
  private final ZoneId zone = ZoneId.systemDefault();
  private final DateTimeFormatter inputFormat =
      DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
          .withResolverStyle(java.time.format.ResolverStyle.STRICT);

  @Override
  protected void attachBaseContext(Context base) {
    android.content.res.Configuration config =
        new android.content.res.Configuration(base.getResources().getConfiguration());
    config.setLocale(Locale.UK);
    config.setLayoutDirection(Locale.UK);
    super.attachBaseContext(base.createConfigurationContext(config));
  }

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    repo = Repository.get(this);
    if (state != null) {
      tab = Math.min(3, state.getInt("tab", 0));
      addonsSettings = state.getBoolean("addonsSettings", false);
      diagnosticsExpanded = state.getBoolean("diagnosticsExpanded", false);
      advancedRules = state.getBoolean("advancedRules", false);
      pendingScrollPosition = state.getInt("scrollPosition", 0);
      historyMonth =
          LocalDate.parse(
              state.getString("historyMonth", LocalDate.now().withDayOfMonth(1).toString()));
      historyScrollX = state.getInt("historyScrollX", 0);
      reportMonday = LocalDate.parse(state.getString("reportMonday", reportMonday.toString()));
      selectedKind = state.getString("kind", "");
      ArrayList<String> expanded = state.getStringArrayList("expandedKinds");
      if (expanded != null) expandedKinds.addAll(expanded);
    }
    if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
    root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(PAPER);
    if (Build.VERSION.SDK_INT >= 30)
      root.setOnApplyWindowInsetsListener(
          (v, insets) -> {
            android.graphics.Insets i =
                insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return insets;
          });
    setContentView(root);
    render();
    if (getIntent().getBooleanExtra("resumeTracking", false)) handler.post(() -> startTracking());
  }

  @Override
  public void onSaveInstanceState(Bundle out) {
    out.putString("historyMonth", historyMonth.toString());
    out.putString("reportMonday", reportMonday.toString());
    out.putInt("historyScrollX", historyScrollX);
    out.putInt("tab", tab);
    out.putBoolean("addonsSettings", addonsSettings);
    out.putBoolean("diagnosticsExpanded", diagnosticsExpanded);
    out.putBoolean("advancedRules", advancedRules);
    out.putInt(
        "scrollPosition",
        pendingScrollPosition != null
            ? pendingScrollPosition
            : scroll == null ? 0 : scroll.getScrollY());
    out.putString("kind", selectedKind);
    out.putStringArrayList("expandedKinds", new ArrayList<>(expandedKinds));
    super.onSaveInstanceState(out);
  }

  @Override
  public void onBackPressed() {
    if (tab == 3 && addonsSettings) {
      addonsSettings = false;
      renderFromTop();
    } else super.onBackPressed();
  }

  @Override
  public void onResume() {
    super.onResume();
    TrackingRecovery.resume(this, true);
    repo.subscribe(refresh);
    handler.post(clock);
    render();
  }

  @Override
  public void onPause() {
    repo.unsubscribe(refresh);
    handler.removeCallbacks(clock);
    super.onPause();
  }

  @Override
  public void onDestroy() {
    if (oneShot != null)
      try {
        getSystemService(LocationManager.class).removeUpdates(oneShot);
      } catch (Exception ignored) {
      }
    super.onDestroy();
  }

  private int dp(float n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }

  private TextView text(LinearLayout parent, String value, float size, int color) {
    TextView t = new TextView(this);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setPadding(0, dp(4), 0, dp(6));
    parent.addView(t, new LinearLayout.LayoutParams(-1, -2));
    return t;
  }

  private LinearLayout column() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private LinearLayout card() {
    LinearLayout l = column();
    l.setPadding(dp(16), dp(12), dp(16), dp(14));
    GradientDrawable background = new GradientDrawable();
    background.setColor(CARD);
    background.setCornerRadius(dp(20));
    background.setStroke(dp(1), 0xffe0e8ef);
    l.setBackground(background);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.setMargins(0, dp(8), 0, dp(8));
    content.addView(l, p);
    return l;
  }

  private Button button(LinearLayout parent, String title, Runnable action) {
    Button b = new Button(this);
    b.setText(title);
    b.setAllCaps(false);
    b.setTextSize(14);
    b.setMinHeight(dp(48));
    b.setTextColor(INK);
    b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xffedf3f7));
    parent.addView(b, new LinearLayout.LayoutParams(-1, -2));
    b.setOnClickListener(v -> run(action));
    return b;
  }

  private void run(Runnable work) {
    try {
      work.run();
      TrackingService.recheck();
    } catch (Exception e) {
      new AlertDialog.Builder(this)
          .setTitle("Unable to complete the action")
          .setMessage(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
          .setPositiveButton("Close", null)
          .show();
    }
  }

  private void confirm(String message, Runnable work) {
    new AlertDialog.Builder(this)
        .setMessage(message)
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Confirm", (d, w) -> run(work))
        .show();
  }

  private void change(java.util.function.Consumer<JSONObject> work) {
    repo.change(work);
    TrackingService.recheck();
    render();
  }

  private void renderFromTop() {
    pendingScrollPosition = 0;
    render();
  }

  private void render() {
    if (isFinishing()) return;
    // Repository notifications can request another render before the previous layout finishes.
    // Carry the requested position until the newest ScrollView has actually laid out.
    final int position =
        pendingScrollPosition != null
            ? pendingScrollPosition
            : scroll == null ? 0 : scroll.getScrollY();
    pendingScrollPosition = position;
    root.removeAllViews();
    historyTableScroll = null;
    timer = null;
    today = null;
    status = null;
    LinearLayout title = column();
    title.setPadding(dp(16), dp(10), dp(16), dp(4));
    TextView heading = text(title, "Technically Here", 28, INK);
    heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    text(
        title,
        new String[] {
              "Today at a glance",
              "Your activities",
              "History and reports",
              addonsSettings ? "Settings · Addons" : "Settings"
            }
            [tab],
        13,
        MUTE);
    root.addView(title);
    scroll = new ScrollView(this);
    scroll.setFillViewport(false);
    scroll.setFocusableInTouchMode(true);
    scroll.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
    scroll.requestFocus();
    content = column();
    content.setPadding(dp(16), 0, dp(16), dp(16));
    scroll.addView(content);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    JSONObject state = repo.snapshot();
    if (repo.loadError != null) text(card(), repo.loadError, 14, RUST);
    if (state.optInt("trackingGapCount") > 0) {
      LinearLayout gap = card();
      DateTimeFormatter format = DateTimeFormatter.ofPattern("d. M. HH:mm").withZone(zone);
      text(gap, "Review your hours", 17, RUST);
      text(
          gap,
          "Possible tracking interruptions: "
              + state.optInt("trackingGapCount")
              + ". Latest: "
              + format.format(Instant.ofEpochMilli(state.optLong("trackingGapFrom")))
              + " – "
              + format.format(Instant.ofEpochMilli(state.optLong("trackingGapTo")))
              + ". Recorded times have not been changed.",
          14,
          MUTE);
      button(
          gap,
          "Open hours",
          () -> {
            tab = 2;
            renderFromTop();
          });
      button(gap, "Mark as reviewed", () -> change(s -> J.put(s, "trackingGapCount", 0)));
    }
    switch (tab) {
      case 0:
        todayScreen(state);
        break;
      case 1:
        rulesScreen(state);
        break;
      case 2:
        historyScreen(state);
        break;
      default:
        if (addonsSettings) {
          button(
              content,
              "Back to settings",
              () -> {
                addonsSettings = false;
                renderFromTop();
              });
          addonsScreen(state);
        } else settingsScreen(state);
        break;
    }
    LinearLayout nav = new LinearLayout(this);
    nav.setPadding(dp(4), dp(4), dp(4), dp(4));
    String[] names = {"Today", "Activities", "Hours", "Settings"};
    for (int i = 0; i < names.length; i++) {
      final int selected = i;
      Button b = new Button(this);
      b.setText(names[i]);
      b.setAllCaps(false);
      b.setTextSize(11);
      b.setMinWidth(0);
      b.setPadding(0, 0, 0, 0);
      b.setTextColor(tab == i ? android.graphics.Color.WHITE : MUTE);
      b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(tab == i ? RUST : PAPER));
      b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
      nav.addView(b, new LinearLayout.LayoutParams(0, dp(50), 1));
      b.setOnClickListener(
          v -> {
            tab = selected;
            addonsSettings = false;
            renderFromTop();
          });
    }
    root.addView(nav);
    final ScrollView renderedScroll = scroll;
    renderedScroll
        .getViewTreeObserver()
        .addOnGlobalLayoutListener(
            new ViewTreeObserver.OnGlobalLayoutListener() {
              @Override
              public void onGlobalLayout() {
                if (renderedScroll.getViewTreeObserver().isAlive())
                  renderedScroll.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                if (scroll != renderedScroll) return;
                renderedScroll.scrollTo(0, position);
                if (historyTableScroll != null) historyTableScroll.scrollTo(historyScrollX, 0);
                pendingScrollPosition = null;
              }
            });
    updateClock();
  }

  private List<JSONObject> activeKinds(JSONObject state) {
    List<JSONObject> result = new ArrayList<>();
    for (JSONObject k : J.objects(J.array(state, "kinds")))
      if (!k.optBoolean("archived")) result.add(k);
    return result;
  }

  private void addActivity() {
    editActivity(null);
  }

  private Button primary(LinearLayout parent, String label, Runnable action) {
    Button b = button(parent, label, action);
    b.setTextColor(android.graphics.Color.WHITE);
    b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(RUST));
    return b;
  }

  private void todayScreen(JSONObject state) {
    List<JSONObject> active = activeKinds(state);
    if (active.isEmpty()) {
      LinearLayout empty = card();
      text(empty, "Add your first activity", 26, INK);
      text(empty, "Add an activity manually or import an addon.", 16, MUTE);
      primary(empty, "Add activity", this::addActivity);
      button(
          empty,
          "Import an addon",
          () -> {
            tab = 3;
            addonsSettings = true;
            renderFromTop();
          });
      text(empty, "Then you can start tracking time.", 14, MUTE);
      return;
    }
    if (active.stream().noneMatch(k -> k.optString("id").equals(selectedKind)))
      selectedKind = active.get(0).optString("id");
    JSONObject open = Core.open(state);
    if (open != null) selectedKind = open.optString("kindId");
    LinearLayout hero = card();
    text(
        hero,
        open == null ? "READY TO START" : "TRACKING · " + open.optString("kindName"),
        13,
        RUST);
    timer = text(hero, "00:00:00", 44, INK);
    today = text(hero, "", 17, INK);
    button(
        hero,
        "Activity: " + Core.kind(state, selectedKind).optString("name"),
        () -> {
          String[] labels = active.stream().map(k -> k.optString("name")).toArray(String[]::new);
          new AlertDialog.Builder(this)
              .setTitle("Activity")
              .setItems(
                  labels,
                  (d, w) -> {
                    selectedKind = active.get(w).optString("id");
                    render();
                  })
              .show();
        });
    primary(
            hero,
            "Clock in",
            () ->
                change(
                    s -> {
                      J.put(s, "hold", false);
                      Core.clockIn(
                          s,
                          selectedKind,
                          "manual",
                          "Manual",
                          "manual",
                          System.currentTimeMillis());
                    }))
        .setEnabled(open == null && !active.isEmpty());
    button(
            hero,
            "Clock out",
            () ->
                change(
                    s -> {
                      J.put(s, "hold", true);
                      Core.clockOut(s, System.currentTimeMillis());
                    }))
        .setEnabled(open != null);
  }

  private void automationSettings(JSONObject state) {
    LinearLayout sensors = card();
    text(sensors, "Phone automation", 18, INK);
    text(
        sensors,
        TrackingService.active == null
            ? (state.optBoolean("monitorWanted") ? "Tracking interrupted" : "Tracking is off")
            : "Tracking is active",
        14,
        MUTE);
    button(
        sensors,
        TrackingService.active == null ? "Start background tracking" : "Stop tracking",
        () -> {
          if (TrackingService.active == null) startTracking();
          else stopTracking();
        });
    button(
        sensors,
        "Automatic rules: " + (state.optBoolean("auto", true) ? "on" : "off"),
        () -> change(s -> J.put(s, "auto", !state.optBoolean("auto", true))));
    LinearLayout diagnostics = column();
    button(
        sensors,
        "Signal status",
        () -> {
          diagnosticsExpanded = !diagnosticsExpanded;
          diagnostics.setVisibility(diagnosticsExpanded ? View.VISIBLE : View.GONE);
        });
    sensors.addView(diagnostics);
    diagnostics.setVisibility(diagnosticsExpanded ? View.VISIBLE : View.GONE);
    status = text(diagnostics, "", 13, MUTE);
  }

  private void updateClock() {
    JSONObject state = repo.snapshot(), open = Core.open(state);
    if (timer != null && today != null) {
      long elapsed =
          open == null ? 0 : Math.max(0, System.currentTimeMillis() - open.optLong("start"));
      long sec = elapsed / 1000;
      timer.setText(
          String.format(Locale.ROOT, "%02d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60));
      today.setText(
          getString(
              R.string.today_total,
              Core.duration(Core.todayNet(state, System.currentTimeMillis(), zone))));
    }
    Core.Signals s = repo.signals;
    String gps =
        s.locationValid && System.currentTimeMillis() - s.fixAt < 90000
            ? String.format(Locale.ROOT, "%.5f, %.5f · ±%.0f m", s.lat, s.lng, s.accuracy)
            : "waiting for a valid location";
    if (status != null)
      status.setText(
          getString(
              R.string.signal_status,
              repo.sensorStatus,
              gps,
              (s.wifiKnown ? (s.wifi.isEmpty() ? "disconnected" : s.wifi) : "unavailable"),
              (s.btKnown
                  ? (s.bluetooth.isEmpty() ? "disconnected" : String.join(", ", s.bluetooth))
                  : "unavailable")));
  }

  private void startTracking() {
    new AlertDialog.Builder(this)
        .setTitle("Background tracking")
        .setMessage(
            "Track Wi-Fi and location while the phone is locked? Android may display a silent"
                + " service notification.")
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Continue", (d, w) -> requestTrackingPermissions())
        .show();
  }

  private void requestTrackingPermissions() {
    List<String> permissions = new ArrayList<>();
    for (String permission :
        java.util.Arrays.asList(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
      if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
        permissions.add(permission);
    if (Build.VERSION.SDK_INT >= 31
        && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED)
      permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
    if (Build.VERSION.SDK_INT >= 33) {
      if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
          != PackageManager.PERMISSION_GRANTED)
        permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES);
    }
    pendingStart = true;
    if (permissions.isEmpty()) beginService();
    else requestPermissions(permissions.toArray(new String[0]), 10);
  }

  private void beginService() {
    pendingStart = false;
    J.require(
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
            || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED,
        "Allow location access in the app settings.");
    change(s -> J.put(s, "monitorWanted", true));
    startForegroundService(new Intent(this, TrackingService.class));
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(requestCode, permissions, results);
    if (requestCode == 10 && pendingStart) run(this::beginService);
  }

  private void stopTracking() {
    change(TrackingRecovery::stopped);
    stopService(new Intent(this, TrackingService.class));
  }

  private void appSettings() {
    startActivity(
        new Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + getPackageName())));
  }

  private void backgroundLocationSettings() {
    if (!TrackingRecovery.hasLocation(this)) {
      requestPermissions(
          new String[] {
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
          },
          12);
      return;
    }
    if (Build.VERSION.SDK_INT < 29) return;
    String option =
        Build.VERSION.SDK_INT >= 30
            ? getPackageManager().getBackgroundPermissionOptionLabel().toString()
            : "Allow all the time";
    new AlertDialog.Builder(this)
        .setTitle("Resume after restart")
        .setMessage(
            "Allow background location to resume tracking without opening the app."
                + " In Android, select Permissions → Location → "
                + option
                + ". Also enable precise location. You can still start tracking manually without"
                + " this permission.")
        .setNegativeButton("Not now", null)
        .setPositiveButton(
            "Allow",
            (d, w) -> {
              if (Build.VERSION.SDK_INT == 29)
                requestPermissions(
                    new String[] {Manifest.permission.ACCESS_BACKGROUND_LOCATION}, 11);
              else appSettings();
            })
        .show();
  }

  private void reliabilitySettings(JSONObject state) {
    LinearLayout c = card();
    text(c, "Reliable tracking", 18, INK);
    boolean background = TrackingRecovery.backgroundAllowed(this);
    boolean precise =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED;
    boolean battery = TrackingRecovery.batteryUnrestricted(this);
    text(
        c,
        "Background location: "
            + (background ? "allowed" : "not allowed")
            + "\nPrecise location: "
            + (precise ? "allowed" : "not allowed")
            + "\nBattery optimization exemption: "
            + (battery ? "allowed" : "not allowed"),
        14,
        MUTE);
    button(c, "Location permissions", this::backgroundLocationSettings);
    button(
        c,
        "Allow unrestricted battery use",
        () -> {
          try {
            startActivity(
                new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
          } catch (ActivityNotFoundException e) {
            appSettings();
          }
        });
    text(
        c,
        "Samsung: add this app to Never sleeping apps."
            + " Disable permission removal for unused apps in the app settings.",
        14,
        MUTE);
    button(c, "Android app settings", this::appSettings);
    text(
        c,
        "Enabled tracking resumes after a restart and first unlock, and after an app update."
            + " This needs background location. After a force stop, open the app again."
            + " The silent system service notification remains.",
        14,
        MUTE);
    if (state.optBoolean("monitorWanted") && TrackingService.active == null)
      text(c, repo.sensorStatus, 14, RUST);
  }

  private void rulesScreen(JSONObject state) {
    text(content, "Activities", 20, INK);
    for (JSONObject k : J.objects(J.array(state, "kinds"))) {
      LinearLayout c = card();
      text(c, k.optString("name") + (k.optBoolean("archived") ? " · archived" : ""), 17, INK);
      List<JSONObject> triggers = Core.activityTriggers(state, k.optString("id"));
      String summary = "Manual";
      if (!triggers.isEmpty()) {
        JSONObject r = triggers.get(0);
        summary =
            r.optString("source").equals("wifi")
                ? "Wi-Fi: " + r.optString("match")
                : "GPS: "
                    + r.optDouble("lat")
                    + ", "
                    + r.optDouble("lng")
                    + " · "
                    + r.optDouble("radiusM")
                    + " m";
        if (r.optString("source").equals("wifi_place"))
          summary = "Wi-Fi: " + r.optString("match") + " or GPS · " + r.optDouble("radiusM") + " m";
        if (triggers.size() > 1) summary += " · total " + triggers.size() + " rules";
      }
      text(c, "Trigger: " + summary, 14, MUTE);
      text(
          c,
          "Break "
              + k.optInt("breakMin")
              + " min · "
              + (k.optBoolean("round") ? "15-minute rounding" : "exact time"),
          13,
          MUTE);
      if (!k.optBoolean("archived")) button(c, "Edit activity", () -> editActivity(k));
      button(
          c,
          "Delete activity",
          () ->
              confirm(
                  "Delete activity “"
                      + k.optString("name")
                      + "”? Existing hours will remain in history."
                      + " Its rules will be removed and its running entry will end.",
                  () ->
                      change(
                          next ->
                              Core.deleteActivity(
                                  next, k.optString("id"), System.currentTimeMillis()))));
    }
    primary(content, "Add activity", this::addActivity);
    button(
        content,
        advancedRules ? "Hide additional rules" : "Additional Wi-Fi / GPS rules",
        () -> {
          advancedRules = !advancedRules;
          render();
        });
    if (!advancedRules) return;
    text(content, "Additional rules", 20, INK);
    text(content, "A stop rule ends the current entry and blocks a new start.", 13, MUTE);
    for (JSONObject r : J.objects(J.array(state, "rules"))) {
      if (!Arrays.asList("wifi", "place", "wifi_place").contains(r.optString("source"))) continue;
      LinearLayout c = card();
      text(c, r.optString("name"), 17, INK);
      text(
          c,
          r.optString("source")
              + " · "
              + r.optString("effect")
              + " · "
              + Core.kind(state, r.optString("kindId")).optString("name"),
          13,
          MUTE);
      button(c, "Edit", () -> editRule(r));
      button(
          c,
          "Remove",
          () ->
              confirm(
                  "Remove this rule?",
                  () ->
                      change(
                          s ->
                              J.put(
                                  s,
                                  "rules",
                                  J.arr(
                                      J.objects(J.array(s, "rules")).stream()
                                          .filter(x -> !x.optString("id").equals(r.optString("id")))
                                          .collect(java.util.stream.Collectors.toList()))))));
    }
    button(content, "Add rule", () -> editRule(null));
  }

  private EditText field(LinearLayout parent, String label, String value, boolean number) {
    text(parent, label, 13, MUTE);
    EditText e = new EditText(this);
    e.setContentDescription(label);
    e.setText(value);
    e.setTextSize(15);
    e.setSingleLine(true);
    e.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(4000)});
    if (number)
      e.setInputType(
          InputType.TYPE_CLASS_NUMBER
              | InputType.TYPE_NUMBER_FLAG_DECIMAL
              | InputType.TYPE_NUMBER_FLAG_SIGNED);
    parent.addView(e, new LinearLayout.LayoutParams(-1, -2));
    return e;
  }

  private Spinner select(LinearLayout parent, String label, String[] labels, int selected) {
    text(parent, label, 13, MUTE);
    Spinner s = new Spinner(this);
    s.setContentDescription(label);
    s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
    s.setSelection(Math.max(0, selected));
    parent.addView(s, new LinearLayout.LayoutParams(-1, dp(48)));
    return s;
  }

  private AlertDialog form(String title, LinearLayout form, Runnable save) {
    ScrollView holder = new ScrollView(this);
    holder.setPadding(dp(16), 0, dp(16), 0);
    holder.addView(form);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(holder)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create();
    dialog.setOnShowListener(
        x ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      try {
                        save.run();
                        dialog.dismiss();
                        render();
                        TrackingService.recheck();
                      } catch (Exception e) {
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                      }
                    }));
    dialog.show();
    return dialog;
  }

  private void askText(String title, String initial, java.util.function.Consumer<String> save) {
    LinearLayout l = column();
    EditText e = field(l, title, initial, false);
    form(
        title,
        l,
        () -> {
          J.require(
              !e.getText().toString().trim().isEmpty() || title.equals("App mode"),
              "Enter a value.");
          save.accept(e.getText().toString().trim());
        });
  }

  private void editActivity(JSONObject kind) {
    String id = kind == null ? J.id() : kind.optString("id");
    List<JSONObject> existing = Core.activityTriggers(repo.snapshot(), id);
    JSONObject old = existing.isEmpty() ? new JSONObject() : existing.get(0);
    LinearLayout l = column();
    EditText name = field(l, "Activity name", kind == null ? "" : kind.optString("name"), false);
    EditText dailyBreak =
        field(
            l,
            "Daily break (minutes)",
            kind == null ? "0" : String.valueOf(kind.optInt("breakMin")),
            true);
    CheckBox rounding = new CheckBox(this);
    rounding.setText("Round to 15 minutes");
    rounding.setContentDescription("Rounding");
    rounding.setChecked(kind != null && kind.optBoolean("round"));
    l.addView(rounding);
    boolean combined = old.optString("source").equals("wifi_place");
    Spinner source =
        select(
            l,
            "Trigger mode",
            new String[] {"Wi-Fi", "GPS", "Wi-Fi or GPS", "Manual"},
            existing.isEmpty()
                ? 3
                : combined ? 2 : old.optString("source").equals("place") ? 1 : 0);
    if (existing.size() > 1) text(l, "Saving will replace the existing triggers.", 13, MUTE);
    LinearLayout wifi = column();
    l.addView(wifi);
    EditText ssid = field(wifi, "Wi-Fi network name (SSID)", old.optString("match"), false);
    button(wifi, "Use current Wi-Fi", () -> currentWifi(ssid));
    text(wifi, "The exact network name, including capitalization.", 13, MUTE);
    LinearLayout gps = column();
    l.addView(gps);
    EditText lat = field(gps, "Latitude", old.has("lat") ? old.optString("lat") : "", true);
    EditText lng = field(gps, "Longitude", old.has("lng") ? old.optString("lng") : "", true);
    button(gps, "Use current location", () -> currentLocation(lat, lng));
    EditText radius =
        field(
            gps,
            "Radius (metres)",
            old.optString("source").equals("place") || old.optString("source").equals("wifi_place")
                ? old.optString("radiusM", "200")
                : "200",
            true);
    text(gps, "Starts inside this area. An inaccurate location will not end tracking.", 13, MUTE);
    LinearLayout automatic = column();
    l.addView(automatic);
    EditText delay =
        field(
            automatic,
            "Departure delay (minutes)",
            String.valueOf(old.optDouble("confirmSec", 3600) / 60),
            true);
    text(
        automatic,
        "Returning during the delay cancels departure. The end is saved at the first detected"
            + " departure.",
        13,
        MUTE);
    Runnable visibility =
        () -> {
          int choice = source.getSelectedItemPosition();
          wifi.setVisibility(choice == 0 || choice == 2 ? View.VISIBLE : View.GONE);
          gps.setVisibility(choice == 1 || choice == 2 ? View.VISIBLE : View.GONE);
          automatic.setVisibility(choice != 3 ? View.VISIBLE : View.GONE);
        };
    source.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onItemSelected(AdapterView<?> p, View v, int position, long id) {
            visibility.run();
          }

          public void onNothingSelected(AdapterView<?> p) {}
        });
    visibility.run();
    form(
        kind == null ? "Add activity" : "Edit activity",
        l,
        () -> {
          int choice = source.getSelectedItemPosition();
          String title = name.getText().toString().trim();
          double pause = decimal(dailyBreak);
          J.require(
              Double.isFinite(pause) && pause >= 0 && pause <= 1440 && pause == Math.floor(pause),
              "The break must be a whole number from 0 to 1440 minutes.");
          double minutes = choice == 3 ? 60 : decimal(delay);
          J.require(
              Double.isFinite(minutes) && minutes >= 0 && minutes <= 1440,
              "The delay must be between 0 and 1440 minutes.");
          boolean useWifi = choice != 1, useGps = choice != 0;
          JSONObject r =
              choice == 3
                  ? null
                  : J.obj(
                      "id",
                      J.id(),
                      "name",
                      title + (choice == 2 ? " · Wi-Fi or GPS" : useWifi ? " · Wi-Fi" : " · GPS"),
                      "kindId",
                      id,
                      "source",
                      choice == 2 ? "wifi_place" : useWifi ? "wifi" : "place",
                      "effect",
                      "start",
                      "match",
                      useWifi ? ssid.getText().toString() : "",
                      "lat",
                      useGps ? decimal(lat) : 0,
                      "lng",
                      useGps ? decimal(lng) : 0,
                      "radiusM",
                      useGps ? decimal(radius) : 200,
                      "confirmSec",
                      Math.round(minutes * 60));
          JSONObject trigger = r;
          change(
              s -> {
                J.require(
                    kind == null || J.find(J.array(s, "kinds"), id) != null,
                    "This activity no longer exists.");
                Core.saveActivity(s, id, title, (int) pause, rounding.isChecked(), trigger);
              });
          selectedKind = id;
        });
  }

  private double decimal(EditText input) {
    return Double.parseDouble(input.getText().toString().trim().replace(',', '.'));
  }

  @SuppressWarnings("deprecation")
  private void currentWifi(EditText ssid) {
    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(
          new String[] {
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
          },
          13);
      Toast.makeText(
              this, "Allow location access, then tap Use current Wi-Fi again.", Toast.LENGTH_LONG)
          .show();
      return;
    }
    try {
      android.net.wifi.WifiManager manager =
          (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
      android.net.wifi.WifiInfo info = manager == null ? null : manager.getConnectionInfo();
      String value = info == null ? "" : info.getSSID();
      J.require(
          value != null && !value.isEmpty() && !value.equals("<unknown ssid>"),
          "Connect to Wi-Fi and enable location, or enter the network name manually.");
      if (value.startsWith("\"") && value.endsWith("\""))
        value = value.substring(1, value.length() - 1);
      ssid.setText(value);
    } catch (Exception e) {
      Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
    }
  }

  private void editRule(JSONObject old) {
    J.require(!activeKinds(repo.snapshot()).isEmpty(), "Add an activity first.");
    JSONObject state = repo.snapshot();
    JSONObject r =
        old == null
            ? J.obj(
                "id",
                J.id(),
                "name",
                "",
                "source",
                "place",
                "effect",
                "start",
                "kindId",
                selectedKind,
                "lat",
                repo.signals.locationValid ? repo.signals.lat : 0,
                "lng",
                repo.signals.locationValid ? repo.signals.lng : 0,
                "radiusM",
                200,
                "confirmSec",
                3600,
                "match",
                "")
            : J.copy(old);
    LinearLayout l = column();
    EditText name = field(l, "Name", r.optString("name"), false);
    String[] sources = {"wifi", "place", "wifi_place"};
    Spinner source =
        select(
            l,
            "Source",
            new String[] {"Wi-Fi network (SSID)", "GPS location", "Wi-Fi or GPS"},
            r.optString("source").equals("wifi_place")
                ? 2
                : r.optString("source").equals("wifi") ? 0 : 1);
    Spinner effect =
        select(
            l,
            "Action",
            new String[] {"Start activity", "Stop entry"},
            r.optString("effect").equals("stop") ? 1 : 0);
    List<JSONObject> kinds = new ArrayList<>();
    for (JSONObject k : J.objects(J.array(state, "kinds")))
      if (!k.optBoolean("archived")) kinds.add(k);
    String[] kindNames = kinds.stream().map(k -> k.optString("name")).toArray(String[]::new);
    int index = 0;
    for (int i = 0; i < kinds.size(); i++)
      if (kinds.get(i).optString("id").equals(r.optString("kindId"))) index = i;
    Spinner kind = select(l, "Activity to start", kindNames, index);
    EditText lat = field(l, "Latitude (GPS)", String.valueOf(r.optDouble("lat")), true),
        lng = field(l, "Longitude (GPS)", String.valueOf(r.optDouble("lng")), true);
    button(l, "Use current location", () -> currentLocation(lat, lng));
    EditText
        radius = field(l, "Radius in metres (GPS)", String.valueOf(r.optInt("radiusM", 200)), true),
        match = field(l, "Wi-Fi network name (SSID)", r.optString("match"), false);

    EditText wait =
        field(
            l,
            "Departure delay (minutes)",
            String.valueOf(r.optDouble("confirmSec", 3600) / 60),
            true);
    form(
        old == null ? "New rule" : "Edit rule",
        l,
        () -> {
          J.put(r, "name", name.getText().toString().trim());
          J.put(r, "source", sources[source.getSelectedItemPosition()]);
          J.put(r, "effect", effect.getSelectedItemPosition() == 0 ? "start" : "stop");
          J.put(r, "kindId", kinds.get(kind.getSelectedItemPosition()).optString("id"));
          J.put(r, "lat", Double.parseDouble(lat.getText().toString()));
          J.put(r, "lng", Double.parseDouble(lng.getText().toString()));
          J.put(r, "radiusM", Double.parseDouble(radius.getText().toString()));
          J.put(r, "match", match.getText().toString().trim());
          J.put(r, "confirmSec", Math.round(Double.parseDouble(wait.getText().toString()) * 60));
          Core.validateRule(r);
          change(
              s -> {
                List<JSONObject> rules = J.objects(J.array(s, "rules"));
                rules.removeIf(x -> x.optString("id").equals(r.optString("id")));
                rules.add(r);
                J.put(s, "rules", J.arr(rules));
              });
        });
  }

  @SuppressWarnings("deprecation")
  private void currentLocation(EditText lat, EditText lng) {
    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(
          new String[] {
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
          },
          11);
      Toast.makeText(
              this, "After granting permission, tap Use current location again.", Toast.LENGTH_LONG)
          .show();
      return;
    }
    LocationManager manager = getSystemService(LocationManager.class);
    if (oneShot != null) manager.removeUpdates(oneShot);
    oneShot =
        new LocationListener() {
          public void onLocationChanged(Location fix) {
            lat.setText(String.valueOf(fix.getLatitude()));
            lng.setText(String.valueOf(fix.getLongitude()));
            manager.removeUpdates(this);
            Toast.makeText(
                    MainActivity.this,
                    "Accuracy ±" + Math.round(fix.getAccuracy()) + " m",
                    Toast.LENGTH_LONG)
                .show();
          }
        };
    String provider =
        manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            ? LocationManager.GPS_PROVIDER
            : LocationManager.NETWORK_PROVIDER;
    manager.requestSingleUpdate(provider, oneShot, Looper.getMainLooper());
    LocationListener current = oneShot;
    handler.postDelayed(() -> manager.removeUpdates(current), 30000);
    Toast.makeText(this, "Finding location…", Toast.LENGTH_SHORT).show();
  }

  private void pickBonded(EditText output) {
    if (Build.VERSION.SDK_INT >= 31
        && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {Manifest.permission.BLUETOOTH_CONNECT}, 12);
      return;
    }
    BluetoothManager manager = getSystemService(BluetoothManager.class);
    BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
    J.require(adapter != null, "No Bluetooth adapter is available.");
    List<BluetoothDevice> devices = new ArrayList<>(adapter.getBondedDevices());
    J.require(!devices.isEmpty(), "Pair the device in your phone settings first.");
    String[] names =
        devices.stream().map(d -> d.getName() + " · " + d.getAddress()).toArray(String[]::new);
    new AlertDialog.Builder(this)
        .setTitle("Device for rule")
        .setItems(names, (d, w) -> output.setText(devices.get(w).getAddress()))
        .show();
  }

  private void historyScreen(JSONObject state) {
    text(
        content,
        historyMonth.format(
            DateTimeFormatter.ofPattern("LLLL yyyy", Locale.forLanguageTag("en-GB"))),
        22,
        INK);
    LinearLayout months = new LinearLayout(this);
    content.addView(months);
    Button previous =
        button(
            months,
            "Previous month",
            () -> {
              historyMonth = historyMonth.minusMonths(1);
              renderFromTop();
            });
    previous.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
    Button next =
        button(
            months,
            "Next month",
            () -> {
              historyMonth = historyMonth.plusMonths(1);
              renderFromTop();
            });
    next.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
    long latest =
        J.objects(J.array(state, "entries")).stream()
            .mapToLong(e -> e.optLong("start"))
            .max()
            .orElse(-1);
    if (latest >= 0) {
      LocalDate latestMonth =
          Instant.ofEpochMilli(latest).atZone(zone).toLocalDate().withDayOfMonth(1);
      if (!latestMonth.equals(historyMonth))
        button(
            content,
            "Latest entry",
            () -> {
              historyMonth = latestMonth;
              renderFromTop();
            });
    }
    if (!activeKinds(state).isEmpty()) button(content, "Add entry", () -> editEntry(null));
    button(content, "Export", this::standardExportOptions);
    JSONObject addon = J.obj("template", Core.standardTemplate());
    List<Core.Row> rows =
        Core.rows(
            state,
            addon,
            historyMonth,
            historyMonth.lengthOfMonth(),
            System.currentTimeMillis(),
            zone);
    if (rows.isEmpty()) {
      text(content, "No entries this month.", 15, MUTE);
      return;
    }
    text(
        content,
        "Tap to edit. Scroll the table horizontally. Entries rounded to zero"
            + " show their original times.",
        13,
        MUTE);
    HorizontalScrollView horizontal = new HorizontalScrollView(this);
    historyTableScroll = horizontal;
    horizontal.setHorizontalScrollBarEnabled(true);
    horizontal.setOnScrollChangeListener(
        (v, x, y, oldX, oldY) -> {
          if (pendingScrollPosition == null) historyScrollX = x;
        });
    content.addView(horizontal, new LinearLayout.LayoutParams(-1, -2));
    TableLayout table = new TableLayout(this);
    horizontal.addView(table);
    List<JSONObject> columns = J.objects(J.array(Core.standardTemplate(), "columns"));

    TableRow header = new TableRow(this);
    table.addView(header);
    for (int i = 0; i < columns.size(); i++)
      tableCell(
          header,
          columns.get(i).optString("label"),
          historyColumnWidth(columns.get(i).optString("source")),
          true,
          null);
    tableCell(header, "Actions", 110, true, null);
    long total = 0, pause = 0;
    for (Core.Row r : rows) {
      TableRow row = new TableRow(this);
      table.addView(row);
      for (int i = 0; i < columns.size(); i++) {
        String key = columns.get(i).optString("source");
        Runnable action =
            key.equals("note")
                ? () -> editEntryNote(r.entryId)
                : Arrays.asList("date", "activity", "from", "to").contains(key)
                    ? () -> editCurrentEntry(r.entryId)
                    : null;
        String value = r.cell(columns.get(i), Locale.forLanguageTag("en-GB"));
        tableCell(
            row,
            key.equals("note") && value.isEmpty() ? "Add note" : value,
            historyColumnWidth(columns.get(i).optString("source")),
            false,
            action);
      }
      tableCell(row, "Edit…", 110, false, () -> entryActions(r.entryId));
      total += r.net;
      pause += r.pause;
    }
    TableRow sum = new TableRow(this);
    table.addView(sum);
    for (int i = 0; i < columns.size(); i++) {
      String source = columns.get(i).optString("source");
      tableCell(
          sum,
          i == 0
              ? "Total"
              : source.equals("pause")
                  ? String.valueOf(pause / 60000)
                  : source.equals("hours")
                      ? Core.decimal(total, Locale.forLanguageTag("en-GB"))
                      : "",
          historyColumnWidth(source),
          true,
          null);
    }
    tableCell(sum, "", 110, true, null);
  }

  private int historyColumnWidth(String source) {
    switch (source) {
      case "note":
        return 240;
      case "activity":
        return 150;
      case "date":
        return 120;
      case "pause":
        return 105;
      case "week":
        return 85;
      default:
        return 75;
    }
  }

  private void tableCell(TableRow row, String value, int width, boolean heading, Runnable action) {
    TextView cell = new TextView(this);
    cell.setText(value);
    cell.setTextSize(14);
    cell.setPadding(dp(10), dp(12), dp(10), dp(12));
    cell.setMinHeight(dp(48));
    cell.setTextColor(heading ? android.graphics.Color.WHITE : action == null ? INK : RUST);
    if (heading) cell.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    GradientDrawable background = new GradientDrawable();
    background.setColor(heading ? INK : CARD);
    background.setStroke(dp(1), 0xffe0e8ef);
    cell.setBackground(background);
    row.addView(cell, new TableRow.LayoutParams(dp(width), -1));
    if (action != null) {
      cell.setFocusable(true);
      cell.setOnClickListener(v -> run(action));
    }
  }

  private void editCurrentEntry(String id) {
    JSONObject entry = J.find(J.array(repo.snapshot(), "entries"), id);
    J.require(entry != null, "This entry no longer exists.");
    editEntry(entry);
  }

  private void editEntryNote(String id) {
    JSONObject entry = J.find(J.array(repo.snapshot(), "entries"), id);
    J.require(entry != null, "This entry no longer exists.");
    LinearLayout l = column();
    EditText note = field(l, "Note", entry.optString("note"), false);
    note.setSingleLine(false);
    note.setMinLines(3);
    form(
        "Note",
        l,
        () ->
            change(
                s -> {
                  JSONObject current = J.find(J.array(s, "entries"), id);
                  J.require(current != null, "This entry no longer exists.");
                  J.put(current, "note", note.getText().toString());
                }));
  }

  private void entryActions(String id) {
    new AlertDialog.Builder(this)
        .setTitle("Entry")
        .setItems(
            new String[] {"Edit entry", "Edit note", "Delete"},
            (dialog, which) ->
                run(
                    () -> {
                      if (which == 0) editCurrentEntry(id);
                      else if (which == 1) editEntryNote(id);
                      else
                        confirm(
                            "Delete this entry?",
                            () ->
                                change(
                                    s -> {
                                      JSONObject entry = J.find(J.array(s, "entries"), id);
                                      J.require(entry != null, "This entry no longer exists.");
                                      if (entry.isNull("end")) J.put(s, "hold", true);
                                      List<JSONObject> entries = J.objects(J.array(s, "entries"));
                                      entries.removeIf(e -> e.optString("id").equals(id));
                                      J.put(s, "entries", J.arr(entries));
                                    }));
                    }))
        .show();
  }

  private String format(long ts) {
    return Instant.ofEpochMilli(ts).atZone(zone).format(inputFormat);
  }

  private long parseTime(String value, Long original) {
    if (original != null && format(original).equals(value)) return original;
    LocalDateTime local = LocalDateTime.parse(value, inputFormat);
    J.require(
        !zone.getRules().getValidOffsets(local).isEmpty(),
        "This local time does not exist because of a daylight saving time change.");
    return local.atZone(zone).toInstant().toEpochMilli();
  }

  private void editEntry(JSONObject old) {
    if (old == null && J.find(J.array(repo.snapshot(), "kinds"), selectedKind) == null)
      selectedKind = activeKinds(repo.snapshot()).get(0).optString("id");
    LinearLayout l = column();
    JSONObject e =
        old == null
            ? J.obj(
                "id",
                J.id(),
                "ruleId",
                "manual",
                "ruleName",
                "Manual",
                "kindId",
                selectedKind,
                "kindName",
                Core.kind(repo.snapshot(), selectedKind).optString("name"),
                "how",
                "manual",
                "start",
                LocalDate.now().minusDays(1).atTime(8, 0).atZone(zone).toInstant().toEpochMilli(),
                "end",
                LocalDate.now().minusDays(1).atTime(16, 0).atZone(zone).toInstant().toEpochMilli())
            : J.copy(old);
    text(
        l,
        "Format: YYYY-MM-DD HH:MM · " + zone + ". Leave the end empty for a running entry.",
        13,
        MUTE);
    EditText start = field(l, "Start", format(e.optLong("start")), false),
        end = field(l, "End", e.isNull("end") ? "" : format(e.optLong("end")), false);
    button(
        l,
        "End now",
        () -> {
          end.setText(format(System.currentTimeMillis()));
          end.setError(null);
        });
    List<JSONObject> kinds = activeKinds(repo.snapshot());
    if (kinds.stream().noneMatch(k -> k.optString("id").equals(e.optString("kindId"))))
      kinds.add(Core.kind(repo.snapshot(), e.optString("kindId")));
    int kindIndex = 0;
    for (int i = 0; i < kinds.size(); i++)
      if (kinds.get(i).optString("id").equals(e.optString("kindId"))) kindIndex = i;
    Spinner kindChoice =
        select(
            l,
            "Activity",
            kinds.stream().map(k -> k.optString("name")).toArray(String[]::new),
            kindIndex);
    EditText note = field(l, "Note", e.optString("note"), false);
    note.setSingleLine(false);
    note.setMinLines(3);
    form(
        "Edit entry",
        l,
        () -> {
          long newStart = parseTime(start.getText().toString().trim(), e.optLong("start"));
          String stop = end.getText().toString().trim();
          Long newEnd =
              stop.isEmpty() ? null : parseTime(stop, e.isNull("end") ? null : e.optLong("end"));
          long now = System.currentTimeMillis();
          start.setError(null);
          end.setError(null);
          if (newStart > now)
            entryTimeError(
                start,
                "The start is in the future. Current time: "
                    + Instant.ofEpochMilli(now)
                        .atZone(zone)
                        .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                    + ".");
          // Minute-only editing must not put an end before hidden start seconds.
          if (newEnd != null && newEnd < newStart && format(newEnd).equals(format(newStart)))
            newEnd = newStart;
          if (newEnd != null && newEnd < newStart)
            entryTimeError(end, "The end is before the start.");
          if (newEnd != null && newEnd > now)
            entryTimeError(
                end,
                "The end is in the future. Current time: "
                    + Instant.ofEpochMilli(now)
                        .atZone(zone)
                        .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                    + ". Use End now or leave the end empty.");
          final Long savedEnd = newEnd;
          JSONObject chosen = kinds.get(kindChoice.getSelectedItemPosition());
          change(
              s -> {
                JSONObject current =
                    old == null ? J.copy(e) : J.find(J.array(s, "entries"), e.optString("id"));
                J.require(current != null, "This entry no longer exists.");
                J.put(current, "start", newStart);
                // Preserve a service-recorded departure if the user only edited the text.
                boolean unchangedEnd = stop.equals(e.isNull("end") ? "" : format(e.optLong("end")));
                if (old == null || !unchangedEnd) J.put(current, "end", savedEnd);
                J.put(current, "kindId", chosen.optString("id"));
                J.put(current, "kindName", chosen.optString("name"));
                J.put(current, "note", note.getText().toString());
                if (old == null) J.array(s, "entries").put(current);
              });
          LocalDate savedMonth =
              Instant.ofEpochMilli(newStart).atZone(zone).toLocalDate().withDayOfMonth(1);
          if (!savedMonth.equals(historyMonth)) {
            historyMonth = savedMonth;
            historyScrollX = 0;
            renderFromTop();
          }
        });
  }

  private void entryTimeError(EditText field, String message) {
    field.setError(message);
    field.requestFocus();
    throw new IllegalArgumentException(message);
  }

  private void addonsScreen(JSONObject state) {
    text(content, "Your AI addons", 20, INK);
    text(content, "Custom fields and reports. Updates preserve your data.", 14, MUTE);
    button(content, "Import addon JSON", () -> openDocument(20));
    button(content, "Save AI guide · ADDONS.md", () -> saveAsset("ADDONS.md"));
    for (JSONObject addon : J.objects(J.array(state, "addons"))) {
      LinearLayout c = card();
      JSONObject template = J.object(addon, "template");
      text(c, template.optString("name"), 18, INK);
      text(c, "v" + template.optString("version", "1.0.0"), 13, MUTE);
      button(c, "Details and signature", () -> editAddon(addon));
      button(c, "Weekly report", () -> addonReport(addon));
      button(
          c,
          "Remove addon",
          () ->
              confirm(
                  "Remove this addon and its completed fields? You can create a backup first.",
                  () ->
                      change(
                          s ->
                              J.put(
                                  s,
                                  "addons",
                                  J.arr(
                                      J.objects(J.array(s, "addons")).stream()
                                          .filter(
                                              a -> !a.optString("id").equals(addon.optString("id")))
                                          .collect(java.util.stream.Collectors.toList()))))));
    }
  }

  private void editAddon(JSONObject addon) {
    LinearLayout l = column();
    JSONObject t = J.object(addon, "template");
    LocalDate[] selectedWeek = {reportMonday};
    Map<String, String> shiftDrafts = new LinkedHashMap<>();
    Map<String, EditText> values = new LinkedHashMap<>();
    for (JSONObject f : J.objects(J.array(t, "fields")))
      if (!f.optBoolean("readonly"))
        values.put(
            f.optString("key"),
            field(
                l,
                f.optString("label"),
                J.object(addon, "values").optString(f.optString("key"), f.optString("default")),
                false));
    if (t.optString("shiftScope").equals("week"))
      weeklyShiftEditor(l, addon, selectedWeek, shiftDrafts);
    if (!t.optBoolean("fixedActivities")) text(l, "Included activities", 16, INK);
    Map<String, CheckBox> kinds = new LinkedHashMap<>();
    JSONArray filtered =
        addon.has("kindIds") ? J.array(addon, "kindIds") : t.optJSONArray("kindIds");
    Set<String> selected = filtered == null ? null : new HashSet<>(J.strings(filtered));
    for (JSONObject k : J.objects(J.array(repo.snapshot(), "kinds"))) {
      if (t.optBoolean("fixedActivities")) continue;
      CheckBox box = new CheckBox(this);
      box.setText(k.optString("name"));
      box.setChecked(selected == null || selected.contains(k.optString("id")));
      l.addView(box);
      kinds.put(k.optString("id"), box);
    }
    SignatureView signature =
        t.optBoolean("signature") ? new SignatureView(this, addon.optString("signature")) : null;
    if (signature != null) {
      text(l, "Signature", 16, INK);
      l.addView(signature, new LinearLayout.LayoutParams(-1, dp(150)));
      button(l, "Clear signature", signature::clear);
    }
    form(
        t.optString("name"),
        l,
        () ->
            change(
                s -> {
                  JSONObject a = J.find(J.array(s, "addons"), addon.optString("id"));
                  JSONObject fields = J.object(a, "values");
                  for (Map.Entry<String, EditText> v : values.entrySet()) {
                    J.require(v.getValue().length() <= 4000, "This field is too long.");
                    J.put(fields, v.getKey(), v.getValue().getText().toString());
                  }
                  J.put(a, "values", fields);
                  List<String> include = new ArrayList<>();
                  for (Map.Entry<String, CheckBox> k : kinds.entrySet())
                    if (k.getValue().isChecked()) include.add(k.getKey());
                  if (!t.optBoolean("fixedActivities")) J.put(a, "kindIds", J.arr(include));
                  if (signature != null) J.put(a, "signature", signature.value());
                  if (t.optString("shiftScope").equals("week")) {
                    JSONObject weeks = J.object(a, "weeks");
                    for (Map.Entry<String, String> draft : shiftDrafts.entrySet()) {
                      JSONObject week = J.object(weeks, draft.getKey());
                      J.put(week, "shift", draft.getValue());
                      J.put(weeks, draft.getKey(), week);
                    }
                    J.put(a, "weeks", weeks);
                    reportMonday = selectedWeek[0];
                  }
                }));
  }

  private String weekLabel(LocalDate monday) {
    DateTimeFormatter f = DateTimeFormatter.ofPattern("d. M. yyyy");
    return Core.weekKey(monday) + " · " + monday.format(f) + " – " + monday.plusDays(6).format(f);
  }

  private void weekPicker(LinearLayout parent, LocalDate[] monday, Runnable changed) {
    TextView label = text(parent, weekLabel(monday[0]), 15, INK);
    LinearLayout navigation = new LinearLayout(this);
    parent.addView(navigation);
    Runnable update =
        () -> {
          label.setText(weekLabel(monday[0]));
          changed.run();
        };
    Button previous =
        button(
            navigation,
            "Previous week",
            () -> {
              monday[0] = monday[0].minusWeeks(1);
              update.run();
            });
    previous.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
    Button next =
        button(
            navigation,
            "Next week",
            () -> {
              monday[0] = monday[0].plusWeeks(1);
              update.run();
            });
    next.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
    button(
        parent,
        "Choose a date in the week",
        () -> {
          LocalDate day = monday[0];
          new DatePickerDialog(
                  this,
                  (v, year, month, date) -> {
                    monday[0] = Core.monday(LocalDate.of(year, month + 1, date));
                    update.run();
                  },
                  day.getYear(),
                  day.getMonthValue() - 1,
                  day.getDayOfMonth())
              .show();
        });
  }

  private void weeklyShiftEditor(
      LinearLayout l, JSONObject addon, LocalDate[] monday, Map<String, String> drafts) {
    List<String> shifts = new ArrayList<>();
    shifts.add("—");
    shifts.addAll(J.strings(J.array(J.object(addon, "template"), "shifts")));
    Spinner[] selection = new Spinner[1];
    Runnable load =
        () -> {
          String key = Core.weekKey(monday[0]);
          String saved =
              drafts.containsKey(key)
                  ? drafts.get(key)
                  : J.object(J.object(addon, "weeks"), key).optString("shift");
          drafts.put(key, saved);
          if (selection[0] != null) selection[0].setSelection(Math.max(0, shifts.indexOf(saved)));
        };
    weekPicker(l, monday, load);
    selection[0] = select(l, "Shift for the whole week", shifts.toArray(new String[0]), 0);
    load.run();
    selection[0].setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            int index = selection[0].getSelectedItemPosition();
            drafts.put(Core.weekKey(monday[0]), index == 0 ? "" : shifts.get(index));
          }

          public void onNothingSelected(AdapterView<?> parent) {}
        });
  }

  private void addonReport(JSONObject initial) {
    JSONObject addon = J.find(J.array(repo.snapshot(), "addons"), initial.optString("id"));
    JSONObject t = J.object(addon, "template");
    LinearLayout l = column();
    text(l, Core.weekKey(reportMonday) + " · " + reportMonday, 18, INK);
    button(
        l,
        "Previous week",
        () -> {
          reportMonday = reportMonday.minusWeeks(1);
          addonReport(addon);
        });
    button(
        l,
        "Next week",
        () -> {
          reportMonday = reportMonday.plusWeeks(1);
          addonReport(addon);
        });
    List<Core.Row> rows =
        Core.rows(repo.snapshot(), addon, reportMonday, 7, System.currentTimeMillis(), zone);
    long total = 0;
    Set<String> days = new LinkedHashSet<>();
    Locale locale = Locale.forLanguageTag(t.optString("locale", "en-GB"));
    for (Core.Row row : rows) {
      text(
          l,
          row.date + " · " + row.from + "–" + row.to + " · " + Core.decimal(row.net, locale) + " h",
          14,
          INK);
      days.add(row.dayKey);
      total += row.net;
    }
    text(l, "Total: " + Core.decimal(total, locale) + " h", 18, RUST);
    button(l, "Details and signature", () -> editAddon(addon));
    if (J.object(t, "pdf").has("canvas"))
      for (int i = 0; i < 7; i++) days.add(reportMonday.plusDays(i).toString());
    boolean dayTexts =
        J.objects(J.array(t, "columns")).stream()
            .anyMatch(c -> c.optString("source").equals("text") && !c.optBoolean("readonly"));
    if (!t.optString("shiftScope").equals("week") || dayTexts)
      for (String day : new java.util.TreeSet<>(days))
        button(
            l,
            (dayTexts ? "Shift and notes · " : "Shift · ") + day,
            () -> editAddonDay(addon, day));
    if (J.array(t, "weekFields").length() > 0)
      button(l, "Weekly notes", () -> editAddonWeek(addon));
    button(
        l,
        "Save PDF",
        () -> {
          exportAddon = J.copy(J.find(J.array(repo.snapshot(), "addons"), addon.optString("id")));
          exportRows =
              Core.rows(
                  repo.snapshot(), exportAddon, reportMonday, 7, System.currentTimeMillis(), zone);
          exportMonday = reportMonday;
          exportKind = "pdf";
          createDocument(
              "application/pdf", t.optString("id") + "_" + Core.weekKey(reportMonday) + ".pdf");
        });
    button(
        l,
        "Save Markdown",
        () -> {
          JSONObject current = J.find(J.array(repo.snapshot(), "addons"), addon.optString("id"));
          exportText =
              ReportMarkdown.write(
                  current,
                  Core.rows(
                      repo.snapshot(), current, reportMonday, 7, System.currentTimeMillis(), zone),
                  reportMonday);
          exportKind = "md";
          createDocument(
              "text/markdown", t.optString("id") + "_" + Core.weekKey(reportMonday) + ".md");
        });
    ScrollView scroll = new ScrollView(this);
    scroll.setPadding(dp(16), 0, dp(16), 0);
    scroll.addView(l);
    if (reportDialog != null) reportDialog.dismiss();
    reportDialog =
        new AlertDialog.Builder(this)
            .setTitle(t.optString("name"))
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show();
  }

  private void editAddonDay(JSONObject addon, String day) {
    JSONObject t = J.object(addon, "template"), current = J.object(J.object(addon, "days"), day);
    LinearLayout l = column();
    List<String> shifts = new ArrayList<>();
    shifts.add("—");
    shifts.addAll(J.strings(J.array(t, "shifts")));
    Spinner shift =
        select(
            l,
            "Shift",
            shifts.toArray(new String[0]),
            Math.max(0, shifts.indexOf(current.optString("shift"))));
    Map<String, EditText> texts = new LinkedHashMap<>();
    for (JSONObject c : J.objects(J.array(t, "columns")))
      if (c.optString("source").equals("text") && !c.optBoolean("readonly"))
        texts.put(
            c.optString("key"),
            field(
                l,
                c.optString("label"),
                J.object(current, "texts").optString(c.optString("key")),
                false));
    form(
        day,
        l,
        () ->
            change(
                s -> {
                  JSONObject a = J.find(J.array(s, "addons"), addon.optString("id")),
                      days = J.object(a, "days"),
                      extra = J.object(days, day),
                      values = J.object(extra, "texts");
                  J.put(
                      extra,
                      "shift",
                      shift.getSelectedItemPosition() == 0
                          ? ""
                          : shifts.get(shift.getSelectedItemPosition()));
                  for (Map.Entry<String, EditText> v : texts.entrySet())
                    J.put(values, v.getKey(), v.getValue().getText().toString());
                  J.put(extra, "texts", values);
                  J.put(days, day, extra);
                  J.put(a, "days", days);
                }));
  }

  private void editAddonWeek(JSONObject addon) {
    String week = Core.weekKey(reportMonday);
    JSONObject t = J.object(addon, "template"), current = J.object(J.object(addon, "weeks"), week);
    LinearLayout l = column();
    Map<String, EditText> values = new LinkedHashMap<>();
    for (JSONObject f : J.objects(J.array(t, "weekFields"))) {
      EditText value =
          field(
              l,
              f.optString("label"),
              current.optString(f.optString("key"), f.optString("default")),
              false);
      value.setSingleLine(false);
      value.setMinLines(3);
      values.put(f.optString("key"), value);
    }
    form(
        week,
        l,
        () ->
            change(
                s -> {
                  JSONObject a = J.find(J.array(s, "addons"), addon.optString("id")),
                      weeks = J.object(a, "weeks"),
                      fields = J.object(weeks, week);
                  for (Map.Entry<String, EditText> v : values.entrySet())
                    J.put(fields, v.getKey(), v.getValue().getText().toString());
                  J.put(weeks, week, fields);
                  J.put(a, "weeks", weeks);
                }));
  }

  private void settingsScreen(JSONObject state) {
    LinearLayout addons = card();
    text(addons, "Addons", 20, INK);
    text(addons, "Customize activities and work reports.", 14, MUTE);
    button(
        addons,
        "Addons",
        () -> {
          addonsSettings = true;
          renderFromTop();
        });
    automationSettings(state);
    reliabilitySettings(state);
    LinearLayout c = card();
    text(c, "Backup and restore", 20, INK);
    button(
        c,
        "Save full backup",
        () -> {
          exportText = repo.backup().toString();
          exportKind = "text";
          createDocument("application/json", "hours-backup-" + LocalDate.now() + ".json");
        });
    button(c, "Restore backup", () -> openDocument(21));
    text(c, "One break is deducted per day and activity.", 14, MUTE);
    button(
        c,
        "Delete all data",
        () ->
            confirm(
                "Delete all data? You cannot recover it without a backup.",
                () -> {
                  stopService(new Intent(this, TrackingService.class));
                  repo.reset();
                  render();
                }));
  }

  private void openDocument(int code) {
    Intent intent =
        new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .setType("application/json")
            .addCategory(Intent.CATEGORY_OPENABLE);
    intent.putExtra(
        Intent.EXTRA_MIME_TYPES,
        new String[] {"application/json", "text/plain", "application/octet-stream"});
    startActivityForResult(intent, code);
  }

  private File pendingExport() {
    return new File(getCacheDir(), "pending-export");
  }

  private void createDocument(String type, String name) {
    // Prepare bytes before opening Android's file picker so rotation/process recreation cannot lose
    // the export.
    try (OutputStream output = new FileOutputStream(pendingExport())) {
      if (exportKind.equals("pdf")) AddonPdf.write(exportAddon, exportRows, exportMonday, output);
      else output.write(exportText.getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new IllegalStateException("Unable to prepare the export.", e);
    }
    startActivityForResult(
        new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .setType(type)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_TITLE, name),
        30);
  }

  private void saveAsset(String path) {
    try (InputStream input = getAssets().open(path)) {
      exportText = new String(Repository.readLimited(input, 256000), StandardCharsets.UTF_8);
      exportKind = "text";
      createDocument("text/markdown", "ADDONS.md");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private void standardExportOptions() {
    JSONObject state = repo.snapshot();
    LinearLayout l = column();
    List<JSONObject> addons = J.objects(J.array(state, "addons"));
    List<String> templates = new ArrayList<>();
    for (JSONObject addon : addons) templates.add(J.object(addon, "template").optString("name"));
    templates.add("Default");
    int selected = 0;
    String preferred = state.optString("exportTemplate");
    if (preferred.equals("default")) selected = addons.size();
    else
      for (int i = 0; i < addons.size(); i++)
        if (addons.get(i).optString("id").equals(preferred)) selected = i;
    Spinner templateChoice =
        addons.isEmpty() ? null : select(l, "Template", templates.toArray(new String[0]), selected);
    Spinner format = select(l, "Format", new String[] {"PDF", "Markdown (.md)"}, 0);
    LocalDate[] monday = {reportMonday};
    LinearLayout weekly = column();
    l.addView(weekly);
    weekPicker(weekly, monday, () -> {});
    button(
        weekly,
        "Details and signature",
        () -> {
          int index = templateChoice == null ? -1 : templateChoice.getSelectedItemPosition();
          if (index >= 0 && index < addons.size()) {
            reportMonday = monday[0];
            editAddon(
                J.find(J.array(repo.snapshot(), "addons"), addons.get(index).optString("id")));
          }
        });
    LinearLayout standard = column();
    l.addView(standard);
    text(
        standard,
        historyMonth.format(
            DateTimeFormatter.ofPattern("LLLL yyyy", Locale.forLanguageTag("en-GB"))),
        16,
        INK);
    text(standard, "Fields", 17, INK);
    String[][] groups = {
      {"activity", "Activity"},
      {"date", "Date"},
      {"week", "Week"},
      {"year", "Year"},
      {"times", "Times (from/to)"},
      {"pause", "Break"},
      {"hours", "Total hours"},
      {"note", "Note"}
    };
    Map<String, CheckBox> fields = new LinkedHashMap<>();
    for (String[] group : groups) {
      CheckBox check = new CheckBox(this);
      check.setText(group[1]);
      check.setChecked(true);
      standard.addView(check);
      fields.put(group[0], check);
    }
    Map<String, CheckBox> kinds = new LinkedHashMap<>();
    if (!Core.reportKinds(state).isEmpty()) {
      text(standard, "Include activities", 17, INK);
      for (JSONObject kind : Core.reportKinds(state)) {
        CheckBox check = new CheckBox(this);
        check.setText(kind.optString("name") + (kind.optBoolean("deleted") ? " (deleted)" : ""));
        check.setChecked(true);
        standard.addView(check);
        kinds.put(kind.optString("id"), check);
      }
    }
    Runnable visibility =
        () -> {
          boolean custom =
              templateChoice != null && templateChoice.getSelectedItemPosition() < addons.size();
          weekly.setVisibility(custom ? View.VISIBLE : View.GONE);
          standard.setVisibility(custom ? View.GONE : View.VISIBLE);
        };
    if (templateChoice != null)
      templateChoice.setOnItemSelectedListener(
          new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int position, long id) {
              visibility.run();
            }

            public void onNothingSelected(AdapterView<?> p) {}
          });
    visibility.run();
    form(
        "Export hours",
        l,
        () -> {
          if (templateChoice != null && templateChoice.getSelectedItemPosition() < addons.size()) {
            String id = addons.get(templateChoice.getSelectedItemPosition()).optString("id");
            JSONObject current = J.find(J.array(repo.snapshot(), "addons"), id);
            J.require(current != null, "This addon no longer exists.");
            change(s -> J.put(s, "exportTemplate", id));
            reportMonday = monday[0];
            exportAddon = J.copy(current);
            exportRows =
                Core.rows(
                    repo.snapshot(), exportAddon, monday[0], 7, System.currentTimeMillis(), zone);
            exportMonday = monday[0];
            exportKind = format.getSelectedItemPosition() == 0 ? "pdf" : "md";
            if (exportKind.equals("md"))
              exportText = ReportMarkdown.write(exportAddon, exportRows, exportMonday);
            createDocument(
                exportKind.equals("pdf") ? "application/pdf" : "text/markdown",
                J.object(current, "template").optString("id")
                    + "_"
                    + Core.weekKey(monday[0])
                    + "."
                    + exportKind);
            return;
          }
          Set<String> included = new LinkedHashSet<>();
          for (Map.Entry<String, CheckBox> field : fields.entrySet())
            if (field.getValue().isChecked()) {
              if (field.getKey().equals("times")) {
                included.add("from");
                included.add("to");
              } else included.add(field.getKey());
            }
          JSONObject template = Core.selectedStandardTemplate(included);
          List<String> kindIds = new ArrayList<>();
          for (Map.Entry<String, CheckBox> kind : kinds.entrySet())
            if (kind.getValue().isChecked()) kindIds.add(kind.getKey());
          J.require(kinds.isEmpty() || !kindIds.isEmpty(), "Select at least one activity.");
          if (templateChoice != null) change(s -> J.put(s, "exportTemplate", "default"));
          standardExport(format.getSelectedItemPosition() == 0 ? "pdf" : "md", template, kindIds);
        });
  }

  private void standardExport(String type, JSONObject template, List<String> kindIds) {
    exportAddon = J.obj("template", template, "kindIds", J.arr(kindIds));
    LocalDate first = historyMonth;
    exportRows =
        Core.rows(
            repo.snapshot(),
            exportAddon,
            first,
            first.lengthOfMonth(),
            System.currentTimeMillis(),
            zone);
    exportMonday = first;
    exportKind = type;
    if (type.equals("md")) exportText = ReportMarkdown.write(exportAddon, exportRows, first);
    createDocument(
        type.equals("pdf") ? "application/pdf" : "text/markdown", "hours-" + first + "." + type);
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
    Uri uri = data.getData();
    run(
        () -> {
          try {
            if (requestCode == 20 || requestCode == 21) {
              JSONObject parsed;
              try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("Unable to open the file.");
                parsed =
                    J.parse(
                        new String(
                            Repository.readLimited(input, requestCode == 20 ? 256000 : 20000000),
                            StandardCharsets.UTF_8));
              }
              if (requestCode == 20) {
                change(s -> Core.install(s, parsed));
                Toast.makeText(this, "Addon added or updated.", Toast.LENGTH_LONG).show();
              } else {
                JSONObject restored = Core.fromBackup(parsed, System.currentTimeMillis());
                confirm(
                    "Replace all current data with this backup? Automation and tracking will be"
                        + " turned off.",
                    () -> {
                      stopService(new Intent(this, TrackingService.class));
                      repo.restore(restored);
                      render();
                    });
              }
            } else if (requestCode == 30) {
              try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) throw new IOException("Unable to save the file.");
                try (InputStream prepared = new FileInputStream(pendingExport())) {
                  byte[] buffer = new byte[8192];
                  int n;
                  while ((n = prepared.read(buffer)) != -1) output.write(buffer, 0, n);
                }
              }
              pendingExport().delete();
              Toast.makeText(this, "File saved.", Toast.LENGTH_LONG).show();
            }
          } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
          }
        });
  }
}
