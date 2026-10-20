package sk.technicallyhere;

import java.time.LocalDate;
import java.util.*;
import org.json.*;

/** Self-contained UTF-8 Markdown report, using the same declarative sections as PDF. */
public final class ReportMarkdown {
  private ReportMarkdown() {}

  private static String escape(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("|", "\\|")
        .replace("*", "\\*")
        .replace("_", "\\_")
        .replace("[", "\\[")
        .replace("]", "\\]")
        .replace("`", "\\`")
        .replace("#", "\\#")
        .replace("!", "\\!")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace("\n", "<br>");
  }

  private static void row(StringBuilder out, List<String> cells) {
    out.append("| ");
    for (String cell : cells) out.append(escape(cell)).append(" | ");
    out.append('\n');
  }

  public static String write(JSONObject addon, List<Core.Row> rows, LocalDate first) {
    JSONObject t = J.object(addon, "template");
    Locale locale = Locale.forLanguageTag(t.optString("locale", "en-GB"));
    StringBuilder out =
        new StringBuilder("# ")
            .append(escape(t.optString("title", t.optString("name"))))
            .append("\n\n")
            .append(first)
            .append("\n\n");
    JSONArray sections = J.array(J.object(t, "pdf"), "sections");
    if (sections.length() == 0) {
      List<String> keys = new ArrayList<>();
      for (JSONObject f : J.objects(J.array(t, "fields"))) keys.add(f.optString("key"));
      sections =
          J.arr(
              Arrays.asList(
                  J.obj("type", "fields", "keys", J.arr(keys)),
                  J.obj("type", "table", "totalLabel", "Total"),
                  J.obj("type", "weekFields")));
      if (t.optBoolean("signature")) sections.put(J.obj("type", "signature", "label", "Signature"));
    }
    for (JSONObject section : J.objects(sections)) {
      switch (section.optString("type")) {
        case "text":
          out.append(escape(section.optString("text"))).append("\n\n");
          break;
        case "fields":
          if (!section.optString("title").isEmpty())
            out.append("## ").append(escape(section.optString("title"))).append("\n\n");
          for (String key : J.strings(J.array(section, "keys")))
            for (JSONObject f : J.objects(J.array(t, "fields")))
              if (f.optString("key").equals(key))
                out.append("**")
                    .append(escape(f.optString("label")))
                    .append(":** ")
                    .append(escape(Core.fieldValue(addon, f)))
                    .append("\n\n");
          break;
        case "table":
          List<JSONObject> columns = J.objects(J.array(t, "columns"));
          List<String> headers = new ArrayList<>();
          for (JSONObject c : columns) headers.add(c.optString("label"));
          row(out, headers);
          out.append("|");
          for (JSONObject c : columns) out.append(" --- |");
          out.append('\n');
          long sum = 0, pause = 0;
          for (Core.Row r : rows) {
            List<String> cells = new ArrayList<>();
            for (JSONObject c : columns) cells.add(r.cell(c, locale));
            row(out, cells);
            sum += r.net;
            pause += r.pause;
          }
          List<String> total = new ArrayList<>();
          boolean label = false;
          for (JSONObject c : columns) {
            String source = c.optString("source");
            if (source.equals("hours")) total.add(Core.decimal(sum, locale));
            else if (source.equals("pause")) total.add(String.valueOf(pause / 60000));
            else if (!label) {
              total.add(section.optString("totalLabel", "Total"));
              label = true;
            } else total.add("");
          }
          row(out, total);
          out.append('\n');
          break;
        case "weekFields":
          JSONObject values = J.object(J.object(addon, "weeks"), Core.weekKey(first));
          for (JSONObject f : J.objects(J.array(t, "weekFields")))
            out.append("**")
                .append(escape(f.optString("label")))
                .append(":** ")
                .append(escape(values.optString(f.optString("key"), f.optString("default"))))
                .append("\n\n");
          break;
        case "signature":
          if (t.optBoolean("signature")) {
            out.append("**")
                .append(escape(section.optString("label", "Signature")))
                .append("**\n\n");
            String signature = addon.optString("signature");
            if (signature.matches("data:image/png;base64,[A-Za-z0-9+/=]+"))
              out.append("![Podpis](").append(signature).append(")\n\n");
            out.append(escape(section.optString("dateLabel", "Date")))
                .append(": ")
                .append(LocalDate.now())
                .append("\n\n");
            String name = J.object(addon, "values").optString(section.optString("nameField"));
            if (!name.isEmpty()) out.append(escape(name)).append("\n\n");
          }
          break;
        default:
          break;
      }
    }
    return out.toString();
  }
}
