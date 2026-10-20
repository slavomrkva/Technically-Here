package sk.technicallyhere;

import java.util.*;
import org.json.*;

/** Declarative fixed-form pages. Coordinates and all business content belong to the addon. */
public final class FormLayout {
  private FormLayout() {}

  public static void validate(JSONObject template) {
    JSONObject layout = J.object(J.object(template, "pdf"), "canvas");
    double width = layout.optDouble("width"), height = layout.optDouble("height");
    J.require(
        Double.isFinite(width)
            && Double.isFinite(height)
            && width >= 200
            && width <= 2000
            && height >= 200
            && height <= 2000,
        "Invalid form size.");
    JSONObject background = J.object(layout, "background");
    box(background, width, height);
    String data = background.optString("image");
    J.require(
        data.length() <= 400000 && data.matches("data:image/(jpeg|png);base64,[A-Za-z0-9+/=]+"),
        "Invalid form background.");
    Set<String> fields = new HashSet<>(), weekFields = new HashSet<>(), columns = new HashSet<>();
    for (JSONObject f : J.objects(J.array(template, "fields"))) fields.add(f.optString("key"));
    for (JSONObject f : J.objects(J.array(template, "weekFields")))
      weekFields.add(f.optString("key"));
    for (JSONObject c : J.objects(J.array(template, "columns"))) columns.add(c.optString("key"));
    placements(layout, "fields", fields, width, height);
    placements(layout, "weekFields", weekFields, width, height);
    for (String key : Arrays.asList("signature", "exportDate", "pageNumber"))
      if (layout.has(key)) box(J.object(layout, key), width, height);
    JSONObject table = J.object(layout, "table");
    double y = table.optDouble("y"), rowHeight = table.optDouble("rowHeight");
    J.require(
        Double.isFinite(y)
            && y >= 0
            && Double.isFinite(rowHeight)
            && rowHeight >= 12
            && y + rowHeight * 7 <= height,
        "Invalid form table.");
    List<JSONObject> tableColumns = J.objects(J.array(table, "columns"));
    J.require(
        tableColumns.size() == columns.size() && tableColumns.size() <= 20,
        "Invalid form columns.");
    Set<String> used = new HashSet<>();
    for (JSONObject c : tableColumns) {
      J.require(
          columns.contains(c.optString("key")) && used.add(c.optString("key")),
          "Missing or duplicate form column.");
      JSONObject cell = J.copy(c);
      J.put(cell, "y", y);
      J.put(cell, "height", rowHeight);
      box(cell, width, height);
      J.require(
          !c.has("dateFormat")
              || Arrays.asList("dd.MM", "dd.MM.yyyy").contains(c.optString("dateFormat")),
          "Invalid form date format.");
    }
    box(J.object(table, "total"), width, height);
  }

  private static void placements(
      JSONObject layout, String name, Set<String> keys, double width, double height) {
    JSONArray array = J.array(layout, name);
    J.require(
        array.length() <= 60 && J.objects(array).size() == array.length(), "Invalid form fields.");
    for (JSONObject field : J.objects(array)) {
      J.require(keys.contains(field.optString("key")), "The form refers to a missing field.");
      box(field, width, height);
    }
  }

  private static void box(JSONObject box, double pageWidth, double pageHeight) {
    double x = box.optDouble("x"),
        y = box.optDouble("y"),
        w = box.optDouble("width"),
        h = box.optDouble("height");
    J.require(
        Double.isFinite(x)
            && Double.isFinite(y)
            && Double.isFinite(w)
            && Double.isFinite(h)
            && x >= 0
            && y >= 0
            && w > 0
            && h > 0
            && x + w <= pageWidth + .1
            && y + h <= pageHeight + .1,
        "A field is outside the form.");
    double font = box.optDouble("fontSize", 10);
    J.require(Double.isFinite(font) && font >= 6 && font <= 30, "Invalid form font.");
    J.require(
        Arrays.asList("left", "center", "right").contains(box.optString("align", "left")),
        "Invalid form alignment.");
  }

  /** Keep calendar slots for a simple week; pack repeated-day blocks into seven-row pages. */
  public static List<List<Core.Row>> pages(List<Core.Row> rows, java.time.LocalDate monday) {
    List<List<Core.Row>> days = new ArrayList<>();
    for (int i = 0; i < 7; i++) days.add(new ArrayList<>());
    for (Core.Row row : rows) {
      long index =
          java.time.temporal.ChronoUnit.DAYS.between(monday, java.time.LocalDate.parse(row.dayKey));
      J.require(index >= 0 && index < 7, "An entry is outside the selected week.");
      days.get((int) index).add(row);
    }
    boolean repeatedDay = days.stream().anyMatch(day -> day.size() > 1);
    List<List<Core.Row>> result = new ArrayList<>();
    if (!repeatedDay) {
      List<Core.Row> page = new ArrayList<>();
      for (List<Core.Row> day : days) page.add(day.isEmpty() ? null : day.get(0));
      result.add(page);
      return result;
    }
    List<Core.Row> packed = new ArrayList<>();
    for (List<Core.Row> day : days) {
      day.sort(Comparator.comparing((Core.Row row) -> row.from));
      packed.addAll(day);
    }
    for (int start = 0; start < packed.size(); start += 7) {
      List<Core.Row> page = new ArrayList<>();
      for (int slot = 0; slot < 7; slot++)
        page.add(start + slot < packed.size() ? packed.get(start + slot) : null);
      result.add(page);
    }
    return result;
  }
}
