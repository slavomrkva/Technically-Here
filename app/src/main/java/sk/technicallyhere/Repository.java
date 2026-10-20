package sk.technicallyhere;

import android.content.Context;
import android.os.*;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;
import org.json.*;

public final class Repository {
  private static Repository singleton;

  public static synchronized Repository get(Context context) {
    if (singleton == null) singleton = new Repository(context.getApplicationContext());
    return singleton;
  }

  private final AtomicFile file;
  private JSONObject state;
  public volatile String loadError = null;
  public volatile String sensorStatus = "Tracking is off.";
  public final Core.Signals signals = new Core.Signals();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final CopyOnWriteArraySet<Runnable> listeners = new CopyOnWriteArraySet<>();

  private Repository(Context context) {
    file = new AtomicFile(new File(context.getFilesDir(), "attendance.json"));
    state = Core.defaults();
    if (file.getBaseFile().exists())
      try (FileInputStream in = file.openRead()) {
        state = J.parse(new String(readLimited(in, 20_000_000), StandardCharsets.UTF_8));
        Core.validate(state, System.currentTimeMillis());
        if (Core.upgradeWifiLunchGrace(state)) write(state);
      } catch (Exception e) {
        state = Core.defaults();
        loadError =
            "Unable to load saved data. The original file has been preserved. Restore a backup or"
                + " explicitly delete the data.";
      }
  }

  public synchronized JSONObject snapshot() {
    return J.copy(state);
  }

  public synchronized void change(Consumer<JSONObject> operation) {
    if (loadError != null) throw new IllegalStateException(loadError);
    JSONObject next = J.copy(state);
    operation.accept(next);
    Core.validate(next, System.currentTimeMillis());
    if (!next.toString().equals(state.toString())) write(next);
  }

  public synchronized void restore(JSONObject next) {
    Core.validate(next, System.currentTimeMillis());
    Core.upgradeWifiLunchGrace(next);
    J.put(next, "auto", false);
    J.put(next, "monitorWanted", false);
    J.put(next, "trackingHeartbeat", 0);
    J.put(next, "hold", true);
    write(J.copy(next));
    loadError = null;
  }

  public synchronized void reset() {
    write(Core.defaults());
    loadError = null;
  }

  private void write(JSONObject next) {
    FileOutputStream stream = null;
    try {
      stream = file.startWrite();
      stream.write(next.toString().getBytes(StandardCharsets.UTF_8));
      file.finishWrite(stream);
      state = next;
      notifyChanged();
    } catch (Exception e) {
      if (stream != null) file.failWrite(stream);
      throw new IllegalStateException(
          "Unable to save data. Free up storage and create a backup.", e);
    }
  }

  public void subscribe(Runnable listener) {
    listeners.add(listener);
  }

  public void unsubscribe(Runnable listener) {
    listeners.remove(listener);
  }

  public void notifyChanged() {
    main.post(
        () -> {
          for (Runnable listener : listeners) listener.run();
        });
  }

  public synchronized JSONObject backup() {
    if (loadError != null) throw new IllegalStateException(loadError);
    return J.obj(
        "format",
        "technically-here-backup",
        "version",
        1,
        "createdAt",
        java.time.Instant.now().toString(),
        "data",
        J.copy(state));
  }

  public static byte[] readLimited(InputStream in, int maximum) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int n;
    while ((n = in.read(buffer)) != -1) {
      if (out.size() + n > maximum) throw new IOException("The file is too large.");
      out.write(buffer, 0, n);
    }
    return out.toByteArray();
  }
}
