import { ConfigPlugin, withDangerousMod } from "@expo/config-plugins";
import * as fs from "fs";
import { createRequire } from "module";
import * as path from "path";

export type BeaconAndroidPluginProps = {
  backgroundGeolocation?: boolean;
};

export function getAndroidPluginKotlin(packageName: string): string {
  return `\
package ${packageName}

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.transistorsoft.locationmanager.adapter.BackgroundGeolocation
import com.transistorsoft.locationmanager.adapter.callback.TSCallback
import com.transistorsoft.locationmanager.adapter.callback.TSLocationCallback
import com.transistorsoft.locationmanager.adapter.callback.TSSyncCallback
import com.transistorsoft.locationmanager.data.LocationModel
import com.transistorsoft.locationmanager.location.TSCurrentPositionRequest
import com.transistorsoft.locationmanager.location.TSLocation
import expo.modules.beacon.BeaconEventPlugin

class BeaconGeoPlugin(context: Context) : BeaconEventPlugin {
  companion object {
    private const val TAG = "BeaconGeoPlugin"
    private const val STOP_GRACE_MS = 30_000L
  }

  private val appContext = context.applicationContext
  private val bgGeo = BackgroundGeolocation.getInstance(appContext, null)
  private val mainHandler = Handler(Looper.getMainLooper())
  private val activeBeaconReasons = mutableSetOf<String>()
  private var trackingRequested = false
  private var stopInFlight = false
  private var lifecycleGeneration = 0L
  private var pendingFinalization: Runnable? = null

  private fun logFailure(operation: String, error: Any) {
    Log.e(TAG, "$operation failed: $error")
  }

  private fun loggedCallback(operation: String) = object : TSCallback {
    override fun onSuccess() {}
    override fun onFailure(error: String) = logFailure(operation, error)
  }

  private fun runOnMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
  }

  private fun cancelPendingFinalization() {
    pendingFinalization?.let(mainHandler::removeCallbacks)
    pendingFinalization = null
  }

  private fun canFinalize(generation: Long) =
    activeBeaconReasons.isEmpty() && !trackingRequested && generation == lifecycleGeneration

  private fun ensureTrackingStarted() {
    if (!trackingRequested || stopInFlight) return
    bgGeo.start(object : TSCallback {
      override fun onSuccess() = runOnMain {
        if (trackingRequested && !stopInFlight) {
          bgGeo.changePace(true, loggedCallback("changePace(true)"))
        }
      }

      override fun onFailure(error: String) = logFailure("start", error)
    })
  }

  private fun startTracking() {
    lifecycleGeneration += 1
    cancelPendingFinalization()
    val wasTrackingRequested = trackingRequested
    trackingRequested = true
    if (!wasTrackingRequested) {
      ensureTrackingStarted()
    } else if (!stopInFlight) {
      bgGeo.changePace(true, loggedCallback("changePace(true)"))
    }
  }

  private fun scheduleFinalization() {
    if (!trackingRequested) return
    lifecycleGeneration += 1
    val generation = lifecycleGeneration
    cancelPendingFinalization()
    val finalization = Runnable {
      if (
        generation != lifecycleGeneration ||
        activeBeaconReasons.isNotEmpty() ||
        !trackingRequested
      ) return@Runnable
      pendingFinalization = null
      trackingRequested = false
      requestFinalPosition(generation)
    }
    pendingFinalization = finalization
    mainHandler.postDelayed(finalization, STOP_GRACE_MS)
  }

  private fun requestFinalPosition(generation: Long) {
    if (!canFinalize(generation)) return
    val builder = TSCurrentPositionRequest.Builder(appContext)
    builder.setPersist(true)
    builder.setCallback(object : TSLocationCallback {
      override fun onLocation(location: TSLocation) = runOnMain {
        changeToStationary(generation)
      }

      override fun onError(errorCode: Int) = runOnMain {
        logFailure("getCurrentPosition", errorCode)
        changeToStationary(generation)
      }
    })
    bgGeo.getCurrentPosition(builder.build())
  }

  private fun changeToStationary(generation: Long) {
    if (!canFinalize(generation)) return
    bgGeo.changePace(false, object : TSCallback {
      override fun onSuccess() = runOnMain { syncAndStop(generation) }

      override fun onFailure(error: String) = runOnMain {
        logFailure("changePace(false)", error)
        syncAndStop(generation)
      }
    })
  }

  private fun syncAndStop(generation: Long) {
    if (!canFinalize(generation)) return
    bgGeo.sync(object : TSSyncCallback {
      override fun onSuccess(records: MutableList<LocationModel>) = runOnMain {
        stopTracking(generation)
      }

      override fun onFailure(error: String) = runOnMain {
        logFailure("sync", error)
        stopTracking(generation)
      }
    })
  }

  private fun stopTracking(generation: Long) {
    if (!canFinalize(generation)) return
    stopInFlight = true
    bgGeo.stop(object : TSCallback {
      override fun onSuccess() = finishStop()

      override fun onFailure(error: String) {
        logFailure("stop", error)
        finishStop()
      }
    })
  }

  private fun finishStop() = runOnMain {
    stopInFlight = false
    if (trackingRequested) ensureTrackingStarted()
  }

  private fun setBeaconActive(reason: String, active: Boolean) {
    runOnMain {
      if (active) {
        activeBeaconReasons.add(reason)
        startTracking()
      } else {
        activeBeaconReasons.remove(reason)
        if (activeBeaconReasons.isEmpty()) scheduleFinalization()
      }
    }
  }

  override fun onBeaconEnter(identifier: String, uuid: String, major: Int, minor: Int, distance: Double) =
    setBeaconActive("ibeacon:$identifier", true)
  override fun onBeaconExit(identifier: String, uuid: String, major: Int, minor: Int, distance: Double) =
    setBeaconActive("ibeacon:$identifier", false)
  override fun onBeaconTimeout(identifier: String, uuid: String, major: Int, minor: Int, distance: Double) =
    setBeaconActive("ibeacon:$identifier", false)
  override fun onEddystoneEnter(identifier: String, namespace: String, instance: String, distance: Double) =
    setBeaconActive("eddystone:$identifier", true)
  override fun onEddystoneExit(identifier: String, namespace: String, instance: String, distance: Double) =
    setBeaconActive("eddystone:$identifier", false)
  override fun onEddystoneTimeout(identifier: String, namespace: String, instance: String, distance: Double) =
    setBeaconActive("eddystone:$identifier", false)
}
`;
}

