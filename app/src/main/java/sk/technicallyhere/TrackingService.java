package sk.technicallyhere;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.*;
import android.location.*;
import android.net.*;
import android.net.wifi.*;
import android.os.*;
import java.util.*;
import org.json.*;

/**
 * User-started location foreground service. No WebView, server, polling website or Internet
 * permission.
 */
public final class TrackingService extends Service {
  public static final String STOP = "sk.technicallyhere.STOP";
  public static volatile TrackingService active;
  private Repository repo;
  private LocationManager location;
  private ConnectivityManager networks;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Map<String, Long> pending = new HashMap<>();
  private final Map<Network, String> wifiNetworks = new HashMap<>();
  private final Map<String, String> connected = new HashMap<>();
  private BluetoothAdapter adapter;
  private final Map<Integer, Set<String>> profileAddresses = new HashMap<>();
  private final Map<Integer, BluetoothProfile> profiles = new HashMap<>();
  private ConnectivityManager.NetworkCallback networkCallback;
  private boolean receiverRegistered = false;
  private long lastTick = 0;
  private long lastHeartbeatElapsed = -60_000;
  private boolean failed = false;
  private final Runnable tick =
      new Runnable() {
        public void run() {
          evaluate();
          handler.postDelayed(this, 15000);
        }
      };
  private final LocationListener locationListener =
      new LocationListener() {
        public void onLocationChanged(Location l) {
          repo.signals.lat = l.getLatitude();
          repo.signals.lng = l.getLongitude();
          repo.signals.accuracy = l.getAccuracy();
          repo.signals.fixAt = l.getTime();
          repo.signals.locationValid = true;
          evaluate();
        }

        public void onProviderDisabled(String p) {
          if (location != null
              && !location.isProviderEnabled(LocationManager.GPS_PROVIDER)
              && !location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            repo.signals.locationValid = false;
            evaluate();
          }
        }
      };

  public static void recheck() {
    TrackingService service = active;
    if (service != null) service.handler.post(service::evaluate);
  }

