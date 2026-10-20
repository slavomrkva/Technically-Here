package sk.technicallyhere;

import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import android.util.Base64;
import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.json.*;

/** A generic, flowing renderer: all business labels/layout come from the imported addon. */
public final class AddonPdf implements Closeable {
  private final PdfDocument doc = new PdfDocument();
  private PdfDocument.Page page;
  private Canvas canvas;
  private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint();
  private int pageNumber = 0;
  private final int width, height;
  private final float margin = 34;
  private float y;

  public AddonPdf(boolean landscape) {
    width = landscape ? 842 : 595;
    height = landscape ? 595 : 842;
    ink.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
    newPage();
  }

  private void newPage() {
    if (page != null) doc.finishPage(page);
    page = doc.startPage(new PdfDocument.PageInfo.Builder(width, height, ++pageNumber).create());
    canvas = page.getCanvas();
    canvas.drawColor(Color.WHITE);
    y = margin;
  }

  private void room(float height) {
    if (y + height > this.height - margin) newPage();
  }

  private List<String> wrap(String text, float width, float size) {
    ink.setTextSize(size);
    List<String> lines = new ArrayList<>();
    for (String paragraph : (text == null ? "" : text).split("\n", -1)) {
      if (paragraph.isEmpty()) {
        lines.add("");
        continue;
      }
      String rest = paragraph;
      while (!rest.isEmpty()) {
        int n = ink.breakText(rest, true, Math.max(12, width), null);
        if (n <= 0) n = 1;
        if (n < rest.length()) {
          int space = rest.lastIndexOf(' ', n);
          if (space > n / 2) n = space;
        }
        lines.add(rest.substring(0, n));
        rest = rest.substring(n).trim();
      }
    }
    return lines;
  }

  public void text(String value, float size) {
    for (String line : wrap(value, width - margin * 2, size)) {
      room(size + 5);
      ink.setColor(Color.rgb(28, 25, 20));
      ink.setTextSize(size);
      canvas.drawText(line, margin, y + size, ink);
      y += size + 4;
    }
    y += 6;
  }

  private void cells(List<String> values, boolean heading, float[] widths) {
    List<List<String>> lines = new ArrayList<>();
    int max = 1;
    float font = values.size() > 8 ? 6.5f : 8;
    for (int i = 0; i < values.size(); i++) {
      List<String> list = wrap(values.get(i), widths[i] - 8, font);
      lines.add(list);
      max = Math.max(max, list.size());
    }
    // Long cells flow across pages rather than painting over other sections.
    for (int firstLine = 0; firstLine < max; ) {
      int available = Math.max(1, (int) ((height - margin - y - 10) / (font + 3)));
      if (available < 2 && firstLine == 0) {
        newPage();
        available = (int) ((height - margin - y - 10) / (font + 3));
      }
      int count = Math.min(max - firstLine, available);
      float h = count * (font + 3) + 10;
      room(h);
      float x = margin;
      for (int i = 0; i < values.size(); i++) {
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(heading ? Color.rgb(45, 45, 45) : Color.WHITE);
        canvas.drawRect(x, y, x + widths[i], y + h, fill);
        fill.setStyle(Paint.Style.STROKE);
        fill.setStrokeWidth(.4f);
        fill.setColor(Color.LTGRAY);
        canvas.drawRect(x, y, x + widths[i], y + h, fill);
        ink.setColor(heading ? Color.WHITE : Color.rgb(28, 25, 20));
        ink.setTextSize(font);
        List<String> list = lines.get(i);
        for (int j = 0; j < count && firstLine + j < list.size(); j++)
          canvas.drawText(list.get(firstLine + j), x + 4, y + font + 5 + j * (font + 3), ink);
        x += widths[i];
      }
      y += h;
      firstLine += count;
      if (firstLine < max) newPage();
    }
  }

