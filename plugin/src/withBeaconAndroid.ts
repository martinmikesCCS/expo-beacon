import {
  AndroidConfig,
  ConfigPlugin,
  withAndroidManifest,
  withDangerousMod,
} from '@expo/config-plugins';
import * as fs from 'fs';
import * as path from 'path';

// ─── Plugin options ───────────────────────────────────────────────────────────

/**
 * Categories advertised in the generated `automotive_app_desc.xml` resource.
 * - `template` — declares the app as a Car-App-Library template provider. This
 *   is the value most commonly required by Gearhead to flag the package as
 *   Android-Auto-aware and start reporting `CONNECTION_TYPE_PROJECTION` via
 *   `CarConnection.getType()`. Side effect: the app may appear in the AA
 *   launcher drawer.
 * - `media` — media-app category (audio playback in AA).
 * - `notification` — silent registration; no AA UI surface but still flags the
 *   package as AA-aware for connection detection. Use this when the consumer
 *   app shouldn't appear in the AA drawer at all.
 */
export type AndroidAutoUsesName = 'template' | 'media' | 'notification';

export type BeaconAndroidPluginProps = {
  /**
   * Generate `BeaconGeoPlugin.kt` and register it in `MainApplication.kt` so
   * beacon and Android Auto events start/stop
   * `react-native-background-geolocation` natively. Requires
   * `react-native-background-geolocation` to be installed — set to `false`
   * when you don't use that library. Default: `true`.
   */
  backgroundGeolocation?: boolean;
  /**
   * Android Auto registration. When enabled (the default), the plugin injects
   * the `com.google.android.gms.car.application` meta-data into the consumer
   * app's `AndroidManifest.xml` and emits a matching
   * `res/xml/automotive_app_desc.xml` resource. This makes Gearhead recognise
   * the app, which is a prerequisite for `CarConnection.getType()` to ever
   * report `PROJECTION` — without it the LiveData silently stays at
   * `NOT_CONNECTED` and `onCarPlayConnected` never fires.
   *
   * Defaults: `{ register: true, usesName: 'template' }`.
   */
  androidAuto?: {
    /** Set to `false` to suppress the registration entirely (e.g. when you
     *  manage `automotive_app_desc.xml` yourself). Default: `true`. */
    register?: boolean;
    /** Category written into the generated XML. See {@link AndroidAutoUsesName}.
     *  Default: `'template'`. */
    usesName?: AndroidAutoUsesName;
  };
};

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

const AA_META_DATA_NAME = 'com.google.android.gms.car.application';
const AA_META_DATA_RESOURCE = '@xml/automotive_app_desc';
const AA_RES_FILE = 'automotive_app_desc.xml';

function getAutomotiveAppDescXml(usesName: AndroidAutoUsesName): string {
  return `<?xml version="1.0" encoding="utf-8"?>
<!--
  Generated by the expo-beacon config plugin. Marks this app as
  Android-Auto-aware so Gearhead reports CarConnection.PROJECTION to the
  process. Disable via the plugin's android.androidAuto.register: false
  option if you want to manage this resource yourself.
-->
<automotiveApp>
    <uses name="${usesName}"/>
</automotiveApp>
`;
}

/**
 * Inject the `com.google.android.gms.car.application` meta-data into the
 * consumer app's `<application>` element. Idempotent — `addMetaDataItemToMainApplication`
 * replaces an existing entry with the same name rather than duplicating it,
 * so re-runs of `expo prebuild` are safe. Skipped when the manifest already
 * declares the meta-data (presence implies the user is managing it manually).
 */
const withAndroidAutoMetaData: ConfigPlugin = (config) => {
  return withAndroidManifest(config, (config) => {
    const app = AndroidConfig.Manifest.getMainApplicationOrThrow(
      config.modResults,
    );
    const existing =
      AndroidConfig.Manifest.getMainApplicationMetaDataValue(
        config.modResults,
        AA_META_DATA_NAME,
      );
    if (existing != null) {
      // User-managed entry; leave it alone.
      return config;
    }
    AndroidConfig.Manifest.addMetaDataItemToMainApplication(
      app,
      AA_META_DATA_NAME,
      AA_META_DATA_RESOURCE,
      'resource',
    );
    return config;
  });
};

/**
 * Emit `android/app/src/main/res/xml/automotive_app_desc.xml`. Never
 * overwrites an existing file — if the consumer hand-authored the resource
 * (e.g. with multiple `<uses>` children), we respect it and log a notice.
 */
function withAndroidAutoDescriptor(usesName: AndroidAutoUsesName): ConfigPlugin {
  return (config) => withDangerousMod(config, [
    'android',
    (config) => {
      const xmlDir = path.join(
        config.modRequest.platformProjectRoot,
        'app/src/main/res/xml',
      );
      const xmlPath = path.join(xmlDir, AA_RES_FILE);
      if (fs.existsSync(xmlPath)) {
        // Don't clobber a user-managed resource.
        return config;
      }
      try {
        fs.mkdirSync(xmlDir, { recursive: true });
        fs.writeFileSync(xmlPath, getAutomotiveAppDescXml(usesName));
      } catch (err) {
        console.warn(
          `[expo-beacon] Failed to write ${AA_RES_FILE}: ${String(err)}`,
        );
      }
      return config;
    },
  ]);
}

/**
 * Write BeaconGeoPlugin.kt and register it in MainApplication.kt. Gated by
 * the `backgroundGeolocation` plugin prop (default: enabled).
 */
const withBeaconGeoPlugin: ConfigPlugin = (config) => {
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

  // Step 2 – patch MainApplication (Kotlin only) to call register().
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

      // The injected import + registration use Kotlin syntax and would corrupt
      // a Java MainApplication — leave Java projects untouched.
      if (mainAppPath.endsWith('.java')) {
        console.warn(
          '[expo-beacon] MainApplication.java (Java) cannot be patched automatically — ' +
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

const withBeaconAndroid: ConfigPlugin<BeaconAndroidPluginProps | void> = (
  config,
  props,
) => {
  const opts: BeaconAndroidPluginProps = props ?? {};
  const aaRegister = opts.androidAuto?.register ?? true;
  const aaUsesName: AndroidAutoUsesName = opts.androidAuto?.usesName ?? 'template';

  // Steps 1–2 – BeaconGeoPlugin generation + MainApplication registration
  // (react-native-background-geolocation integration). Opt-out via
  // `backgroundGeolocation: false`.
  if (opts.backgroundGeolocation ?? true) {
    config = withBeaconGeoPlugin(config);
  }

  // Step 3 – register the consumer app as Android-Auto-aware so Gearhead
  // surfaces CarConnection.PROJECTION to our CarPlayMonitor. Opt-out via
  // `androidAuto.register: false` for advanced consumers managing this
  // themselves.
  if (aaRegister) {
    config = withAndroidAutoMetaData(config);
    config = withAndroidAutoDescriptor(aaUsesName)(config);
  }

  return config;
};

export default withBeaconAndroid;