  @Override
  public void onCreate() {
    super.onCreate();
    repo = Repository.get(this);
    active = this;
    notifications(this);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && STOP.equals(intent.getAction())) {
      try {
        repo.change(TrackingRecovery::stopped);
      } catch (Exception ignored) {
      }
      stopSelf();
      return START_NOT_STICKY;
    }
    // A stale sticky restart must not undo an explicit Stop or a restored backup.
    if (intent == null && !repo.snapshot().optBoolean("monitorWanted")) {
      stopSelf();
      return START_NOT_STICKY;
    }
    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
      fail("Tracking needs location permission. Open the app to allow it.");
      return START_NOT_STICKY;
    }
    try {
      Notification notification = notification("Tracking GPS and Wi-Fi in the background.");
      if (Build.VERSION.SDK_INT >= 29)
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
      else startForeground(1, notification);
      repo.change(s -> J.put(s, "monitorWanted", true));
      if (location == null) {
        startSensors();
        handler.post(tick);
      }
      repo.sensorStatus = "Background tracking is running.";
      repo.notifyChanged();
      return START_STICKY;
    } catch (Exception e) {
      fail("Unable to start tracking: " + e.getMessage());
      return START_NOT_STICKY;
    }
  }

  private void fail(String message) {
    failed = true;
    repo.sensorStatus = message;
    repo.notifyChanged();
    stopSelf();
  }

  @SuppressLint("MissingPermission")
  private void startSensors() {
    location = getSystemService(LocationManager.class);
    for (String provider :
        java.util.Arrays.asList(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER))
      try {
        if (location.getAllProviders().contains(provider))
          location.requestLocationUpdates(
              provider, 30000, 10, locationListener, Looper.getMainLooper());
      } catch (SecurityException ignored) {
      }
    networks = getSystemService(ConnectivityManager.class);
    networkCallback =
        Build.VERSION.SDK_INT >= 31
            ? new WifiCallback(NetworkCallbackFlags.INCLUDE_LOCATION)
            : new WifiCallback();
    try {
      networks.registerNetworkCallback(
          new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
          networkCallback,
          handler);
      handler.postDelayed(this::readInitialWifi, 2500);
    } catch (Exception e) {
      repo.signals.wifiKnown = false;
    }
    BluetoothManager manager = getSystemService(BluetoothManager.class);
    adapter = manager == null ? null : manager.getAdapter();
    IntentFilter filter = new IntentFilter();
    filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
    filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
    filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
    if (Build.VERSION.SDK_INT >= 33)
      registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED);
    else registerReceiver(bluetoothReceiver, filter);
    receiverRegistered = true;
    if (canBluetooth() && adapter != null) {
      if (!adapter.isEnabled()) {
        repo.signals.btKnown = true;
      } else {
        try {
          for (BluetoothDevice device : manager.getConnectedDevices(BluetoothProfile.GATT))
            connected.put(device.getAddress(), safeName(device));
        } catch (Exception ignored) {
        }
        for (int profile : java.util.Arrays.asList(BluetoothProfile.A2DP, BluetoothProfile.HEADSET))
          try {
            adapter.getProfileProxy(this, profileListener, profile);
          } catch (Exception ignored) {
          }
      }
    }
  }

  private static final class NetworkCallbackFlags {
    static final int INCLUDE_LOCATION = 1;
  }

  private final class WifiCallback extends ConnectivityManager.NetworkCallback {
    WifiCallback() {
      super();
    }

    @android.annotation.TargetApi(31)
    WifiCallback(int flags) {
      super(flags);
    }

    @Override
    public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
      String ssid = "";
      boolean known = false;
      if (Build.VERSION.SDK_INT >= 29 && capabilities.getTransportInfo() instanceof WifiInfo) {
        ssid = ((WifiInfo) capabilities.getTransportInfo()).getSSID();
        known = validSsid(ssid);
      }
      if (!known) {
        String fallback = legacySsid();
        if (validSsid(fallback)) {
          ssid = fallback;
          known = true;
        }
      }
      wifiNetworks.put(network, known ? cleanSsid(ssid) : null);
      updateWifi();
    }

    @Override
    public void onLost(Network network) {
      wifiNetworks.remove(network);
      updateWifi();
    }

    @Override
    public void onUnavailable() {
      wifiNetworks.clear();
      updateWifi();
    }
  }

  @SuppressLint("MissingPermission")
  private String legacySsid() {
    try {
      WifiManager manager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
      return manager == null ? "" : manager.getConnectionInfo().getSSID();
    } catch (Exception e) {
      return "";
    }
  }

  private static boolean validSsid(String s) {
    return s != null && !s.isEmpty() && !s.equals("<unknown ssid>") && !s.equals("0x");
  }

  private static String cleanSsid(String s) {
    return s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
  }

  private void readInitialWifi() {
    // Outside callback: initialize an observed disconnected state if no Wi-Fi exists.
    if (!wifiNetworks.isEmpty()) return;
    try {
      boolean any = false;
      for (Network n : networks.getAllNetworks()) {
        NetworkCapabilities c = networks.getNetworkCapabilities(n);
        if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
          wifiNetworks.put(n, null);
          any = true;
        }
      }
      if (any) {
        String fallback = legacySsid();
        if (validSsid(fallback))
          for (Network n : new ArrayList<>(wifiNetworks.keySet()))
            wifiNetworks.put(n, cleanSsid(fallback));
      }
      updateWifi();
    } catch (Exception e) {
      repo.signals.wifiKnown = false;
    }
  }

  private void updateWifi() {
    repo.signals.wifiKnown = wifiNetworks.isEmpty();
    repo.signals.wifi = "";
    for (String ssid : wifiNetworks.values())
      if (ssid != null) {
        repo.signals.wifiKnown = true;
        repo.signals.wifi = ssid;
        break;
      }
    evaluate();
  }

  private boolean canBluetooth() {
    return Build.VERSION.SDK_INT < 31
        || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            == PackageManager.PERMISSION_GRANTED;
  }

  @SuppressLint("MissingPermission")
  private String safeName(BluetoothDevice d) {
    try {
      return d.getName() == null ? d.getAddress() : d.getName();
    } catch (Exception e) {
      return "";
    }
  }

  private final BluetoothProfile.ServiceListener profileListener =
      new BluetoothProfile.ServiceListener() {
        @SuppressLint("MissingPermission")
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
          profiles.put(profile, proxy);
          Set<String> addresses = new HashSet<>();
          try {
            for (BluetoothDevice d : proxy.getConnectedDevices()) {
              addresses.add(d.getAddress());
              connected.put(d.getAddress(), safeName(d));
            }
            profileAddresses.put(profile, addresses);
            repo.signals.btKnown =
                profileAddresses.containsKey(BluetoothProfile.A2DP)
                    && profileAddresses.containsKey(BluetoothProfile.HEADSET);
            updateBluetooth();
          } catch (SecurityException e) {
            repo.signals.btKnown = false;
          }
        }

        public void onServiceDisconnected(int profile) {
          profiles.remove(profile);
          profileAddresses.remove(profile);
          repo.signals.btKnown = false;
          evaluate();
        }
      };
  private final BroadcastReceiver bluetoothReceiver =
      new BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        @Override
        public void onReceive(Context context, Intent intent) {
          if (!canBluetooth()) {
            repo.signals.btKnown = false;
            return;
          }
          String action = intent.getAction();
          if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
            int next = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (next == BluetoothAdapter.STATE_OFF) {
              connected.clear();
              repo.signals.btKnown = true;
            } else if (next == BluetoothAdapter.STATE_ON) {
              repo.signals.btKnown = false;
              for (int profile :
                  java.util.Arrays.asList(BluetoothProfile.A2DP, BluetoothProfile.HEADSET))
                try {
                  adapter.getProfileProxy(TrackingService.this, profileListener, profile);
                } catch (Exception ignored) {
                }
            }
          } else {
            BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (d == null) return;
            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action))
              connected.put(d.getAddress(), safeName(d));
            else connected.remove(d.getAddress());
            repo.signals.btKnown = true;
          }
          updateBluetooth();
        }
      };

  private void updateBluetooth() {
    repo.signals.bluetooth.clear();
    repo.signals.bluetooth.addAll(connected.keySet());
    repo.signals.bluetooth.addAll(connected.values());
    evaluate();
  }

  private void evaluate() {
    if (active != this) return;
    long now = System.currentTimeMillis();
    if (lastTick != 0 && now - lastTick > 90000) pending.clear();
    lastTick = now;
    try {
      long elapsed = SystemClock.elapsedRealtime();
      boolean checkpoint = elapsed - lastHeartbeatElapsed >= 60_000;
      repo.change(
          s -> {
            Core.evaluate(s, repo.signals, pending, now);
            if (checkpoint) TrackingRecovery.heartbeat(s, now);
          });
      if (checkpoint) lastHeartbeatElapsed = elapsed;
    } catch (Exception e) {
      repo.sensorStatus = "Automation paused: " + e.getMessage();
      repo.notifyChanged();
    }
  }

  static void notifications(Context context) {
    NotificationManager n = context.getSystemService(NotificationManager.class);
    NotificationChannel channel =
        new NotificationChannel(
            "attendance", "Time tracking service", NotificationManager.IMPORTANCE_LOW);
    channel.setSound(null, null);
    channel.enableVibration(false);
    channel.setShowBadge(false);
    n.createNotificationChannel(channel);
  }

  private Notification notification(String text) {
    PendingIntent open =
        PendingIntent.getActivity(
            this,
            0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    PendingIntent stop =
        PendingIntent.getService(
            this,
            1,
            new Intent(this, TrackingService.class).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    return new Notification.Builder(this, "attendance")
        .setSmallIcon(R.drawable.ic_clock)
        .setContentTitle("Technically Here")
        .setContentText(text)
        .setContentIntent(open)
        .setCategory(Notification.CATEGORY_SERVICE)
        .setLocalOnly(true)
        .setShowWhen(false)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .addAction(new Notification.Action.Builder(null, "Stop tracking", stop).build())
        .build();
  }

  @Override
  public void onDestroy() {
    handler.removeCallbacksAndMessages(null);
    if (location != null) {
      try {
        location.removeUpdates(locationListener);
      } catch (SecurityException ignored) {
      }
    }
    if (networks != null && networkCallback != null)
      try {
        networks.unregisterNetworkCallback(networkCallback);
      } catch (Exception ignored) {
      }
    if (receiverRegistered)
      try {
        unregisterReceiver(bluetoothReceiver);
      } catch (Exception ignored) {
      }
    if (adapter != null && canBluetooth())
      for (Map.Entry<Integer, BluetoothProfile> p : profiles.entrySet())
        try {
          adapter.closeProfileProxy(p.getKey(), p.getValue());
        } catch (Exception ignored) {
        }
    if (active == this) active = null;
    repo.signals.locationValid = false;
    repo.signals.wifiKnown = false;
    repo.signals.btKnown = false;
    repo.signals.bluetooth.clear();
    if (!failed) repo.sensorStatus = "Tracking is off.";
    repo.notifyChanged();
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
