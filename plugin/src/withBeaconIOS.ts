import {
  ConfigPlugin,
  withDangerousMod,
  withInfoPlist,
  withXcodeProject,
} from "@expo/config-plugins";
import * as fs from "fs";
import { createRequire } from "module";
import * as path from "path";

export type BeaconIOSPluginProps = {
  /**
   * Generate and register the optional
   * `react-native-background-geolocation` lifecycle bridge.
   *
   * The peer package must be installed in the consuming app.
   * @defaultValue false
   */
  backgroundGeolocation?: boolean;
  /** Value for `NSLocationWhenInUseUsageDescription`. */
  locationWhenInUsePermission?: string;
  /** Value for `NSLocationAlwaysAndWhenInUseUsageDescription`. */
  locationAlwaysPermission?: string;
  /** Value for `NSBluetoothAlwaysUsageDescription`. */
  bluetoothPermission?: string;
};

export function getIOSPluginSwift(): string {
  return `\
internal import ExpoBeacon
import Foundation
import TSLocationManager

final class BeaconGeoPlugin: BeaconLifecycleDelegate {
  private static let stopGrace: TimeInterval = 30
  private static let stationaryTransitionTimeout: TimeInterval = 10

  private var activeBeaconReasons = Set<String>()
  private var trackingRequested = false
  private var lifecycleGeneration: UInt = 0
  private var pendingFinalization: DispatchWorkItem?
  private var pendingStationaryTransition: DispatchWorkItem?
  private var awaitingStationaryGeneration: UInt?
  private var motionChangeListenerRegistered = false

  private func startTracking() {
    lifecycleGeneration &+= 1
    cancelFinalization()
    trackingRequested = true
    ensureMotionChangeListener()
    let bgGeo = BackgroundGeolocation.sharedInstance()
    bgGeo.start()
    bgGeo.changePace(true)
  }

  private func scheduleFinalization() {
    guard trackingRequested else { return }
    lifecycleGeneration &+= 1
    let generation = lifecycleGeneration
    pendingFinalization?.cancel()
    let finalization = DispatchWorkItem { [weak self] in
      guard let self = self,
            generation == self.lifecycleGeneration,
            self.activeBeaconReasons.isEmpty,
            self.trackingRequested else { return }
      self.pendingFinalization = nil
      self.trackingRequested = false
      self.requestFinalPosition(generation: generation)
    }
    pendingFinalization = finalization
    DispatchQueue.main.asyncAfter(
      deadline: .now() + Self.stopGrace,
      execute: finalization
    )
  }

  private func canFinalize(_ generation: UInt) -> Bool {
    activeBeaconReasons.isEmpty && !trackingRequested && generation == lifecycleGeneration
  }

  private func cancelFinalization() {
    pendingFinalization?.cancel()
    pendingFinalization = nil
    pendingStationaryTransition?.cancel()
    pendingStationaryTransition = nil
    awaitingStationaryGeneration = nil
  }

  private func ensureMotionChangeListener() {
    guard !motionChangeListenerRegistered else { return }
    motionChangeListenerRegistered = true
    _ = BackgroundGeolocation.sharedInstance().onMotionChange { [weak self] _ in
      guard let self = self else { return }
      self.runOnMain {
        self.stationaryTransitionCompleted()
      }
    }
  }

  private func requestFinalPosition(generation: UInt) {
    guard canFinalize(generation) else { return }
    let request = TSCurrentPositionRequest.make(
      type: .current,
      success: { [weak self] _ in
        self?.runOnMain {
          self?.changeToStationary(generation: generation)
        }
      },
      failure: { [weak self] error in
        NSLog("[BeaconGeoPlugin] getCurrentPosition failed: %@", error.localizedDescription)
        self?.runOnMain {
          self?.changeToStationary(generation: generation)
        }
      }
    )
    request.persist = true
    BackgroundGeolocation.sharedInstance().getCurrentPosition(request)
  }

  private func changeToStationary(generation: UInt) {
    guard canFinalize(generation) else { return }
    awaitingStationaryGeneration = generation
    let timeout = DispatchWorkItem { [weak self] in
      guard let self = self,
            self.awaitingStationaryGeneration == generation,
            self.canFinalize(generation) else { return }
      NSLog("[BeaconGeoPlugin] changePace(false) motion-change timed out")
      self.stationaryTransitionCompleted()
    }
    pendingStationaryTransition = timeout
    DispatchQueue.main.asyncAfter(
      deadline: .now() + Self.stationaryTransitionTimeout,
      execute: timeout
    )
    BackgroundGeolocation.sharedInstance().changePace(false)
  }

  private func stationaryTransitionCompleted() {
    guard let generation = awaitingStationaryGeneration,
          canFinalize(generation) else { return }
    awaitingStationaryGeneration = nil
    pendingStationaryTransition?.cancel()
    pendingStationaryTransition = nil
    syncAndStop(generation: generation)
  }

  private func syncAndStop(generation: UInt) {
    guard canFinalize(generation) else { return }
    BackgroundGeolocation.sharedInstance().sync({ [weak self] _ in
      self?.runOnMain {
        self?.stopTracking(generation: generation)
      }
    }, failure: { [weak self] error in
      NSLog("[BeaconGeoPlugin] sync failed: %@", error.localizedDescription)
      self?.runOnMain {
        self?.stopTracking(generation: generation)
      }
    })
  }

  private func stopTracking(generation: UInt) {
    guard canFinalize(generation) else { return }
    BackgroundGeolocation.sharedInstance().stop()
  }

  private func setBeaconActive(_ reason: String, active: Bool) {
    runOnMain {
      if active {
        self.activeBeaconReasons.insert(reason)
        self.startTracking()
      } else {
        self.activeBeaconReasons.remove(reason)
        if self.activeBeaconReasons.isEmpty { self.scheduleFinalization() }
      }
    }
  }

  private func runOnMain(_ block: @escaping () -> Void) {
    if Thread.isMainThread { block() }
    else { DispatchQueue.main.async(execute: block) }
  }

  func beaconDidEnter(identifier: String, uuid: String, major: Int, minor: Int, distance: Double) {
    setBeaconActive("ibeacon:\\(identifier)", active: true)
  }
  func beaconDidExit(identifier: String, uuid: String, major: Int, minor: Int, distance: Double) {
    setBeaconActive("ibeacon:\\(identifier)", active: false)
  }
  func beaconDidTimeout(identifier: String, uuid: String, major: Int, minor: Int, distance: Double) {
    setBeaconActive("ibeacon:\\(identifier)", active: false)
  }
  func eddystoneDidEnter(identifier: String, namespace: String, instance: String, distance: Double) {
    setBeaconActive("eddystone:\\(identifier)", active: true)
  }
  func eddystoneDidExit(identifier: String, namespace: String, instance: String, distance: Double) {
    setBeaconActive("eddystone:\\(identifier)", active: false)
  }
  func eddystoneDidTimeout(identifier: String, namespace: String, instance: String, distance: Double) {
    setBeaconActive("eddystone:\\(identifier)", active: false)
  }
}
`;
}

