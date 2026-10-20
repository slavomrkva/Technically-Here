package sk.technicallyhere;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Application;
import android.app.Service;
import android.content.Intent;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class TrackingRecoveryTest {
  private Application app;
  private Repository repo;

  @Before
  public void setup() {
    app = RuntimeEnvironment.getApplication();
    ReflectionHelpers.setStaticField(Repository.class, "singleton", null);
    TrackingService.active = null;
    repo = Repository.get(app);
    repo.reset();
  }

  private void permissions() {
    Shadows.shadowOf(app)
        .grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION);
  }

  @Test
  public void bootAndUpdateResumeOnlyWhenPreviouslyEnabled() {
    permissions();
    BootReceiver receiver = new BootReceiver();
    receiver.onReceive(app, new Intent(Intent.ACTION_BOOT_COMPLETED));
    assertNull(Shadows.shadowOf(app).getNextStartedService());
    repo.change(s -> J.put(s, "monitorWanted", true));
    receiver.onReceive(app, new Intent(Intent.ACTION_BOOT_COMPLETED));
    assertEquals(
        TrackingService.class.getName(),
        Shadows.shadowOf(app).getNextStartedService().getComponent().getClassName());
    receiver.onReceive(app, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));
    assertNotNull(Shadows.shadowOf(app).getNextStartedService());
    repo.change(TrackingRecovery::stopped);
    receiver.onReceive(app, new Intent(Intent.ACTION_BOOT_COMPLETED));
    assertNull(Shadows.shadowOf(app).getNextStartedService());
  }

  @Test
  public void bootDoesNotAttemptIllegalBackgroundLocationStart() {
    Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
    Shadows.shadowOf(app).denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
    repo.change(s -> J.put(s, "monitorWanted", true));
    new BootReceiver().onReceive(app, new Intent(Intent.ACTION_BOOT_COMPLETED));
    assertNull(Shadows.shadowOf(app).getNextStartedService());
    assertTrue(repo.snapshot().optBoolean("monitorWanted"));
    TrackingRecovery.resume(app, true);
    assertNotNull(Shadows.shadowOf(app).getNextStartedService());
  }

  @Test
  @Config(sdk = 28)
  public void oldAndroidCanResumeWithoutBackgroundPermission() {
    Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
    repo.change(s -> J.put(s, "monitorWanted", true));
    assertTrue(TrackingRecovery.backgroundAllowed(app));
    new BootReceiver().onReceive(app, new Intent(Intent.ACTION_BOOT_COMPLETED));
    assertNotNull(Shadows.shadowOf(app).getNextStartedService());
  }

  @Test
  public void unrelatedBroadcastAndLostPermissionDoNotStartService() {
    permissions();
    repo.change(s -> J.put(s, "monitorWanted", true));
    new BootReceiver().onReceive(app, new Intent("unrelated"));
    assertNull(Shadows.shadowOf(app).getNextStartedService());
    Shadows.shadowOf(app)
        .denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION);
    TrackingRecovery.resume(app, true);
    assertNull(Shadows.shadowOf(app).getNextStartedService());
  }

  @Test
  public void heartbeatFlagsGapWithoutChangingRecordedTimes() {
    JSONObject state = Core.defaults();
    J.put(state, "monitorWanted", true);
    J.array(state, "entries").put(J.obj("id", "test", "start", 1000L));
    String entries = J.array(state, "entries").toString();
    TrackingRecovery.heartbeat(state, 10_000L);
    TrackingRecovery.heartbeat(state, 70_000L);
    assertEquals(0, state.optInt("trackingGapCount"));
    TrackingRecovery.heartbeat(state, 1_000_000L);
    assertEquals(1, state.optInt("trackingGapCount"));
    assertEquals(70_000L, state.optLong("trackingGapFrom"));
    assertEquals(1_000_000L, state.optLong("trackingGapTo"));
    assertEquals(entries, J.array(state, "entries").toString());
    TrackingRecovery.heartbeat(state, 1_060_000L);
    assertEquals(1, state.optInt("trackingGapCount"));
    TrackingRecovery.stopped(state);
    J.put(state, "monitorWanted", true);
    TrackingRecovery.heartbeat(state, 9_000_000L);
    assertEquals(1, state.optInt("trackingGapCount"));
  }

  @Test
  public void restoredBackupAndStickyRestartRespectStoppedState() {
    repo.change(
        s -> {
          J.put(s, "monitorWanted", true);
          J.put(s, "trackingHeartbeat", 12345L);
        });
    repo.restore(repo.snapshot());
    assertEquals(0, repo.snapshot().optLong("trackingHeartbeat"));
    var controller = Robolectric.buildService(TrackingService.class).create();
    assertEquals(Service.START_NOT_STICKY, controller.get().onStartCommand(null, 0, 1));
    assertFalse(repo.snapshot().optBoolean("monitorWanted"));
    controller.destroy();
  }

  @Test
  public void heartbeatPersistsAcrossProcessRestart() {
    repo.change(
        s -> {
          J.put(s, "monitorWanted", true);
          TrackingRecovery.heartbeat(s, 10_000L);
        });
    ReflectionHelpers.setStaticField(Repository.class, "singleton", null);
    Repository reopened = Repository.get(app);
    reopened.change(s -> TrackingRecovery.heartbeat(s, 900_000L));
    assertEquals(1, reopened.snapshot().optInt("trackingGapCount"));
    assertTrue(reopened.snapshot().optBoolean("monitorWanted"));
  }
}
