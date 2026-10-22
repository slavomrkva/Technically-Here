package sk.technicallyhere;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;
import org.json.JSONObject;

/** Recovery never enables monitoring that the user has stopped. */
final class TrackingRecovery {
  static final long GAP_MS = 5 * 60_000L;

  static boolean hasLocation(Context context) {
    return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            == PackageManager.PERMISSION_GRANTED;
  }

  static boolean backgroundAllowed(Context context) {
    return hasLocation(context)
        && (Build.VERSION.SDK_INT < 29
            || context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED);
  }

  static boolean batteryUnrestricted(Context context) {
    PowerManager power = context.getSystemService(PowerManager.class);
    return power != null && power.isIgnoringBatteryOptimizations(context.getPackageName());
  }

  static void resume(Context context, boolean visible) {
    Repository repo = Repository.get(context);
    if (repo.loadError != null || !repo.snapshot().optBoolean("monitorWanted")) return;
    if (TrackingService.active != null) return;
    if (!hasLocation(context) || (!visible && !backgroundAllowed(context))) {
      repo.sensorStatus = "Recovery needs background location permission. Open the app settings.";
      repo.notifyChanged();
      return;
    }
    try {
      context.startForegroundService(new Intent(context, TrackingService.class));
    } catch (RuntimeException e) {
      repo.sensorStatus = "Android blocked recovery. Start tracking in the app.";
      repo.notifyChanged();
    }
  }

  /** A heartbeat is evidence of evaluation, not proof that GPS was available. */
  static void heartbeat(JSONObject state, long now) {
    long previous = state.optLong("trackingHeartbeat");
    if (state.optBoolean("monitorWanted") && previous > 0 && now - previous > GAP_MS) {
      J.put(state, "trackingGapFrom", previous);
      J.put(state, "trackingGapTo", now);
      J.put(state, "trackingGapCount", state.optInt("trackingGapCount") + 1);
    }
    J.put(state, "trackingHeartbeat", now);
  }

  static void stopped(JSONObject state) {
    state.remove("automationPending");
    J.put(state, "monitorWanted", false);
    J.put(state, "trackingHeartbeat", 0);
  }
}