  public static void write(
      JSONObject addon, List<Core.Row> rows, LocalDate monday, OutputStream out)
      throws IOException {
    JSONObject template = J.object(addon, "template");
    if (J.object(template, "pdf").has("canvas")) {
      FormPdf.write(addon, rows, monday, out);
      return;
    }
    Locale locale = Locale.forLanguageTag(template.optString("locale", "en-GB"));
    JSONArray sections = J.array(J.object(template, "pdf"), "sections");
    if (sections.length() == 0) {
      List<String> fields = new ArrayList<>();
      for (JSONObject f : J.objects(J.array(template, "fields"))) fields.add(f.optString("key"));
      sections =
          J.arr(
              java.util.Arrays.asList(
                  J.obj(
                      "type",
                      "text",
                      "text",
                      template.optString("title", template.optString("name")),
                      "size",
                      14),
                  J.obj("type", "fields", "keys", J.arr(fields), "columns", 2),
                  J.obj("type", "table", "totalLabel", "Total"),
                  J.obj("type", "weekFields")));
      if (template.optBoolean("signature"))
        sections.put(J.obj("type", "signature", "label", "Signature", "dateLabel", "Date"));
    }
    try (AddonPdf pdf =
        new AddonPdf(J.object(template, "pdf").optString("orientation").equals("landscape"))) {
      for (JSONObject section : J.objects(sections))
        switch (section.optString("type")) {
          case "text":
            pdf.text(
                section.optString("text"),
                (float) Math.max(6, Math.min(24, section.optDouble("size", 12))));
            break;
          case "fields":
            {
              if (!section.optString("title").isEmpty()) pdf.text(section.optString("title"), 10);
              List<String> keys = J.strings(J.array(section, "keys"));
              int columns = section.optInt("columns", 2);
              for (int i = 0; i < keys.size(); i += columns) {
                List<String> values = new ArrayList<>();
                for (int j = 0; j < columns; j++) {
                  JSONObject f = null;
                  if (i + j < keys.size())
                    for (JSONObject candidate : J.objects(J.array(template, "fields")))
                      if (candidate.optString("key").equals(keys.get(i + j))) f = candidate;
                  values.add(
                      f == null ? "" : f.optString("label") + "\n" + Core.fieldValue(addon, f));
                }
                float[] widths = new float[columns];
                Arrays.fill(widths, (pdf.width - pdf.margin * 2) / columns);
                pdf.cells(values, false, widths);
              }
              pdf.y += 8;
              break;
            }
          case "table":
            {
              List<JSONObject> columns = J.objects(J.array(template, "columns"));
              float[] widths = new float[columns.size()];
              Arrays.fill(widths, (pdf.width - pdf.margin * 2) / columns.size());
              List<String> headers = new ArrayList<>();
              for (JSONObject c : columns) headers.add(c.optString("label"));
              pdf.cells(headers, true, widths);
              long sum = 0, pause = 0;
              for (Core.Row row : rows) {
                List<String> values = new ArrayList<>();
                for (JSONObject c : columns) values.add(row.cell(c, locale));
                float before = pdf.y;
                pdf.room(28);
                if (pdf.y < before) pdf.cells(headers, true, widths);
                pdf.cells(values, false, widths);
                sum += row.net;
                pause += row.pause;
              }
              List<String> foot = new ArrayList<>();
              boolean label = false;
              for (JSONObject c : columns) {
                String source = c.optString("source");
                if (source.equals("hours")) foot.add(Core.decimal(sum, locale));
                else if (source.equals("pause")) foot.add(String.valueOf(pause / 60000));
                else if (!label) {
                  foot.add(section.optString("totalLabel", "Total"));
                  label = true;
                } else foot.add("");
              }
              pdf.cells(foot, true, widths);
              pdf.y += 10;
              break;
            }
          case "weekFields":
            {
              JSONObject values = J.object(J.object(addon, "weeks"), Core.weekKey(monday));
              for (JSONObject field : J.objects(J.array(template, "weekFields"))) {
                pdf.text(field.optString("label"), 10);
                pdf.cells(
                    java.util.Arrays.asList(
                        values.optString(field.optString("key"), field.optString("default"))),
                    false,
                    new float[] {pdf.width - pdf.margin * 2});
                pdf.y += 8;
              }
              break;
            }
          case "signature":
            if (template.optBoolean("signature")) {
              pdf.room(100);
              float span = (pdf.width - pdf.margin * 2) / 3;
              String signature = addon.optString("signature");
              if (signature.startsWith("data:image/png;base64,"))
                try {
                  byte[] bytes =
                      Base64.decode(
                          signature.substring(signature.indexOf(',') + 1), Base64.DEFAULT);
                  BitmapFactory.Options options = new BitmapFactory.Options();
                  options.inJustDecodeBounds = true;
                  BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                  if (options.outWidth > 0
                      && options.outHeight > 0
                      && options.outWidth * 1L * options.outHeight <= 4_000_000) {
                    Bitmap image = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (image != null) {
                      pdf.canvas.drawBitmap(
                          image,
                          null,
                          new RectF(
                              pdf.margin + span, pdf.y, pdf.margin + span * 2 - 8, pdf.y + 36),
                          pdf.ink);
                      image.recycle();
                    }
                  }
                } catch (Exception ignored) {
                }
              pdf.y += 42;
              pdf.ink.setColor(Color.DKGRAY);
              pdf.ink.setStrokeWidth(.5f);
              for (int i = 0; i < 3; i++)
                if (i < 2 || section.has("otherLabel"))
                  pdf.canvas.drawLine(
                      pdf.margin + i * span,
                      pdf.y,
                      pdf.margin + (i + 1) * span - 8,
                      pdf.y,
                      pdf.ink);
              List<String> captions =
                  java.util.Arrays.asList(
                      section.optString("dateLabel", "Date"),
                      section.optString("label", "Signature"),
                      section.optString("otherLabel"));
              float oldY = pdf.y;
              for (int i = 0; i < 3; i++) {
                pdf.ink.setTextSize(7);
                int n = 0;
                for (String line : pdf.wrap(captions.get(i), span - 8, 7)) {
                  pdf.canvas.drawText(line, pdf.margin + i * span, oldY + 12 + n * 9, pdf.ink);
                  n++;
                }
              }
              pdf.y = oldY + 32;
              pdf.text(
                  LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
                      + "     "
                      + J.object(addon, "values").optString(section.optString("nameField")),
                  9);
              break;
            }
            break;
          default:
            break;
        }
      pdf.doc.finishPage(pdf.page);
      pdf.page = null;
      pdf.doc.writeTo(out);
    }
  }

  @Override
  public void close() {
    if (page != null) doc.finishPage(page);
    doc.close();
  }
}