function findMatchingClosingBrace(
  contents: string,
  openingIndex: number,
): number {
  let depth = 0;
  let blockCommentDepth = 0;
  let inLineComment = false;
  let inString = false;
  let inMultilineString = false;
  for (let index = openingIndex; index < contents.length; index += 1) {
    const char = contents[index];
    const next = contents[index + 1];
    if (inLineComment) {
      if (char === "\n") inLineComment = false;
      continue;
    }
    if (blockCommentDepth > 0) {
      if (char === "/" && next === "*") {
        blockCommentDepth += 1;
        index += 1;
      } else if (char === "*" && next === "/") {
        blockCommentDepth -= 1;
        index += 1;
      }
      continue;
    }
    if (inString) {
      if (inMultilineString && contents.startsWith('"""', index)) {
        inString = false;
        inMultilineString = false;
        index += 2;
      } else if (!inMultilineString && char === "\\") index += 1;
      else if (!inMultilineString && char === '"') inString = false;
      continue;
    }
    if (char === "/" && next === "/") {
      inLineComment = true;
      index += 1;
      continue;
    }
    if (char === "/" && next === "*") {
      blockCommentDepth = 1;
      index += 1;
      continue;
    }
    if (contents.startsWith('"""', index)) {
      inString = true;
      inMultilineString = true;
      index += 2;
      continue;
    }
    if (char === '"') {
      inString = true;
      continue;
    }
    if (char === "{") depth += 1;
    if (char === "}" && --depth === 0) return index;
  }
  return -1;
}

