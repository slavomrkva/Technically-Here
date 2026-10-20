package sk.technicallyhere;

import java.util.*;
import org.json.*;

public final class J {
  private J() {}

  public static JSONObject obj(Object... pairs) {
    JSONObject o = new JSONObject();
    for (int i = 0; i < pairs.length; i += 2) put(o, (String) pairs[i], pairs[i + 1]);
    return o;
  }

  public static void put(JSONObject o, String key, Object value) {
    try {
      o.put(key, value == null ? JSONObject.NULL : value);
    } catch (JSONException e) {
      throw new IllegalArgumentException(e);
    }
  }

  public static JSONArray array(JSONObject o, String key) {
    JSONArray a = o.optJSONArray(key);
    return a == null ? new JSONArray() : a;
  }

  public static JSONObject object(JSONObject o, String key) {
    JSONObject v = o.optJSONObject(key);
    return v == null ? new JSONObject() : v;
  }

  public static List<JSONObject> objects(JSONArray a) {
    List<JSONObject> out = new ArrayList<>();
    for (int i = 0; i < a.length(); i++)
      if (a.optJSONObject(i) != null) out.add(a.optJSONObject(i));
    return out;
  }

  public static List<String> strings(JSONArray a) {
    List<String> out = new ArrayList<>();
    for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
    return out;
  }

  public static JSONArray arr(Collection<?> values) {
    return new JSONArray(values);
  }

  public static JSONObject parse(String s) {
    try {
      return new JSONObject(s);
    } catch (JSONException e) {
      throw new IllegalArgumentException("Invalid JSON.", e);
    }
  }

  public static JSONObject copy(JSONObject o) {
    return parse(o.toString());
  }

  public static String id() {
    return UUID.randomUUID().toString();
  }

  public static long end(JSONObject e, long now) {
    return e.isNull("end") ? now : e.optLong("end");
  }

  public static JSONObject find(JSONArray a, String id) {
    for (JSONObject o : objects(a)) if (o.optString("id").equals(id)) return o;
    return null;
  }

  public static void require(boolean good, String message) {
    if (!good) throw new IllegalArgumentException(message);
  }
}
