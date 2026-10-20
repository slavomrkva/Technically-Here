package sk.technicallyhere;

import android.content.*;

/** Credential-protected data: resume after the first unlock following reboot. */
public final class BootReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    String action = intent == null ? null : intent.getAction();
    if (Intent.ACTION_BOOT_COMPLETED.equals(action)
        || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
      TrackingRecovery.resume(context, false);
    }
  }
}