function modifyAppDelegateSwift(contents: string): string {
  const importLine = "internal import ExpoBeacon";
  // Normalize bare imports left behind by older plugin versions so repeated
  // prebuilds cannot insert a duplicate import.
  contents = contents.replace(
    /^([ \t]*)import ExpoBeacon\b/gm,
    "$1internal import ExpoBeacon",
  );
  if (!contents.includes(importLine)) {
    const lines = contents.split("\n");
    const lastImport = lines.reduce(
      (last, line, index) =>
        line.trimStart().startsWith("import ") ? index : last,
      -1,
    );
    lines.splice(lastImport + 1, 0, `${importLine} // expo-beacon-generated`);
    contents = lines.join("\n");
  }
  const call = "BeaconLifecycleRegistry.register(BeaconGeoPlugin())";
  if (contents.includes(call)) return contents;
  const launchMethod =
    /^([ \t]*)(?:(?:public|open)\s+)?override\s+func\s+application\s*\([\s\S]{0,800}?didFinishLaunchingWithOptions[\s\S]{0,800}?\)\s*->\s*Bool\s*\{/m;
  const match = launchMethod.exec(contents);
  if (match) {
    return (
      contents.slice(0, match.index) +
      match[0] +
      `\n${match[1]}  ${call}\n` +
      contents.slice(match.index + match[0].length)
    );
  }
  const appDelegate =
    /^([ \t]*)(?:(?:public|open|final)\s+)*class\s+AppDelegate\b[^{]*\{/m.exec(
      contents,
    );
  if (!appDelegate) return contents;
  const opening = appDelegate.index + appDelegate[0].lastIndexOf("{");
  const closing = findMatchingClosingBrace(contents, opening);
  if (closing < 0) return contents;
  const indent = `${appDelegate[1]}  `;
  const method = `\n${indent}override func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {\n${indent}  ${call}\n${indent}  return super.application(application, didFinishLaunchingWithOptions: launchOptions)\n${indent}}\n`;
  return contents.slice(0, closing) + method + contents.slice(closing);
}

function unmodifyAppDelegateSwift(contents: string): string {
  contents = contents.replace(
    /\r?\n[ \t]*override func application\(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: \[UIApplication\.LaunchOptionsKey: Any\]\?\) -> Bool \{\r?\n[ \t]*BeaconLifecycleRegistry\.register\(BeaconGeoPlugin\(\)\)\r?\n[ \t]*return super\.application\(application, didFinishLaunchingWithOptions: launchOptions\)\r?\n[ \t]*\}\r?\n?/g,
    "\n",
  );
  contents = contents.replace(
    /^[ \t]*BeaconLifecycleRegistry\.register\(BeaconGeoPlugin\(\)\)\r?\n/gm,
    "",
  );
  const generatedImport =
    /^(?:internal\s+)?import ExpoBeacon \/\/ expo-beacon-generated\r?\n/m;
  const withoutImport = contents.replace(generatedImport, "");
  if (!/\bBeaconLifecycleRegistry\b/.test(withoutImport))
    contents = withoutImport;
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

function applyBeaconInfoPlist(
  info: Record<string, any>,
  options: BeaconIOSPluginProps,
): Record<string, any> {
  const modes: string[] = Array.isArray(info.UIBackgroundModes)
    ? info.UIBackgroundModes
    : [];
  for (const mode of ["location", "bluetooth-central"]) {
    if (!modes.includes(mode)) modes.push(mode);
  }
  info.UIBackgroundModes = modes;
  info.NSLocationWhenInUseUsageDescription =
    options.locationWhenInUsePermission ??
    info.NSLocationWhenInUseUsageDescription ??
    "Allow $(PRODUCT_NAME) to detect nearby beacons while you use the app.";
  info.NSLocationAlwaysAndWhenInUseUsageDescription =
    options.locationAlwaysPermission ??
    info.NSLocationAlwaysAndWhenInUseUsageDescription ??
    "Allow $(PRODUCT_NAME) to monitor paired beacons in the background.";
  info.NSBluetoothAlwaysUsageDescription =
    options.bluetoothPermission ??
    info.NSBluetoothAlwaysUsageDescription ??
    "Allow $(PRODUCT_NAME) to scan for nearby Bluetooth beacons.";
  return info;
}

function findAppDir(platformRoot: string) {
  for (const entry of fs.readdirSync(platformRoot)) {
    const appDelegatePath = path.join(platformRoot, entry, "AppDelegate.swift");
    if (fs.existsSync(appDelegatePath))
      return { appDir: entry, appDelegatePath };
  }
  return null;
}

function getXcodeGroupKey(project: any, groupName: string) {
  const groups = project.hash.project.objects.PBXGroup as Record<string, any>;
  return Object.keys(groups).find(
    (key) =>
      !key.endsWith("_comment") &&
      (groups[key].name === groupName || groups[key].path === groupName),
  );
}

const withBeaconGeoPlugin: ConfigPlugin = (config) => {
  config = withDangerousMod(config, [
    "ios",
    (cfg) => {
      assertBackgroundGeolocationInstalled(cfg.modRequest.projectRoot);
      const app = findAppDir(cfg.modRequest.platformProjectRoot);
      if (app) {
        fs.writeFileSync(
          path.join(
            cfg.modRequest.platformProjectRoot,
            app.appDir,
            "BeaconGeoPlugin.swift",
          ),
          getIOSPluginSwift(),
        );
      }
      return cfg;
    },
  ]);
  config = withXcodeProject(config, (cfg) => {
    const app = findAppDir(cfg.modRequest.platformProjectRoot);
    const group = app?.appDir ?? cfg.modRequest.projectName;
    if (!group) return cfg;
    const file = `${group}/BeaconGeoPlugin.swift`;
    if (!cfg.modResults.hasFile(file)) {
      cfg.modResults.addSourceFile(
        file,
        { target: cfg.modResults.getFirstTarget().uuid },
        getXcodeGroupKey(cfg.modResults, group),
      );
    }
    return cfg;
  });
  return withDangerousMod(config, [
    "ios",
    (cfg) => {
      const app = findAppDir(cfg.modRequest.platformProjectRoot);
      if (app) {
        fs.writeFileSync(
          app.appDelegatePath,
          modifyAppDelegateSwift(fs.readFileSync(app.appDelegatePath, "utf8")),
        );
      }
      return cfg;
    },
  ]);
};

const withoutBeaconGeoPlugin: ConfigPlugin = (config) => {
  config = withXcodeProject(config, (cfg) => {
    const app = findAppDir(cfg.modRequest.platformProjectRoot);
    const group = app?.appDir ?? cfg.modRequest.projectName;
    if (!group) return cfg;
    const file = `${group}/BeaconGeoPlugin.swift`;
    if (cfg.modResults.hasFile(file) && cfg.modResults.removeSourceFile) {
      cfg.modResults.removeSourceFile(
        file,
        { target: cfg.modResults.getFirstTarget().uuid },
        getXcodeGroupKey(cfg.modResults, group),
      );
    }
    return cfg;
  });
  return withDangerousMod(config, [
    "ios",
    (cfg) => {
      const app = findAppDir(cfg.modRequest.platformProjectRoot);
      if (!app) return cfg;
      const generated = path.join(
        cfg.modRequest.platformProjectRoot,
        app.appDir,
        "BeaconGeoPlugin.swift",
      );
      if (fs.existsSync(generated)) fs.rmSync(generated);
      const original = fs.readFileSync(app.appDelegatePath, "utf8");
      const cleaned = unmodifyAppDelegateSwift(original);
      if (cleaned !== original) fs.writeFileSync(app.appDelegatePath, cleaned);
      return cfg;
    },
  ]);
};

const withBeaconIOS: ConfigPlugin<BeaconIOSPluginProps | void> = (
  config,
  props,
) => {
  const options = props ?? {};
  config = options.backgroundGeolocation
    ? withBeaconGeoPlugin(config)
    : withoutBeaconGeoPlugin(config);
  return withInfoPlist(config, (cfg) => {
    applyBeaconInfoPlist(cfg.modResults, options);
    return cfg;
  });
};

export const __iosPluginInternals = {
  applyBeaconInfoPlist,
  modifyAppDelegateSwift,
  unmodifyAppDelegateSwift,
};

export default withBeaconIOS;
