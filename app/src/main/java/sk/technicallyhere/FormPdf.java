package sk.technicallyhere;

import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import android.util.Base64;
import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.json.*;

/** Draws the addon's blank form and values without any app-specific company/form code. */
public final class FormPdf {
  private FormPdf() {}

  private static Bitmap image(String data) throws IOException {
    byte[] bytes;
    try {
      bytes = Base64.decode(data.substring(data.indexOf(',') + 1), Base64.DEFAULT);
    } catch (Exception e) {
      throw new IOException("Invalid form image.", e);
    }
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
    if (bounds.outWidth <= 0
        || bounds.outHeight <= 0
        || (long) bounds.outWidth * bounds.outHeight > 4000000)
      throw new IOException("The form image is invalid or too large.");
    Bitmap result = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    if (result == null) throw new IOException("Unable to load the image.");
    return result;
  }

  private static RectF rectangle(JSONObject box) {
    float x = (float) box.optDouble("x"), y = (float) box.optDouble("y");
    return new RectF(x, y, x + (float) box.optDouble("width"), y + (float) box.optDouble("height"));
  }

  private static List<String> wrap(String value, Paint paint, float width) {
    List<String> lines = new ArrayList<>();
    for (String paragraph : value.replace("\r", "").split("\n", -1)) {
      if (paragraph.isEmpty()) {
        lines.add("");
        continue;
      }
      while (!paragraph.isEmpty()) {
        int n = paint.breakText(paragraph, true, width, null);
        if (n <= 0) {
          lines.add(paragraph);
          break;
        }
        if (n < paragraph.length()) {
          int space = paragraph.lastIndexOf(' ', n);
          if (space > 0) n = space;
        }
        lines.add(paragraph.substring(0, n));
        paragraph = paragraph.substring(n).trim();
      }
    }
    return lines;
  }

  private static void text(Canvas canvas, JSONObject box, String value) throws IOException {
    RectF rect = rectangle(box);
    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
    if (box.optBoolean("erase")) {
      paint.setColor(Color.WHITE);
      canvas.drawRect(rect, paint);
    }
    if (value == null || value.isEmpty()) return;
    paint.setColor(Color.BLACK);
    float size = (float) box.optDouble("fontSize", 10);
    List<String> lines;
    while (true) {
      paint.setTextSize(size);
      lines = wrap(value, paint, rect.width() - 2);
      boolean fits = lines.size() * paint.getFontSpacing() <= rect.height();
      for (String line : lines) if (paint.measureText(line) > rect.width() - 2) fits = false;
      if (fits) break;
      if (size <= 6) throw new IOException("The text does not fit in the form field. Shorten it.");
      size = Math.max(6, size - .25f);
    }
    Paint.FontMetrics metrics = paint.getFontMetrics();
    float baseline =
        rect.top + (rect.height() - lines.size() * paint.getFontSpacing()) / 2 - metrics.ascent;
    for (String line : lines) {
      float x = rect.left + 1, measured = paint.measureText(line);
      if (box.optString("align").equals("center")) x = rect.centerX() - measured / 2;
      else if (box.optString("align").equals("right")) x = rect.right - measured - 1;
      canvas.drawText(line, x, baseline, paint);
      baseline += paint.getFontSpacing();
    }
  }

  private static String cell(Core.Row row, JSONObject column, JSONObject layout, Locale locale) {
    if (column.optString("source").equals("date") && layout.has("dateFormat"))
      return LocalDate.parse(row.dayKey)
          .format(DateTimeFormatter.ofPattern(layout.optString("dateFormat"), locale));
    if (column.optString("source").equals("hours") && layout.optBoolean("trimZeros"))
      return new java.text.DecimalFormat("0.##", java.text.DecimalFormatSymbols.getInstance(locale))
          .format(row.net / 3600000.0);
    return row.cell(column, locale);
  }

