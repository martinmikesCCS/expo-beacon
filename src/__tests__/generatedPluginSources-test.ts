import { getAndroidPluginKotlin } from "../../plugin/src/withBeaconAndroid";
import { getIOSPluginSwift } from "../../plugin/src/withBeaconIOS";

describe("generated Beacon background-geolocation plugins", () => {
  it("generates the sequenced Android journey finalization", () => {
    const source = getAndroidPluginKotlin("com.example.app");

    expect(source).toContain("activeBeaconReasons");
    expect(source).toContain(
      "if (activeBeaconReasons.isEmpty()) scheduleFinalization()",
    );
    expect(source).toContain("STOP_GRACE_MS = 30_000L");
    expect(source).toContain(
      "mainHandler.postDelayed(finalization, STOP_GRACE_MS)",
    );
    expect(source).toContain("TSCurrentPositionRequest.Builder(appContext)");
    expect(source).toContain("builder.setPersist(true)");
    expect(source).toContain(
      "import com.transistorsoft.locationmanager.event.LocationEvent",
    );
    expect(source).toContain(
      "override fun onLocation(event: LocationEvent) = runOnMain {",
    );
    expect(source).not.toContain(
      "import com.transistorsoft.locationmanager.location.TSLocation",
    );
    expect(source).toContain(
      "override fun onSuccess() = runOnMain { syncAndStop(generation) }",
    );
    expect(source).toContain("stopTracking(generation)");
    expect(source).toContain("cancelPendingFinalization()");
    expect(source).toContain('Log.e(TAG, "$operation failed: $error")');
    expect(source).not.toContain("noOp");
    expect(source).not.toContain("CarPlay");
  });

  it("generates the sequenced iOS journey finalization", () => {
    const source = getIOSPluginSwift();

    expect(source).toContain("activeBeaconReasons");
    expect(source).toContain(
      "if self.activeBeaconReasons.isEmpty { self.scheduleFinalization() }",
    );
    expect(source).toContain("private static let stopGrace: TimeInterval = 30");
    expect(source).toContain("TSCurrentPositionRequest(");
    expect(source).toContain("persist: true");
    expect(source).toContain("onMotionChange");
    expect(source).toContain("stationaryTransitionCompleted()");
    expect(source).toContain("syncAndStop(generation: generation)");
    expect(source).toContain("BackgroundGeolocation.sharedInstance().stop()");
    expect(source).toContain("pendingFinalization?.cancel()");
    expect(source).toContain('NSLog("[BeaconGeoPlugin] sync failed: %@"');
    expect(source).not.toContain("CarPlay");
  });
});
