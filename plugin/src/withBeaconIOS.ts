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
  backgroundGeolocation?: boolean;
  locationWhenInUsePermission?: string;
  locationAlwaysPermission?: string;
  bluetoothPermission?: string;
};

export function getIOSPluginSwift(): string {
  return `\
import ExpoBeacon
import Foundation
import TSLocationManager

final class BeaconGeoPlugin: BeaconLifecycleDelegate {
  private var activeBeaconReasons = Set<String>()
  private var trackingRequested = false

  private func startTracking() {
    if !trackingRequested {
      trackingRequested = true
      BackgroundGeolocation.sharedInstance().start()
    }
    BackgroundGeolocation.sharedInstance().changePace(true)
  }

  private func stopTracking() {
    guard trackingRequested else { return }
    trackingRequested = false
    BackgroundGeolocation.sharedInstance().sync({ _ in }, failure: { _ in })
    BackgroundGeolocation.sharedInstance().changePace(false)
    BackgroundGeolocation.sharedInstance().stop()
  }

  private func setBeaconActive(_ reason: String, active: Bool) {
    runOnMain {
      if active {
        self.activeBeaconReasons.insert(reason)
        self.startTracking()
      } else {
        self.activeBeaconReasons.remove(reason)
        if self.activeBeaconReasons.isEmpty { self.stopTracking() }
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
  const importLine = "import ExpoBeacon";
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
  const generatedImport = /^import ExpoBeacon \/\/ expo-beacon-generated\r?\n/m;
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
