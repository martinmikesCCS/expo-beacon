import {
  ConfigPlugin,
  withAppDelegate,
  withXcodeProject,
} from '@expo/config-plugins';
import * as fs from 'fs';
import * as path from 'path';

// ─── Generated Swift file ─────────────────────────────────────────────────────

export const IOS_PLUGIN_SWIFT = `\
import ExpoBeacon
import TSLocationManager

final class BeaconGeoPlugin: BeaconLifecycleDelegate {
    func beaconDidEnter(identifier: String, uuid: String, major: Int, minor: Int, distance: Double) {
        TSLocationManager.sharedManager().start()
    }
    func beaconDidExit(identifier: String, uuid: String, major: Int, minor: Int, distance: Double) {
        TSLocationManager.sharedManager().stop()
    }
    func eddystoneDidEnter(identifier: String, namespace: String, instance: String, distance: Double) {
        TSLocationManager.sharedManager().start()
    }
    func eddystoneDidExit(identifier: String, namespace: String, instance: String, distance: Double) {
        TSLocationManager.sharedManager().stop()
    }
}
`;

// ─── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Finds the UUID (key) of a PBXGroup by name, handling both quoted and unquoted
 * name values produced by node-xcode's parser.
 */
function findPBXGroupKeyByName(xcodeProject: any, name: string): string | null {
  const pbxGroups: Record<string, any> =
    xcodeProject.hash.project.objects['PBXGroup'] ?? {};
  for (const key of Object.keys(pbxGroups)) {
    if (key.endsWith('_comment')) continue;
    const group = pbxGroups[key];
    if (group.name === `"${name}"` || group.name === name) return key;
  }
  return null;
}

/** Returns true if a PBXFileReference for filename already exists (idempotency guard). */
function isFileInXcodeProject(xcodeProject: any, filename: string): boolean {
  const refs: Record<string, any> =
    xcodeProject.hash.project.objects['PBXFileReference'] ?? {};
  return Object.values(refs).some(
    (f) =>
      typeof f === 'object' &&
      f !== null &&
      (f.path === `"${filename}"` || f.path === filename),
  );
}

function modifySwiftAppDelegate(contents: string): string {
  // Insert `import ExpoBeacon` after the last existing import line.
  if (!contents.includes('import ExpoBeacon')) {
    const lines = contents.split('\n');
    const lastImportIdx = lines.reduce(
      (last, line, i) => (line.trimStart().startsWith('import ') ? i : last),
      -1,
    );
    if (lastImportIdx >= 0) {
      lines.splice(lastImportIdx + 1, 0, 'import ExpoBeacon');
      contents = lines.join('\n');
    }
  }

  // Insert `BeaconLifecycleRegistry.register(BeaconGeoPlugin())` immediately
  // before `return super.application(`, preserving indentation.
  const marker = 'BeaconLifecycleRegistry.register(BeaconGeoPlugin())';
  if (!contents.includes(marker)) {
    contents = contents.replace(
      /([ \t]*)(return super\.application\()/,
      `$1${marker}\n$1$2`,
    );
  }

  return contents;
}

// ─── Plugin ───────────────────────────────────────────────────────────────────

const withBeaconIOS: ConfigPlugin = (config) => {
  // Step 1 – write BeaconGeoPlugin.swift and add it to the Xcode project.
  config = withXcodeProject(config, (config) => {
    const xcodeProject = config.modResults;
    const { projectName, platformProjectRoot } = config.modRequest;

    if (!projectName || !platformProjectRoot) return config;

    const swiftFilePath = path.join(platformProjectRoot, projectName, 'BeaconGeoPlugin.swift');
    fs.writeFileSync(swiftFilePath, IOS_PLUGIN_SWIFT);

    if (!isFileInXcodeProject(xcodeProject, 'BeaconGeoPlugin.swift')) {
      const groupKey = findPBXGroupKeyByName(xcodeProject, projectName) ?? undefined;
      xcodeProject.addSourceFile(
        'BeaconGeoPlugin.swift',
        null,
        groupKey ?? undefined,
      );
    }

    return config;
  });

  // Step 2 – patch AppDelegate.swift to register the plugin before super.
  config = withAppDelegate(config, (config) => {
    if (config.modResults.language === 'swift') {
      config.modResults.contents = modifySwiftAppDelegate(config.modResults.contents);
    } else {
      console.warn(
        '[expo-beacon] withBeaconIOS: AppDelegate is not Swift — ' +
          'please add BeaconLifecycleRegistry.register(BeaconGeoPlugin()) manually.',
      );
    }
    return config;
  });

  return config;
};

export default withBeaconIOS;
