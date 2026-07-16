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
import com.transistorsoft.locationmanager.adapter.BackgroundGeolocation
import com.transistorsoft.locationmanager.adapter.callback.TSCallback
import com.transistorsoft.locationmanager.adapter.callback.TSSyncCallback
import com.transistorsoft.locationmanager.data.LocationModel
import expo.modules.beacon.BeaconEventPlugin

class BeaconGeoPlugin(context: Context) : BeaconEventPlugin {
  private val appContext = context.applicationContext
  private val bgGeo = BackgroundGeolocation.getInstance(appContext, null)
  private val mainHandler = Handler(Looper.getMainLooper())
  private val activeBeaconReasons = mutableSetOf<String>()
  private var trackingRequested = false
  private val noOp = object : TSCallback {
    override fun onSuccess() {}
    override fun onFailure(error: String) {}
  }
  private val noOpSync = object : TSSyncCallback {
    override fun onSuccess(records: MutableList<LocationModel>) {}
    override fun onFailure(error: String) {}
  }

  private fun startTracking() {
    if (!trackingRequested) {
      trackingRequested = true
      bgGeo.start(noOp)
    }
    bgGeo.changePace(true, noOp)
  }

  private fun stopTracking() {
    if (!trackingRequested) return
    trackingRequested = false
    bgGeo.sync(noOpSync)
    bgGeo.changePace(false, noOp)
    bgGeo.stop(noOp)
  }

  private fun setBeaconActive(reason: String, active: Boolean) {
    runOnMain {
      if (active) {
        activeBeaconReasons.add(reason)
        startTracking()
      } else {
        activeBeaconReasons.remove(reason)
        if (activeBeaconReasons.isEmpty()) stopTracking()
      }
    }
  }

  private fun runOnMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
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
