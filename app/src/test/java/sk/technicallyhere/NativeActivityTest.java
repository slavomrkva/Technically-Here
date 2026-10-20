package sk.technicallyhere;

import static org.junit.Assert.*;

import android.app.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.time.*;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NativeActivityTest {
  private ActivityController<MainActivity> controller;
  private MainActivity activity;

  @Before
  public void setup() {
    org.robolectric.util.ReflectionHelpers.setStaticField(Repository.class, "singleton", null);
    Repository.get(RuntimeEnvironment.getApplication()).reset();
    controller = Robolectric.buildActivity(MainActivity.class).setup();
    activity = controller.get();
    ShadowLooper.idleMainLooper();
  }

  @After
  public void teardown() {
    controller.pause().stop().destroy();
  }

  private void seedActivity() {
    Repository.get(activity)
        .change(
            s ->
                J.array(s, "kinds")
                    .put(J.obj("id", "kind-work", "name", "Work", "breakMin", 0, "round", false)));
    ShadowLooper.idleMainLooper();
  }

  @Test
  public void freshHomeHasNoInventedActivityAndCanAddFirstOne() {
    assertEquals(0, J.array(Repository.get(activity).snapshot(), "kinds").length());
    assertNull(button(activity.getWindow().getDecorView(), "Clock in"));
    button("Add activity").performClick();
    ShadowLooper.idleMainLooper();
    AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
    ((EditText) described(dialog.getWindow().getDecorView(), "Activity name")).setText("Workshop");
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    ShadowLooper.idleMainLooper();
    assertEquals(1, J.array(Repository.get(activity).snapshot(), "kinds").length());
    assertTrue(button("Clock in").isEnabled());
    assertNotNull(button("Activity: Workshop"));
  }

  @Test
  @Config(qualifiers = "sk-rSK-w390dp-h844dp-mdpi")
  public void interfaceUsesEnglishEvenWithASlovakDeviceLocale() {
    assertEquals(
        "en", activity.getResources().getConfiguration().getLocales().get(0).getLanguage());
    assertNotNull(button("Today"));
    assertNotNull(button("Activities"));
    assertNotNull(button("Hours"));
    assertNotNull(button("Settings"));
  }

  private Button button(View v, String label) {
    if (v instanceof Button && ((Button) v).getText().toString().equals(label)) return (Button) v;
    if (v instanceof ViewGroup) {
      for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
        Button b = button(((ViewGroup) v).getChildAt(i), label);
        if (b != null) return b;
      }
    }
    return null;
  }

  private Button button(String label) {
    Button result = button(activity.getWindow().getDecorView(), label);
    assertNotNull("Missing button: " + label, result);
    return result;
  }

  @Test
  public void manualTimerUsesNativeButtonsAndPersists() {
    seedActivity();
    button("Clock in").performClick();
    ShadowLooper.idleMainLooper();
    assertNotNull(Core.open(Repository.get(activity).snapshot()));
    button("Clock out").performClick();
    ShadowLooper.idleMainLooper();
    JSONObject state = Repository.get(activity).snapshot();
    assertNull(Core.open(state));
    assertEquals(1, J.array(state, "entries").length());
  }

  @Test
  public void optionalAddonIsNotInstalledAndGenericAddonCanBeAdded() throws Exception {
    assertEquals(0, J.array(Repository.get(activity).snapshot(), "addons").length());
    button("Settings").performClick();
    ShadowLooper.idleMainLooper();
    button("Addons").performClick();
    ShadowLooper.idleMainLooper();
    assertNull(button(activity.getWindow().getDecorView(), "Import sample project report"));
    importGenericAddonThroughDocumentResult();
    ShadowLooper.idleMainLooper();
    assertNull(button(activity.getWindow().getDecorView(), "Export template for AI"));
    JSONObject state = Repository.get(activity).snapshot();
    assertEquals(1, J.array(state, "addons").length());
    assertEquals(
        "Example", J.object(J.array(state, "addons").optJSONObject(0), "template").optString("id"));
  }

  private void importGenericAddonThroughDocumentResult() throws Exception {
    button("Import addon JSON").performClick();
    android.content.Intent launched =
        Shadows.shadowOf(activity).getNextStartedActivityForResult().intent;
    assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT, launched.getAction());
    android.net.Uri uri = android.net.Uri.parse("content://test/addon.json");
    try (InputStream input = new FileInputStream(new File("../test-fixtures/form-template.json"))) {
      byte[] bytes = Repository.readLimited(input, 256000);
      Shadows.shadowOf(activity.getContentResolver())
          .registerInputStream(uri, new ByteArrayInputStream(bytes));
      activity.onActivityResult(20, Activity.RESULT_OK, new android.content.Intent().setData(uri));
    }
    ShadowLooper.idleMainLooper();
  }

  @Test
  public void invalidTransactionDoesNotOverwriteTheStoredState() {
    seedActivity();
    Repository repository = Repository.get(activity);
    String before = repository.snapshot().toString();
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.change(s -> J.put(J.array(s, "kinds").optJSONObject(0), "breakMin", -1)));
    assertEquals(before, repository.snapshot().toString());
  }

  private View described(View view, String description) {
    if (description.contentEquals(
        view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        View found = described(((ViewGroup) view).getChildAt(i), description);
        if (found != null) return found;
      }
    return null;
  }

  private Spinner spinner(View view) {
    if (view instanceof Spinner) return (Spinner) view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        Spinner found = spinner(((ViewGroup) view).getChildAt(i));
        if (found != null) return found;
      }
    return null;
  }

  @Test
  public void activityWifiSettingsPersist() {
    seedActivity();
    button("Activities").performClick();
    ShadowLooper.idleMainLooper();
    button("Edit activity").performClick();
    AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
    View form = dialog.getWindow().getDecorView();
    spinner(form).setSelection(0);
    ShadowLooper.idleMainLooper();
    EditText ssid = (EditText) described(form, "Wi-Fi network name (SSID)");
    assertTrue(ssid.isShown());
    assertFalse(described(form, "Latitude").isShown());
    ssid.setText("Office");
    ((EditText) described(form, "Departure delay (minutes)")).setText("2,5");
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    ShadowLooper.idleMainLooper();
    JSONObject rule =
        Core.activityTriggers(Repository.get(activity).snapshot(), "kind-work").get(0);
    assertEquals("wifi", rule.optString("source"));
    assertEquals("Office", rule.optString("match"));
    assertEquals(150, rule.optInt("confirmSec"));
    button("Edit activity").performClick();
    dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
    assertEquals(0, spinner(dialog.getWindow().getDecorView()).getSelectedItemPosition());
    assertEquals(
        "Office",
        ((EditText) described(dialog.getWindow().getDecorView(), "Wi-Fi network name (SSID)"))
            .getText()
            .toString());
  }

  @Test
  public void activityGpsValidatesRadiusAndAcceptsDecimalComma() {
    seedActivity();
    button("Activities").performClick();
    ShadowLooper.idleMainLooper();
    button("Edit activity").performClick();
    AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
    View form = dialog.getWindow().getDecorView();
    spinner(form).setSelection(1);
    ShadowLooper.idleMainLooper();
    assertFalse(described(form, "Wi-Fi network name (SSID)").isShown());
    ((EditText) described(form, "Latitude")).setText("48,15");
    ((EditText) described(form, "Longitude")).setText("17,11");
    EditText radius = (EditText) described(form, "Radius (metres)");
    assertEquals("200", radius.getText().toString());
    radius.setText("0");
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    assertTrue(dialog.isShowing());
    assertTrue(Core.activityTriggers(Repository.get(activity).snapshot(), "kind-work").isEmpty());
    radius.setText("250");
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    ShadowLooper.idleMainLooper();
    JSONObject r = Core.activityTriggers(Repository.get(activity).snapshot(), "kind-work").get(0);
    assertEquals("place", r.optString("source"));
    assertEquals(48.15, r.optDouble("lat"), .00001);
    assertEquals(250, r.optInt("radiusM"));
  }

  private void collectChecks(View view, java.util.List<CheckBox> checks) {
    if (view instanceof CheckBox) checks.add((CheckBox) view);
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
        collectChecks(((ViewGroup) view).getChildAt(i), checks);
  }

  @Test
  public void monthlyExportOffersMarkdownAndPreparesUtf8File() throws Exception {
    button("Hours").performClick();
    ShadowLooper.idleMainLooper();
    assertNull(button(activity.getWindow().getDecorView(), "Export tohto mesiaca · CSV"));
    button("Export").performClick();
    ShadowLooper.idleMainLooper();
    AlertDialog options = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
    View optionsView = options.getWindow().getDecorView();
    java.util.List<CheckBox> checks = new java.util.ArrayList<>();
    collectChecks(optionsView, checks);
    assertEquals(8, checks.size());
    for (CheckBox check : checks) assertTrue(check.isChecked());
    spinner(optionsView).setSelection(1);
    ShadowLooper.idleMainLooper();
    options.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    ShadowLooper.idleMainLooper();
    android.content.Intent intent =
        Shadows.shadowOf(activity).getNextStartedActivityForResult().intent;
    assertEquals("text/markdown", intent.getType());
    assertTrue(intent.getStringExtra(android.content.Intent.EXTRA_TITLE).endsWith(".md"));
    String text =
        new String(
            java.nio.file.Files.readAllBytes(
                new File(activity.getCacheDir(), "pending-export").toPath()),
            java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(text.startsWith("# Time report"));
    assertTrue(text.contains("| Date |"));
  }

  @Test
  public void addonAlsoOffersMarkdownExport() throws Exception {
    button("Settings").performClick();
    ShadowLooper.idleMainLooper();
    button("Addons").performClick();
    ShadowLooper.idleMainLooper();
    assertNull(button(activity.getWindow().getDecorView(), "Import sample project report"));
    importGenericAddonThroughDocumentResult();
    ShadowLooper.idleMainLooper();
    button("Weekly report").performClick();
    AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
    Button export = button(dialog.getWindow().getDecorView(), "Save Markdown");
    assertNotNull(export);
    export.performClick();
    android.content.Intent intent =
        Shadows.shadowOf(activity).getNextStartedActivityForResult().intent;
    assertEquals("text/markdown", intent.getType());
    assertTrue(intent.getStringExtra(android.content.Intent.EXTRA_TITLE).endsWith(".md"));
  }

  @Test
  public void roundingPersistsAndUnifiedEditorSurvivesRepositoryRefresh() throws Exception {
    seedActivity();
    button("Activities").performClick();
    ShadowLooper.idleMainLooper();
    button("Edit activity").performClick();
    ShadowLooper.idleMainLooper();
    AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
    CheckBox rounding = (CheckBox) described(dialog.getWindow().getDecorView(), "Rounding");
    rounding.performClick();
    Repository.get(activity).notifyChanged();
    ShadowLooper.idleMainLooper();
    assertTrue(rounding.isChecked());
    assertTrue(dialog.isShowing());
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    ShadowLooper.idleMainLooper();
    String saved =
        new String(
            java.nio.file.Files.readAllBytes(
                new File(activity.getFilesDir(), "attendance.json").toPath()),
            java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(J.find(J.array(J.parse(saved), "kinds"), "kind-work").optBoolean("round"));
    controller.recreate();
    activity = controller.get();
    ShadowLooper.idleMainLooper();
    button("Edit activity").performClick();
    ShadowLooper.idleMainLooper();
    dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
    rounding = (CheckBox) described(dialog.getWindow().getDecorView(), "Rounding");
    assertTrue(rounding.isChecked());
    rounding.performClick();
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    assertFalse(
        J.find(J.array(Repository.get(activity).snapshot(), "kinds"), "kind-work")
            .optBoolean("round"));
  }

  @Test
  public void triggerEditorHasWifiGpsAndCombinedAndDefaultsTo200Metres() {
    seedActivity();
    button("Activities").performClick();
    ShadowLooper.idleMainLooper();
    button("Edit activity").performClick();
    AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
    View form = dialog.getWindow().getDecorView();
    Spinner modes = spinner(form);
    assertEquals(4, modes.getCount());
    assertEquals("Wi-Fi", modes.getItemAtPosition(0));
    assertEquals("GPS", modes.getItemAtPosition(1));
    modes.setSelection(1);
    ShadowLooper.idleMainLooper();
    assertFalse(described(form, "Wi-Fi network name (SSID)").isShown());
    assertEquals("200", ((EditText) described(form, "Radius (metres)")).getText().toString());
    ((EditText) described(form, "Latitude")).setText("48");
    ((EditText) described(form, "Longitude")).setText("17");
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    ShadowLooper.idleMainLooper();
    JSONObject r = Core.activityTriggers(Repository.get(activity).snapshot(), "kind-work").get(0);
    assertEquals("place", r.optString("source"));
    assertEquals(200, r.optInt("radiusM"));
    button("Edit activity").performClick();
    dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
    assertEquals(1, spinner(dialog.getWindow().getDecorView()).getSelectedItemPosition());
  }

  private ScrollView outerScroll(View view) {
    if (view instanceof ScrollView) return (ScrollView) view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        ScrollView found = outerScroll(((ViewGroup) view).getChildAt(i));
        if (found != null) return found;
      }
    return null;
  }

  private TextView textView(View view, String value) {
    if (view instanceof TextView && value.equals(((TextView) view).getText().toString()))
      return (TextView) view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        TextView found = textView(((ViewGroup) view).getChildAt(i), value);
        if (found != null) return found;
      }
    return null;
  }

  @Test
  public void scrollSurvivesSettingChangesAndQueuedRepositoryRefreshes() throws Exception {
    seedActivity();
    Repository.get(activity)
        .change(
            s -> {
              for (int i = 0; i < 10; i++)
                J.array(s, "kinds")
                    .put(
                        J.obj(
                            "id",
                            "extra-" + i,
                            "name",
                            "Activity " + i,
                            "round",
                            false,
                            "breakMin",
                            0));
            });
    ShadowLooper.idleMainLooper();
    button("Activities").performClick();
    ShadowLooper.idleMainLooper();
    capture("scroll-before.png");
    outerScroll(activity.getWindow().getDecorView()).scrollTo(0, 450);
    assertEquals(450, outerScroll(activity.getWindow().getDecorView()).getScrollY());
    button("Edit activity").performClick();
    ShadowLooper.idleMainLooper();
    AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
    ((CheckBox) described(dialog.getWindow().getDecorView(), "Rounding")).performClick();
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    Repository.get(activity).notifyChanged();
    ShadowLooper.idleMainLooper();
    capture("scroll-after.png");
    assertEquals(450, outerScroll(activity.getWindow().getDecorView()).getScrollY());
    assertTrue(
        J.find(J.array(Repository.get(activity).snapshot(), "kinds"), "kind-work")
            .optBoolean("round"));
  }

  @Test
  public void tableNoteEditingIsReflectedInMarkdownExport() throws Exception {
    seedActivity();
    java.time.LocalDate day = java.time.LocalDate.now().minusDays(1);
    long start =
        day.atTime(8, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    Repository.get(activity)
        .change(
            s ->
                J.array(s, "entries")
                    .put(
                        J.obj(
                            "id",
                            "note-test",
                            "kindId",
                            "kind-work",
                            "kindName",
                            "Work",
                            "start",
                            start,
                            "end",
                            start + 4 * 3600000L)));
    ShadowLooper.idleMainLooper();
    button("Hours").performClick();
    ShadowLooper.idleMainLooper();
    capture("history-table.png");
    TextView note = textView(activity.getWindow().getDecorView(), "Add note");
    assertNotNull(note);
    note.performClick();
    ShadowLooper.idleMainLooper();
    AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
    ((EditText) described(dialog.getWindow().getDecorView(), "Note")).setText("Job 42");
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    ShadowLooper.idleMainLooper();
    assertEquals(
        "Job 42",
        J.find(J.array(Repository.get(activity).snapshot(), "entries"), "note-test")
            .optString("note"));
    button("Export").performClick();
    ShadowLooper.idleMainLooper();
    dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
    spinner(dialog.getWindow().getDecorView()).setSelection(1);
    ShadowLooper.idleMainLooper();
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    String md =
        new String(
            java.nio.file.Files.readAllBytes(
                new File(activity.getCacheDir(), "pending-export").toPath()),
            java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(md.contains("Job 42"));
    assertTrue(md.contains("4.00"));
  }

  @Test
  public void nativeExampleFormRendersAndSignatureRemainsOptional() throws Exception {
    java.nio.file.Path path = java.nio.file.Paths.get("../test-fixtures/form-template.json");
    if (!java.nio.file.Files.exists(path))
      path = java.nio.file.Paths.get("test-fixtures/form-template.json");
    JSONObject template =
        J.parse(
            new String(
                java.nio.file.Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8));
    JSONObject state = Core.defaults();
    Core.install(state, template);
    JSONObject addon = J.array(state, "addons").optJSONObject(0);
    String kindId = J.array(state, "kinds").optJSONObject(0).optString("id");
    java.time.LocalDate monday = java.time.LocalDate.of(2026, 10, 12);
    java.time.ZoneId zone = java.time.ZoneId.of("Europe/Berlin");
    for (int i = 1; i < 7; i++) {
      java.time.LocalDate day = monday.plusDays(i);
      String from = i <= 4 ? "11:45" : i == 5 ? "07:45" : "06:00",
          to = i <= 4 ? "22:15" : i == 5 ? "18:45" : "16:45";
      long
          start =
              day.atTime(java.time.LocalTime.parse(from)).atZone(zone).toInstant().toEpochMilli(),
          end = day.atTime(java.time.LocalTime.parse(to)).atZone(zone).toInstant().toEpochMilli();
      J.array(state, "entries")
          .put(J.obj("id", "example-" + i, "kindId", kindId, "start", start, "end", end));
      JSONObject days = J.object(addon, "days");
      J.put(days, day.toString(), J.obj("shift", i <= 4 ? "S2" : "S1"));
      J.put(addon, "days", days);
    }
    List<Core.Row> rows = Core.rows(state, addon, monday, 7, System.currentTimeMillis(), zone);
    assertEquals(59.25 * 3600000, rows.stream().mapToLong(r -> r.net).sum(), .01);
    assertEquals("", addon.optString("signature"));
    Bitmap bitmap = Bitmap.createBitmap(1224, 1584, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    canvas.scale(2, 2);
    FormPdf.renderPage(canvas, addon, rows, monday, 0);
    File folder = new File("build/reports/native-preview");
    assertTrue(folder.mkdirs() || folder.isDirectory());
    try (OutputStream out = new FileOutputStream(new File(folder, "example-native.png"))) {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
    }
    int[] before = new int[280 * 80];
    bitmap.getPixels(before, 0, 280, 80, 1240, 280, 80);
    SignatureView signature = new SignatureView(activity, "");
    signature.layout(0, 0, 900, 260);
    assertEquals("", signature.value());
    long now = android.os.SystemClock.uptimeMillis();
    for (int[] point :
        new int[][] {
          {MotionEvent.ACTION_DOWN, 10, 100},
          {MotionEvent.ACTION_MOVE, 300, 130},
          {MotionEvent.ACTION_UP, 500, 60}
        }) {
      MotionEvent event = MotionEvent.obtain(now, now + 20, point[0], point[1], point[2], 0);
      signature.onTouchEvent(event);
      event.recycle();
    }
    assertTrue(signature.value().startsWith("data:image/png;base64,"));
    J.put(addon, "signature", signature.value());
    FormPdf.renderPage(canvas, addon, rows, monday, 0);
    int[] after = new int[280 * 80];
    bitmap.getPixels(after, 0, 280, 80, 1240, 280, 80);
    assertFalse(java.util.Arrays.equals(before, after));
    signature.clear();
    assertEquals("", signature.value());
    bitmap.recycle();
  }

  @Test
  public void exampleEditorOnlyAsksForNameAndSignatureAndKeepsFixedExportValues() throws Exception {
    java.nio.file.Path path = java.nio.file.Paths.get("../test-fixtures/form-template.json");
    if (!java.nio.file.Files.exists(path))
      path = java.nio.file.Paths.get("test-fixtures/form-template.json");
    JSONObject template =
        J.parse(
            new String(
                java.nio.file.Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8));
    Repository repo = Repository.get(activity);
    repo.change(s -> Core.install(s, template));
    JSONObject addon = J.array(repo.snapshot(), "addons").optJSONObject(0);
    org.robolectric.util.ReflectionHelpers.callInstanceMethod(
        activity,
        "editAddon",
        org.robolectric.util.ReflectionHelpers.ClassParameter.from(JSONObject.class, addon));
    ShadowLooper.idleMainLooper();
    AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
    View form = dialog.getWindow().getDecorView();
    assertEquals(1, editorCount(form));
    assertNotNull(button(form, "Clear signature"));
    assertNull(button(form, "Included activities"));
    ((EditText) described(form, "Name")).setText("Test worker");
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    addon = J.array(repo.snapshot(), "addons").optJSONObject(0);
    assertEquals("Test worker", J.object(addon, "values").optString("worker"));
    assertEquals(1, J.array(addon, "kindIds").length());
    J.put(J.object(addon, "values"), "company", "Old company override");
    String md = ReportMarkdown.write(addon, Collections.emptyList(), LocalDate.of(2026, 10, 19));
    assertTrue(md.contains("Test worker"));
    assertTrue(md.contains("Example"));
    assertFalse(md.contains("Old company override"));
    Core.Row row = new Core.Row();
    row.texts = J.obj("approved", "old text");
    JSONObject fixedColumn = null;
    for (JSONObject c : J.objects(J.array(template, "columns")))
      if (c.optString("key").equals("approved")) fixedColumn = c;
    assertEquals("", row.cell(fixedColumn, Locale.GERMANY));
    long workerPlacements =
        J.objects(J.array(J.object(J.object(template, "pdf"), "canvas"), "fields")).stream()
            .filter(f -> f.optString("key").equals("worker"))
            .count();
    assertEquals(2, workerPlacements);
  }

  @Test
  public void multipleExampleRecordsOnOneDayRenderTogetherOnOnePage() throws Exception {
    java.nio.file.Path path = java.nio.file.Paths.get("../test-fixtures/form-template.json");
    if (!java.nio.file.Files.exists(path))
      path = java.nio.file.Paths.get("test-fixtures/form-template.json");
    JSONObject template =
        J.parse(
            new String(
                java.nio.file.Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8));
    JSONObject state = Core.defaults();
    Core.install(state, template);
    JSONObject addon = J.array(state, "addons").optJSONObject(0);
    J.put(J.object(addon, "values"), "worker", "Test worker");
    LocalDate monday = LocalDate.of(2026, 10, 19);
    J.put(addon, "weeks", J.obj(Core.weekKey(monday), J.obj("shift", "S2")));
    ZoneId zone = ZoneId.of("Europe/Berlin");
    String kind = J.array(state, "kinds").optJSONObject(0).optString("id");
    for (int[] times : new int[][] {{0, 8, 12}, {0, 13, 17}, {1, 8, 16}}) {
      LocalDate day = monday.plusDays(times[0]);
      J.array(state, "entries")
          .put(
              J.obj(
                  "id",
                  "block-" + J.array(state, "entries").length(),
                  "kindId",
                  kind,
                  "start",
                  day.atTime(times[1], 0).atZone(zone).toInstant().toEpochMilli(),
                  "end",
                  day.atTime(times[2], 0).atZone(zone).toInstant().toEpochMilli()));
    }
    List<Core.Row> rows = Core.rows(state, addon, monday, 7, System.currentTimeMillis(), zone);
    assertEquals(1, FormLayout.pages(rows, monday).size());
    assertEquals(3, rows.size());
    assertEquals(14.5 * 3_600_000, rows.stream().mapToLong(r -> r.net).sum(), .01);
    Bitmap bitmap = Bitmap.createBitmap(1224, 1584, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    canvas.scale(2, 2);
    FormPdf.renderPage(canvas, addon, rows, monday, 0);
    File folder = new File("build/reports/native-preview");
    assertTrue(folder.mkdirs() || folder.isDirectory());
    try (OutputStream out =
        new FileOutputStream(new File(folder, "example-multiple-records.png"))) {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
    }
    bitmap.recycle();
  }

  private int editorCount(View view) {
    int count = view instanceof EditText ? 1 : 0;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
        count += editorCount(((ViewGroup) view).getChildAt(i));
    return count;
  }

  @Test
  public void newActivityConfiguresAllSettingsInOneFormAtomically() throws Exception {
    button("Add activity").performClick();
    ShadowLooper.idleMainLooper();
    AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
    View form = dialog.getWindow().getDecorView();
    assertEquals(3, ((Spinner) described(form, "Trigger mode")).getSelectedItemPosition());
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    assertTrue(dialog.isShowing());
    assertEquals(0, J.array(Repository.get(activity).snapshot(), "kinds").length());
    ((EditText) described(form, "Activity name")).setText("New work");
    ((EditText) described(form, "Daily break (minutes)")).setText("30");
    ((CheckBox) described(form, "Rounding")).setChecked(true);
    ((Spinner) described(form, "Trigger mode")).setSelection(2);
    ShadowLooper.idleMainLooper();
    ((EditText) described(form, "Wi-Fi network name (SSID)")).setText("Office");
    ((EditText) described(form, "Latitude")).setText("48,15");
    ((EditText) described(form, "Longitude")).setText("17,11");
    ((EditText) described(form, "Radius (metres)")).setText("300");
    ((EditText) described(form, "Departure delay (minutes)")).setText("60");
    captureView(form, "activity-form.png");
    outerScroll(form).scrollTo(0, 10000);
    captureView(form, "activity-form-bottom.png");
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    ShadowLooper.idleMainLooper();
    assertFalse(dialog.isShowing());
    JSONObject state = Repository.get(activity).snapshot();
    assertEquals(1, J.array(state, "kinds").length());
    JSONObject kind = J.array(state, "kinds").optJSONObject(0);
    assertEquals("New work", kind.optString("name"));
    assertEquals(30, kind.optInt("breakMin"));
    assertTrue(kind.optBoolean("round"));
    JSONObject trigger = Core.activityTriggers(state, kind.optString("id")).get(0);
    assertEquals("wifi_place", trigger.optString("source"));
    assertEquals("Office", trigger.optString("match"));
    assertEquals(300, trigger.optInt("radiusM"));
    assertEquals(3600, trigger.optInt("confirmSec"));
    button("Activities").performClick();
    ShadowLooper.idleMainLooper();
    button("Edit activity").performClick();
    ShadowLooper.idleMainLooper();
    dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
    form = dialog.getWindow().getDecorView();
    assertEquals("30", ((EditText) described(form, "Daily break (minutes)")).getText().toString());
    assertTrue(((CheckBox) described(form, "Rounding")).isChecked());
    assertEquals(2, ((Spinner) described(form, "Trigger mode")).getSelectedItemPosition());
  }

  @Test
  public void deletingActivityRequiresConfirmationAndKeepsHoursInExport() throws Exception {
    seedActivity();
    Repository repo = Repository.get(activity);
    LocalDate day = LocalDate.now().minusDays(1);
    long start = day.atTime(8, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    repo.change(
        s ->
            J.array(s, "entries")
                .put(
                    J.obj(
                        "id",
                        "retained",
                        "kindId",
                        "kind-work",
                        "kindName",
                        "Work",
                        "start",
                        start,
                        "end",
                        start + 8 * 3_600_000L)));
    button("Activities").performClick();
    ShadowLooper.idleMainLooper();
    button("Delete activity").performClick();
    ShadowLooper.idleMainLooper();
    AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
    assertEquals(1, J.array(repo.snapshot(), "kinds").length());
    button("Delete activity").performClick();
    ShadowLooper.idleMainLooper();
    dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    ShadowLooper.idleMainLooper();
    assertEquals(0, J.array(repo.snapshot(), "kinds").length());
    assertEquals(1, J.array(repo.snapshot(), "entries").length());
    assertNull(button(activity.getWindow().getDecorView(), "Edit activity"));
    button("Hours").performClick();
    ShadowLooper.idleMainLooper();
    assertNotNull(textView(activity.getWindow().getDecorView(), "Work"));
    button("Export").performClick();
    ShadowLooper.idleMainLooper();
    dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
    ((Spinner) described(dialog.getWindow().getDecorView(), "Format")).setSelection(1);
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    String md =
        new String(
            java.nio.file.Files.readAllBytes(
                new File(activity.getCacheDir(), "pending-export").toPath()),
            java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(md.contains("Work"));
    assertTrue(md.contains("8.00"));
  }

  @Test
  public void newActivityCanSaveCombinedWifiAndGpsTrigger() {
    seedActivity();
    button("Activities").performClick();
    ShadowLooper.idleMainLooper();
    button("Edit activity").performClick();
    ShadowLooper.idleMainLooper();
    AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
    View form = dialog.getWindow().getDecorView();
    ((Spinner) described(form, "Trigger mode")).setSelection(2);
    ShadowLooper.idleMainLooper();
    assertTrue(described(form, "Wi-Fi network name (SSID)").isShown());
    assertTrue(described(form, "Latitude").isShown());
    ((EditText) described(form, "Wi-Fi network name (SSID)")).setText("Example WiFi");
    ((EditText) described(form, "Latitude")).setText("48.0");
    ((EditText) described(form, "Longitude")).setText("17.0");
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    ShadowLooper.idleMainLooper();
    assertFalse(dialog.isShowing());
    JSONObject trigger =
        Core.activityTriggers(Repository.get(activity).snapshot(), "kind-work").get(0);
    assertEquals("wifi_place", trigger.optString("source"));
    assertEquals("Example WiFi", trigger.optString("match"));
    assertEquals(200, trigger.optInt("radiusM"));
    button("Edit activity").performClick();
    ShadowLooper.idleMainLooper();
    dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
    assertEquals(
        2,
        ((Spinner) described(dialog.getWindow().getDecorView(), "Trigger mode"))
            .getSelectedItemPosition());
  }

  @Test
  public void berlinEntryEditAcceptsPastTimesAndExplainsFutureEnd() {
    TimeZone original = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
      controller.recreate();
      activity = controller.get();
      ShadowLooper.idleMainLooper();
      seedActivity();
      ZoneId berlin = ZoneId.of("Europe/Berlin");
      LocalDate day = LocalDate.now(berlin).minusDays(1);
      long start = day.atTime(15, 49, 30).atZone(berlin).toInstant().toEpochMilli();
      JSONObject entry =
          J.obj(
              "id",
              "berlin",
              "kindId",
              "kind-work",
              "start",
              start,
              "end",
              day.atTime(15, 55, 45).atZone(berlin).toInstant().toEpochMilli());
      Repository repo = Repository.get(activity);
      repo.change(s -> J.array(s, "entries").put(entry));
      org.robolectric.util.ReflectionHelpers.callInstanceMethod(
          activity,
          "editEntry",
          org.robolectric.util.ReflectionHelpers.ClassParameter.from(JSONObject.class, entry));
      ShadowLooper.idleMainLooper();
      AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      View form = dialog.getWindow().getDecorView();
      assertEquals(day + " 15:49", ((EditText) described(form, "Start")).getText().toString());
      ((EditText) described(form, "End")).setText(day + " 15:56");
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
      assertFalse(dialog.isShowing());
      JSONObject saved = J.find(J.array(repo.snapshot(), "entries"), "berlin");
      assertEquals(start, saved.optLong("start"));
      assertEquals(
          day.atTime(15, 56).atZone(berlin).toInstant().toEpochMilli(), saved.optLong("end"));
      org.robolectric.util.ReflectionHelpers.callInstanceMethod(
          activity,
          "editEntry",
          org.robolectric.util.ReflectionHelpers.ClassParameter.from(JSONObject.class, saved));
      ShadowLooper.idleMainLooper();
      dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      form = dialog.getWindow().getDecorView();
      EditText end = (EditText) described(form, "End");
      end.setText(LocalDate.now(berlin).plusDays(1) + " 15:55");
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
      assertTrue(dialog.isShowing());
      assertTrue(end.getError().toString().contains("future"));
      button(form, "End now").performClick();
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
      assertFalse(dialog.isShowing());
    } finally {
      TimeZone.setDefault(original);
    }
  }

  @Test
  public void weeklyShiftIsInProfileAndHistoryExportDefaultsToAddonWithDefaultSwitch()
      throws Exception {
    java.nio.file.Path path = java.nio.file.Paths.get("../test-fixtures/form-template.json");
    if (!java.nio.file.Files.exists(path))
      path = java.nio.file.Paths.get("test-fixtures/form-template.json");
    JSONObject template =
        J.parse(
            new String(
                java.nio.file.Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8));
    Repository repo = Repository.get(activity);
    LocalDate monday = Core.monday(LocalDate.now()).minusWeeks(2);
    ZoneId zone = ZoneId.systemDefault();
    repo.change(
        s -> {
          Core.install(s, template);
          String kind = J.array(s, "kinds").optJSONObject(0).optString("id");
          for (int i : new int[] {0, 1, 7}) {
            long start = monday.plusDays(i).atTime(8, 0).atZone(zone).toInstant().toEpochMilli();
            J.array(s, "entries")
                .put(
                    J.obj(
                        "id",
                        "export-" + i,
                        "kindId",
                        kind,
                        "start",
                        start,
                        "end",
                        start + 8 * 3_600_000L));
          }
        });
    org.robolectric.util.ReflectionHelpers.setField(activity, "reportMonday", monday);
    JSONObject addon = J.array(repo.snapshot(), "addons").optJSONObject(0);
    org.robolectric.util.ReflectionHelpers.callInstanceMethod(
        activity,
        "editAddon",
        org.robolectric.util.ReflectionHelpers.ClassParameter.from(JSONObject.class, addon));
    ShadowLooper.idleMainLooper();
    AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
    View form = dialog.getWindow().getDecorView();
    Spinner shift = (Spinner) described(form, "Shift for the whole week");
    shift.setSelection(2);
    ShadowLooper.idleMainLooper();
    button(form, "Next week").performClick();
    ShadowLooper.idleMainLooper();
    assertEquals(0, shift.getSelectedItemPosition());
    shift.setSelection(1);
    ShadowLooper.idleMainLooper();
    button(form, "Previous week").performClick();
    ShadowLooper.idleMainLooper();
    assertEquals(2, shift.getSelectedItemPosition());
    captureView(form, "example-weekly-profile.png");
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    ShadowLooper.idleMainLooper();
    addon = J.array(repo.snapshot(), "addons").optJSONObject(0);
    assertEquals("S2", J.object(J.object(addon, "weeks"), Core.weekKey(monday)).optString("shift"));
    assertEquals(
        "S1",
        J.object(J.object(addon, "weeks"), Core.weekKey(monday.plusWeeks(1))).optString("shift"));
    List<Core.Row> rows =
        Core.rows(repo.snapshot(), addon, monday, 7, System.currentTimeMillis(), zone);
    assertEquals(2, rows.size());
    for (Core.Row row : rows) assertEquals("S2", row.shift);
    org.robolectric.util.ReflectionHelpers.callInstanceMethod(
        activity,
        "addonReport",
        org.robolectric.util.ReflectionHelpers.ClassParameter.from(JSONObject.class, addon));
    ShadowLooper.idleMainLooper();
    dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
    assertNull(button(dialog.getWindow().getDecorView(), "Shift · " + monday));
    assertNotNull(button(dialog.getWindow().getDecorView(), "Details and signature"));
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    button("Hours").performClick();
    ShadowLooper.idleMainLooper();
    button("Export").performClick();
    ShadowLooper.idleMainLooper();
    dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
    form = dialog.getWindow().getDecorView();
    Spinner choices = (Spinner) described(form, "Template");
    assertEquals("Example", choices.getSelectedItem());
    assertTrue(button(form, "Previous week").isShown());
    assertFalse(textView(form, "Fields").isShown());
    captureView(form, "example-weekly-export.png");
    ((Spinner) described(form, "Format")).setSelection(1);
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    String md =
        new String(
            java.nio.file.Files.readAllBytes(
                new File(activity.getCacheDir(), "pending-export").toPath()),
            java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(md.contains("S2"));
    assertFalse(
        md.contains(
            monday
                .plusWeeks(1)
                .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))));
    button("Export").performClick();
    ShadowLooper.idleMainLooper();
    dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
    form = dialog.getWindow().getDecorView();
    ((Spinner) described(form, "Template")).setSelection(1);
    ShadowLooper.idleMainLooper();
    assertTrue(textView(form, "Fields").isShown());
    assertFalse(button(form, "Previous week").isShown());
    ((Spinner) described(form, "Format")).setSelection(1);
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    md =
        new String(
            java.nio.file.Files.readAllBytes(
                new File(activity.getCacheDir(), "pending-export").toPath()),
            java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(md.startsWith("# Time report"));
    assertEquals("default", repo.snapshot().optString("exportTemplate"));
  }

  @Test
  public void manualShortExampleEntryAppearsInHistoryEvenInAnotherMonth() throws Exception {
    java.nio.file.Path path = java.nio.file.Paths.get("../test-fixtures/form-template.json");
    if (!java.nio.file.Files.exists(path))
      path = java.nio.file.Paths.get("test-fixtures/form-template.json");
    JSONObject template =
        J.parse(
            new String(
                java.nio.file.Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8));
    Repository repo = Repository.get(activity);
    repo.change(s -> Core.install(s, template));
    button("Hours").performClick();
    ShadowLooper.idleMainLooper();
    button("Add entry").performClick();
    ShadowLooper.idleMainLooper();
    AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
    View form = dialog.getWindow().getDecorView();
    LocalDate day = LocalDate.now().minusMonths(1).withDayOfMonth(10);
    ((EditText) described(form, "Start")).setText(day + " 08:01");
    ((EditText) described(form, "End")).setText(day + " 08:02");
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    ShadowLooper.idleMainLooper();
    assertFalse(dialog.isShowing());
    assertEquals(1, J.array(repo.snapshot(), "entries").length());
    View screen = activity.getWindow().getDecorView();
    assertNotNull(textView(screen, "08:01"));
    assertNotNull(textView(screen, "08:02"));
    assertNotNull(textView(screen, "0.00"));
    assertNull(textView(screen, "No entries this month."));
    textView(screen, "08:01").performClick();
    ShadowLooper.idleMainLooper();
    dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
    assertEquals(
        day + " 08:01",
        ((EditText) described(dialog.getWindow().getDecorView(), "Start")).getText().toString());
  }

  @Test
  public void nativeScreenRenders() throws Exception {
    capture("empty-home.png");
    seedActivity();
    capture("today.png");
    button("Activities").performClick();
    ShadowLooper.idleMainLooper();
    capture("activities.png");
  }

  private void capture(String name) throws Exception {
    captureView(activity.getWindow().getDecorView(), name);
  }

  private void captureView(View root, String name) throws Exception {
    int width = 390, height = 844;
    root.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
    root.layout(0, 0, width, height);
    root.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
    root.layout(0, 0, width, height);
    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    root.draw(new Canvas(bitmap));
    File reports = new File("build/reports/native-preview");
    assertTrue(reports.mkdirs() || reports.isDirectory());
    try (OutputStream out = new FileOutputStream(new File(reports, name))) {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
    }
    bitmap.recycle();
    // Android PdfDocument's native PDF backend is not implemented by this Robolectric runtime.
    // Actual PDF bytes/page rendering are verified by NativePdfDeviceTest on a device/emulator.

  }
}
