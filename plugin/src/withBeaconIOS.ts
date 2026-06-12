import {
  ConfigPlugin,
  withDangerousMod,
  withEntitlementsPlist,
  withInfoPlist,
  withXcodeProject,
} from "@expo/config-plugins";
import * as fs from "fs";
import * as path from "path";

// ─── Plugin options ───────────────────────────────────────────────────────────

export type BeaconIOSPluginProps = {
  /**
   * Enable the CarPlay "Driving Task" entitlement integration.
   *
   * When `true` the plugin will:
   *   1. Add `com.apple.developer.carplay-driving-task` to the app's
   *      `*.entitlements` file.
   *   2. Inject a CarPlay scene configuration into `Info.plist`'s
   *      `UIApplicationSceneManifest`, pointing at
   *      `ExpoBeacon.BeaconCarPlaySceneDelegate` (shipped by this library).
   *
   * Requires the consumer app to have been granted the entitlement by Apple
   * (request at https://developer.apple.com/contact/carplay/) and a
   * matching provisioning profile. Without those, the build will fail to
   * sign. Default: `false`.
   */
  carplayDrivingTask?: boolean;
};

// ─── Generated Swift file ─────────────────────────────────────────────────────

export function getIOSPluginSwift(): string {
  return `\
import ExpoBeacon
import TSLocationManager

final class BeaconGeoPlugin: BeaconLifecycleDelegate {
  private func startTracking() {
    BackgroundGeolocation.sharedInstance().start()
    BackgroundGeolocation.sharedInstance().changePace(true)
  }

  private func stopTracking() {
    BackgroundGeolocation.sharedInstance().changePace(false)
    BackgroundGeolocation.sharedInstance().stop()
  }

  func beaconDidEnter(identifier: String, uuid: String, major: Int, minor: Int, distance: Double) {
    startTracking()
  }
  func beaconDidExit(identifier: String, uuid: String, major: Int, minor: Int, distance: Double) {
    stopTracking()
  }
  func beaconDidTimeout(identifier: String, uuid: String, major: Int, minor: Int, distance: Double) {
    stopTracking()
  }
  func eddystoneDidEnter(identifier: String, namespace: String, instance: String, distance: Double) {
    startTracking()
  }
  func eddystoneDidExit(identifier: String, namespace: String, instance: String, distance: Double) {
    stopTracking()
  }
  func eddystoneDidTimeout(identifier: String, namespace: String, instance: String, distance: Double) {
    stopTracking()
  }
  // Start tracking when the device connects to CarPlay (wired or wireless),
  // stop when it disconnects.
  func carPlayDidConnect(transport: String) {
    startTracking()
  }
  func carPlayDidDisconnect() {
    stopTracking()
  }
}
`;
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function modifyAppDelegateSwift(contents: string): string {
  const importLine = "import ExpoBeacon";

  // 1. Add import after the last existing import line.
  if (!contents.includes(importLine)) {
    const lines = contents.split("\n");
    const lastImportIdx = lines.reduce(
      (last, line, i) => (line.trimStart().startsWith("import ") ? i : last),
      -1,
    );
    if (lastImportIdx >= 0) {
      lines.splice(lastImportIdx + 1, 0, importLine);
    } else {
      lines.splice(0, 0, importLine, "");
    }
    contents = lines.join("\n");
  }

  // 2. Insert registration call before `return super.application(…didFinishLaunchingWithOptions…)`.
  const registrationCall =
    "BeaconLifecycleRegistry.register(BeaconGeoPlugin())";
  if (contents.includes(registrationCall)) {
    return contents; // already patched
  }

  if (
    /return super\.application\(application,\s*didFinishLaunching/.test(
      contents,
    )
  ) {
    contents = contents.replace(
      /([ \t]*)(return super\.application\(application,\s*didFinishLaunchingWithOptions[^)]*\))/,
      `$1${registrationCall}\n$1$2`,
    );
    return contents;
  }

  // Fallback: no `didFinishLaunchingWithOptions` override found — inject one before
  // the last closing brace of the class so it is always picked up by the compiler.
  const methodOverride = `
  override func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
    ${registrationCall}
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }
`;
  const lastBraceIdx = contents.lastIndexOf("}");
  if (lastBraceIdx >= 0) {
    contents =
      contents.slice(0, lastBraceIdx) +
      methodOverride +
      contents.slice(lastBraceIdx);
  }

  return contents;
}

/** Return the first subdirectory of `platformRoot` that contains AppDelegate.swift. */
function findAppDir(
  platformRoot: string,
): { appDir: string; appDelegatePath: string } | null {
  let entries: string[];
  try {
    entries = fs.readdirSync(platformRoot);
  } catch {
    return null;
  }
  for (const entry of entries) {
    const dirPath = path.join(platformRoot, entry);
    try {
      if (!fs.statSync(dirPath).isDirectory()) continue;
    } catch {
      continue;
    }
    const swiftPath = path.join(dirPath, "AppDelegate.swift");
    if (fs.existsSync(swiftPath)) {
      return { appDir: entry, appDelegatePath: swiftPath };
    }
  }
  return null;
}

// ─── Plugin ───────────────────────────────────────────────────────────────────

const withBeaconIOS: ConfigPlugin<BeaconIOSPluginProps | void> = (
  config,
  props,
) => {
  const opts: BeaconIOSPluginProps = props ?? {};

  // Step 1 – write BeaconGeoPlugin.swift into the iOS app directory.
  config = withDangerousMod(config, [
    "ios",
    (config) => {
      const platformRoot = config.modRequest.platformProjectRoot;
      const result = findAppDir(platformRoot);
      if (!result) {
        console.warn(
          "[expo-beacon] Could not locate iOS app directory — BeaconGeoPlugin.swift was not written.",
        );
        return config;
      }
      const outputPath = path.join(
        platformRoot,
        result.appDir,
        "BeaconGeoPlugin.swift",
      );
      fs.writeFileSync(outputPath, getIOSPluginSwift());
      return config;
    },
  ]);

  // Step 2 – add BeaconGeoPlugin.swift to the Xcode project target.
  config = withXcodeProject(config, (config) => {
    const xcodeProject = config.modResults;
    const projectName = config.modRequest.projectName;
    const filePath = `${projectName}/BeaconGeoPlugin.swift`;

    if (!xcodeProject.hasFile(filePath)) {
      // pbxGroupByName returns the group object; addSourceFile needs the UUID key.
      const groups = xcodeProject.hash.project.objects["PBXGroup"] as Record<
        string,
        any
      >;
      let groupKey: string | undefined;
      for (const key of Object.keys(groups)) {
        if (key.endsWith("_comment")) continue;
        const g = groups[key];
        if (g.name === projectName || g.path === projectName) {
          groupKey = key;
          break;
        }
      }
      xcodeProject.addSourceFile(
        filePath,
        { target: xcodeProject.getFirstTarget().uuid },
        groupKey,
      );
    }

    return config;
  });

  // Step 3 – patch AppDelegate.swift to register the plugin.
  config = withDangerousMod(config, [
    "ios",
    (config) => {
      const platformRoot = config.modRequest.platformProjectRoot;
      const result = findAppDir(platformRoot);
      if (!result) {
        console.warn(
          "[expo-beacon] AppDelegate.swift not found — " +
            "please add BeaconLifecycleRegistry.register(BeaconGeoPlugin()) manually.",
        );
        return config;
      }
      const original = fs.readFileSync(result.appDelegatePath, "utf-8");
      fs.writeFileSync(
        result.appDelegatePath,
        modifyAppDelegateSwift(original),
      );
      return config;
    },
  ]);

  // Step 4 (opt-in) – CarPlay Driving Task entitlement + scene manifest.
  if (opts.carplayDrivingTask) {
    config = withCarPlayDrivingTask(config);
  }

  return config;
};

// ─── CarPlay Driving Task wiring ──────────────────────────────────────────────

const CARPLAY_ENTITLEMENT_KEY = "com.apple.developer.carplay-driving-task";
const CARPLAY_SCENE_DELEGATE_CLASS = "ExpoBeacon.BeaconCarPlaySceneDelegate";
const CARPLAY_SCENE_CONFIG_NAME = "ExpoBeaconCarPlay";

function withCarPlayDrivingTask(config: Parameters<ConfigPlugin>[0]) {
  // 4a — entitlement
  config = withEntitlementsPlist(config, (cfg) => {
    cfg.modResults[CARPLAY_ENTITLEMENT_KEY] = true;
    return cfg;
  });

  // 4b — Info.plist scene manifest
  config = withInfoPlist(config, (cfg) => {
    const info = cfg.modResults as Record<string, any>;
    const manifest = (info.UIApplicationSceneManifest =
      info.UIApplicationSceneManifest ?? {});
    // Multi-scene support is required for CarPlay scenes to attach.
    manifest.UIApplicationSupportsMultipleScenes = true;
    const configurations = (manifest.UISceneConfigurations =
      manifest.UISceneConfigurations ?? {});
    const role: any[] =
      (configurations.CPTemplateApplicationSceneSessionRoleApplication =
        configurations.CPTemplateApplicationSceneSessionRoleApplication ?? []);
    const alreadyPresent = role.some(
      (entry: any) =>
        entry?.UISceneDelegateClassName === CARPLAY_SCENE_DELEGATE_CLASS ||
        entry?.UISceneConfigurationName === CARPLAY_SCENE_CONFIG_NAME,
    );
    if (!alreadyPresent) {
      role.push({
        UISceneClassName: "CPTemplateApplicationScene",
        UISceneConfigurationName: CARPLAY_SCENE_CONFIG_NAME,
        UISceneDelegateClassName: CARPLAY_SCENE_DELEGATE_CLASS,
      });
    }
    return cfg;
  });

  return config;
}

export default withBeaconIOS;
