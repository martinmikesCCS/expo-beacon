import {
  __androidPluginInternals,
  getAndroidPluginKotlin,
} from "../../plugin/src/withBeaconAndroid";
import {
  __iosPluginInternals,
  getIOSPluginSwift,
} from "../../plugin/src/withBeaconIOS";

describe("generated background-geolocation plugins", () => {
  it("keeps Android tracking active until every beacon has exited", () => {
    const source = getAndroidPluginKotlin("com.example.app");

    expect(source).toContain("activeBeaconReasons");
    expect(source).toContain('setBeaconActive("ibeacon:$identifier", true)');
    expect(source).toContain('setBeaconActive("eddystone:$identifier", false)');
    expect(source).toContain("if (!trackingRequested)");
    expect(source).not.toContain("CarPlay");
  });

  it("keeps iOS tracking active until every beacon has exited", () => {
    const source = getIOSPluginSwift();

    expect(source).toContain("activeBeaconReasons");
    expect(source).toContain(
      'setBeaconActive("ibeacon:\\(identifier)", active: true)',
    );
    expect(source).toContain(
      'setBeaconActive("eddystone:\\(identifier)", active: false)',
    );
    expect(source).toContain("if !trackingRequested");
    expect(source).not.toContain("CarPlay");
  });

  it("injects into a custom iOS launch method instead of duplicating it", () => {
    const appDelegate = `import Expo\n\nclass AppDelegate: ExpoAppDelegate {\n  override func application(\n    _ application: UIApplication,\n    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil\n  ) -> Bool {\n    configureApp()\n    return true\n  }\n}\n`;

    const modified = __iosPluginInternals.modifyAppDelegateSwift(appDelegate);

    expect(modified.match(/didFinishLaunchingWithOptions/g)?.length).toBe(1);
    expect(modified).toContain(
      "BeaconLifecycleRegistry.register(BeaconGeoPlugin())",
    );
  });

  it("adds the fallback launch override inside AppDelegate", () => {
    const appDelegate = `import Expo\n\nclass AppDelegate: ExpoAppDelegate {\n  func configureApp() {}\n}\n\nextension AppDelegate {\n  func extensionMethod() {}\n}\n`;

    const modified = __iosPluginInternals.modifyAppDelegateSwift(appDelegate);
    expect(modified.indexOf("didFinishLaunchingWithOptions")).toBeGreaterThan(
      modified.indexOf("class AppDelegate"),
    );
    expect(modified.indexOf("didFinishLaunchingWithOptions")).toBeLessThan(
      modified.indexOf("extension AppDelegate"),
    );
  });

  it("patches a CRLF Android MainApplication", () => {
    const mainApplication =
      "package com.example\r\n\r\nclass MainApplication {\r\n  override fun onCreate() {\r\n    super.onCreate()\r\n  }\r\n}\r\n";

    const modified =
      __androidPluginInternals.modifyMainApplication(mainApplication);
    expect(modified).toContain(
      "BeaconPluginRegistry.register(BeaconGeoPlugin(this))",
    );
    expect(modified).toContain(
      "BeaconPluginRegistry.register(BeaconGeoPlugin(this))\r\n",
    );
  });

  it("preserves shared native imports while custom plugins still use them", () => {
    const androidApplication = `package com.example\n\nimport expo.modules.beacon.BeaconPluginRegistry // expo-beacon-generated\n\nclass MainApplication {\n  fun registerPlugins() {\n    BeaconPluginRegistry.register(BeaconGeoPlugin(this))\n    BeaconPluginRegistry.register(CustomBeaconPlugin(this))\n  }\n}\n`;
    const cleanedAndroid =
      __androidPluginInternals.unmodifyMainApplication(androidApplication);
    expect(cleanedAndroid).toContain(
      "import expo.modules.beacon.BeaconPluginRegistry",
    );
    expect(cleanedAndroid).toContain("CustomBeaconPlugin");

    const iosApplication = `import ExpoBeacon // expo-beacon-generated\n\nfinal class AppDelegate {\n  func registerPlugins() {\n    BeaconLifecycleRegistry.register(BeaconGeoPlugin())\n    BeaconLifecycleRegistry.register(CustomBeaconPlugin())\n  }\n}\n`;
    const cleanedIOS =
      __iosPluginInternals.unmodifyAppDelegateSwift(iosApplication);
    expect(cleanedIOS).toContain("import ExpoBeacon");
    expect(cleanedIOS).toContain("CustomBeaconPlugin");
  });

  it("removes only plugin-owned imports when no shared usage remains", () => {
    const cleanedAndroid = __androidPluginInternals.unmodifyMainApplication(
      "import expo.modules.beacon.BeaconPluginRegistry // expo-beacon-generated\nBeaconPluginRegistry.register(BeaconGeoPlugin(this))\n",
    );
    expect(cleanedAndroid).not.toContain("BeaconPluginRegistry");

    const cleanedIOS = __iosPluginInternals.unmodifyAppDelegateSwift(
      "import ExpoBeacon // expo-beacon-generated\nBeaconLifecycleRegistry.register(BeaconGeoPlugin())\n",
    );
    expect(cleanedIOS).not.toContain("ExpoBeacon");
    expect(cleanedIOS).not.toContain("BeaconLifecycleRegistry");
  });

  it("honors explicit iOS permission text while preserving host defaults", () => {
    const explicit = __iosPluginInternals.applyBeaconInfoPlist(
      {
        NSLocationWhenInUseUsageDescription: "Old location text",
        NSBluetoothAlwaysUsageDescription: "Old Bluetooth text",
      },
      {
        locationWhenInUsePermission: "New location text",
        bluetoothPermission: "New Bluetooth text",
      },
    );
    expect(explicit.NSLocationWhenInUseUsageDescription).toBe(
      "New location text",
    );
    expect(explicit.NSBluetoothAlwaysUsageDescription).toBe(
      "New Bluetooth text",
    );

    const preserved = __iosPluginInternals.applyBeaconInfoPlist(
      { NSLocationWhenInUseUsageDescription: "Host location text" },
      {},
    );
    expect(preserved.NSLocationWhenInUseUsageDescription).toBe(
      "Host location text",
    );
  });
});