function modifyMainApplication(contents: string): string {
  const importLine = "import expo.modules.beacon.BeaconPluginRegistry";
  if (!contents.includes(importLine)) {
    const lines = contents.split("\n");
    const lastImport = lines.reduce(
      (last, line, index) =>
        line.trimStart().startsWith("import ") ? index : last,
      -1,
    );
    if (lastImport >= 0) {
      lines.splice(lastImport + 1, 0, `${importLine} // expo-beacon-generated`);
    } else {
      const packageIndex = lines.findIndex((line) =>
        line.startsWith("package "),
      );
      lines.splice(
        packageIndex >= 0 ? packageIndex + 1 : 0,
        0,
        "",
        `${importLine} // expo-beacon-generated`,
      );
    }
    contents = lines.join("\n");
  }

  const call = "BeaconPluginRegistry.register(BeaconGeoPlugin(this))";
  if (!contents.includes(call)) {
    contents = contents.replace(
      /(super\.onCreate\(\)[ \t]*)(\r?\n)/,
      `$1$2    ${call}$2`,
    );
  }
  return contents;
}

function unmodifyMainApplication(contents: string): string {
  contents = contents.replace(
    /^[ \t]*BeaconPluginRegistry\.register\(BeaconGeoPlugin\(this\)\)\r?\n/gm,
    "",
  );
  const generatedImport =
    /^import expo\.modules\.beacon\.BeaconPluginRegistry \/\/ expo-beacon-generated\r?\n/m;
  const withoutImport = contents.replace(generatedImport, "");
  if (!/\bBeaconPluginRegistry\b/.test(withoutImport)) contents = withoutImport;
  return contents;
}

function assertBackgroundGeolocationInstalled(projectRoot: string): void {
  try {
    createRequire(path.join(projectRoot, "package.json")).resolve(
      "react-native-background-geolocation",
    );
  } catch {
    throw new Error(
      "[expo-beacon] backgroundGeolocation: true requires react-native-background-geolocation to be installed.",
    );
  }
}

function nativeSourceRoot(platformRoot: string, packageName: string): string {
  return path.join(
    platformRoot,
    "app/src/main/java",
    packageName.replace(/\./g, "/"),
  );
}

const withBeaconGeoPlugin: ConfigPlugin = (config) =>
  withDangerousMod(config, [
    "android",
    (cfg) => {
      assertBackgroundGeolocationInstalled(cfg.modRequest.projectRoot);
      const packageName = cfg.android?.package;
      if (!packageName) return cfg;
      const sourceRoot = nativeSourceRoot(
        cfg.modRequest.platformProjectRoot,
        packageName,
      );
      fs.mkdirSync(sourceRoot, { recursive: true });
      fs.writeFileSync(
        path.join(sourceRoot, "BeaconGeoPlugin.kt"),
        getAndroidPluginKotlin(packageName),
      );
      const mainApplication = [
        path.join(sourceRoot, "MainApplication.kt"),
        path.join(sourceRoot, "MainApplication.java"),
      ].find(fs.existsSync);
      if (!mainApplication || mainApplication.endsWith(".java")) return cfg;
      fs.writeFileSync(
        mainApplication,
        modifyMainApplication(fs.readFileSync(mainApplication, "utf8")),
      );
      return cfg;
    },
  ]);

const withoutBeaconGeoPlugin: ConfigPlugin = (config) =>
  withDangerousMod(config, [
    "android",
    (cfg) => {
      const packageName = cfg.android?.package;
      if (!packageName) return cfg;
      const sourceRoot = nativeSourceRoot(
        cfg.modRequest.platformProjectRoot,
        packageName,
      );
      const generated = path.join(sourceRoot, "BeaconGeoPlugin.kt");
      if (fs.existsSync(generated)) fs.rmSync(generated);
      const mainApplication = path.join(sourceRoot, "MainApplication.kt");
      if (fs.existsSync(mainApplication)) {
        const original = fs.readFileSync(mainApplication, "utf8");
        const cleaned = unmodifyMainApplication(original);
        if (cleaned !== original) fs.writeFileSync(mainApplication, cleaned);
      }
      return cfg;
    },
  ]);

const withBeaconAndroid: ConfigPlugin<BeaconAndroidPluginProps | void> = (
  config,
  props,
) => {
  const options = props ?? {};
  return options.backgroundGeolocation
    ? withBeaconGeoPlugin(config)
    : withoutBeaconGeoPlugin(config);
};

export const __androidPluginInternals = {
  modifyMainApplication,
  unmodifyMainApplication,
};

export default withBeaconAndroid;
