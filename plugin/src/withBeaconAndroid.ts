import { ConfigPlugin, withDangerousMod } from '@expo/config-plugins';
import * as fs from 'fs';
import * as path from 'path';

// ─── Generated Kotlin file ────────────────────────────────────────────────────

export function getAndroidPluginKotlin(packageName: string): string {
  return `\
package ${packageName}

import android.content.Context
import com.transistorsoft.locationmanager.adapter.BackgroundGeolocation
import com.transistorsoft.locationmanager.adapter.callback.TSCallback
import expo.modules.beacon.BeaconEventPlugin

class BeaconGeoPlugin(ctx: Context) : BeaconEventPlugin {
    private val bgGeo = BackgroundGeolocation.getInstance(ctx, null)
    private val noOp = object : TSCallback {
        override fun onSuccess() {}
        override fun onFailure(error: String) {}
    }

    override fun onBeaconEnter(identifier: String, uuid: String, major: Int, minor: Int, distance: Double) =
        bgGeo.start(noOp)
    override fun onBeaconExit(identifier: String, uuid: String, major: Int, minor: Int, distance: Double) =
        bgGeo.stop(noOp)
    override fun onBeaconTimeout(identifier: String, uuid: String, major: Int, minor: Int, distance: Double) =
        bgGeo.stop(noOp)
    override fun onEddystoneEnter(identifier: String, namespace: String, instance: String, distance: Double) =
        bgGeo.start(noOp)
    override fun onEddystoneExit(identifier: String, namespace: String, instance: String, distance: Double) =
        bgGeo.stop(noOp)
    override fun onEddystoneTimeout(identifier: String, namespace: String, instance: String, distance: Double) =
        bgGeo.stop(noOp)
    // Start tracking when the device connects to Android Auto, stop when it disconnects.
    override fun onCarPlayConnected(transport: String) {
        bgGeo.start(noOp)
    }
    override fun onCarPlayDisconnected() {
        bgGeo.stop(noOp)
    }
}
`;
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function modifyMainApplication(contents: string): string {
  const importLine = 'import expo.modules.beacon.BeaconPluginRegistry';

  // Add missing import after the last existing import.
  if (!contents.includes(importLine)) {
    const lines = contents.split('\n');
    const lastImportIdx = lines.reduce(
      (last, line, i) => (line.trimStart().startsWith('import ') ? i : last),
      -1,
    );
    if (lastImportIdx >= 0) {
      lines.splice(lastImportIdx + 1, 0, importLine);
    } else {
      const pkgIdx = lines.findIndex((l) => l.startsWith('package '));
      lines.splice(pkgIdx >= 0 ? pkgIdx + 1 : 0, 0, '', importLine);
    }
    contents = lines.join('\n');
  }

  // Add registration call immediately after `super.onCreate()`.
  const registration = '    BeaconPluginRegistry.register(BeaconGeoPlugin(this))';
  if (!contents.includes('BeaconPluginRegistry.register(BeaconGeoPlugin(this))')) {
    contents = contents.replace(
      /(super\.onCreate\(\)[ \t]*\n)/,
      `$1${registration}\n`,
    );
  }

  return contents;
}

// ─── Plugin ───────────────────────────────────────────────────────────────────

const withBeaconAndroid: ConfigPlugin = (config) => {
  // Step 1 – write BeaconGeoPlugin.kt into the app source tree.
  config = withDangerousMod(config, [
    'android',
    (config) => {
      const pkgName = config.android?.package;
      if (!pkgName) {
        console.warn(
          '[expo-beacon] android.package not set — BeaconGeoPlugin.kt was not written.',
        );
        return config;
      }
      const pkgPath = pkgName.replace(/\./g, '/');
      const outputPath = path.join(
        config.modRequest.platformProjectRoot,
        'app/src/main/java',
        pkgPath,
        'BeaconGeoPlugin.kt',
      );
      fs.writeFileSync(outputPath, getAndroidPluginKotlin(pkgName));
      return config;
    },
  ]);

  // Step 2 – patch MainApplication (Kotlin or Java) to call register().
  config = withDangerousMod(config, [
    'android',
    (config) => {
      const pkgName = config.android?.package;
      if (!pkgName) return config;

      const pkgPath = pkgName.replace(/\./g, '/');
      const javaRoot = path.join(
        config.modRequest.platformProjectRoot,
        'app/src/main/java',
        pkgPath,
      );

      const mainAppPath = [
        path.join(javaRoot, 'MainApplication.kt'),
        path.join(javaRoot, 'MainApplication.java'),
      ].find(fs.existsSync);

      if (!mainAppPath) {
        console.warn(
          '[expo-beacon] MainApplication.kt / .java not found — ' +
            'please add BeaconPluginRegistry.register(BeaconGeoPlugin(this)) manually.',
        );
        return config;
      }

      const original = fs.readFileSync(mainAppPath, 'utf-8');
      fs.writeFileSync(mainAppPath, modifyMainApplication(original));
      return config;
    },
  ]);

  return config;
};

export default withBeaconAndroid;