  /** Same Canvas path for PDF pages and native rendering checks. */
  static void renderPage(
      Canvas canvas, JSONObject addon, List<Core.Row> rows, LocalDate monday, int pageIndex)
      throws IOException {
    JSONObject template = J.object(addon, "template"),
        layout = J.object(J.object(template, "pdf"), "canvas"),
        background = J.object(layout, "background"),
        table = J.object(layout, "table");
    Locale locale = Locale.forLanguageTag(template.optString("locale", "en-GB"));
    List<List<Core.Row>> pages = FormLayout.pages(rows, monday);
    Bitmap backdrop = image(background.optString("image")), signature = null;
    try {
      if (template.optBoolean("signature") && !addon.optString("signature").isEmpty())
        signature = image(addon.optString("signature"));
      canvas.drawColor(Color.WHITE);
      canvas.drawBitmap(backdrop, null, rectangle(background), null);
      for (JSONObject box : J.objects(J.array(layout, "fields"))) {
        String key = box.optString("key"), value = "";
        for (JSONObject field : J.objects(J.array(template, "fields")))
          if (field.optString("key").equals(key)) value = Core.fieldValue(addon, field);
        text(canvas, box, value);
      }
      JSONObject week = J.object(J.object(addon, "weeks"), Core.weekKey(monday));
      for (JSONObject box : J.objects(J.array(layout, "weekFields")))
        text(canvas, box, week.optString(box.optString("key")));
      for (int day = 0; day < 7; day++) {
        Core.Row row = pages.get(pageIndex).get(day);
        if (row == null) continue;
        for (JSONObject columnLayout : J.objects(J.array(table, "columns"))) {
          JSONObject definition = null;
          for (JSONObject c : J.objects(J.array(template, "columns")))
            if (c.optString("key").equals(columnLayout.optString("key"))) definition = c;
          JSONObject box = J.copy(columnLayout);
          J.put(box, "y", table.optDouble("y") + day * table.optDouble("rowHeight"));
          J.put(box, "height", table.optDouble("rowHeight"));
          text(canvas, box, cell(row, definition, columnLayout, locale));
        }
      }
      if (pageIndex == pages.size() - 1) {
        long total = 0;
        for (Core.Row r : rows) total += r.net;
        text(
            canvas,
            J.object(table, "total"),
            new java.text.DecimalFormat("0.##", java.text.DecimalFormatSymbols.getInstance(locale))
                .format(total / 3600000.0));
      }
      if (layout.has("signature") && signature != null) {
        RectF destination = rectangle(J.object(layout, "signature"));
        float factor =
            Math.min(
                destination.width() / signature.getWidth(),
                destination.height() / signature.getHeight());
        RectF fit =
            new RectF(
                destination.left,
                destination.top,
                destination.left + signature.getWidth() * factor,
                destination.top + signature.getHeight() * factor);
        canvas.drawBitmap(signature, null, fit, null);
      }
      if (layout.has("exportDate"))
        text(
            canvas,
            J.object(layout, "exportDate"),
            LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy")));
      if (pages.size() > 1 && layout.has("pageNumber"))
        text(canvas, J.object(layout, "pageNumber"), (pageIndex + 1) + " / " + pages.size());
    } finally {
      backdrop.recycle();
      if (signature != null) signature.recycle();
    }
  }

  public static void write(
      JSONObject addon, List<Core.Row> rows, LocalDate monday, OutputStream out)
      throws IOException {
    JSONObject template = J.object(addon, "template");
    FormLayout.validate(template);
    JSONObject layout = J.object(J.object(template, "pdf"), "canvas");
    List<List<Core.Row>> pages = FormLayout.pages(rows, monday);
    PdfDocument document = new PdfDocument();
    try {
      for (int p = 0; p < pages.size(); p++) {
        PdfDocument.Page page =
            document.startPage(
                new PdfDocument.PageInfo.Builder(
                        layout.optInt("width"), layout.optInt("height"), p + 1)
                    .create());
        try {
          renderPage(page.getCanvas(), addon, rows, monday, p);
        } finally {
          document.finishPage(page);
        }
      }
      document.writeTo(out);
    } finally {
      document.close();
    }
  }
}
