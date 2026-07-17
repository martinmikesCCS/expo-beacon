const mockNativeModule = {
  pairBeacon: jest.fn(),
  pairEddystone: jest.fn(),
  scanForBeaconsAsync: jest.fn(),
  scanForEddystonesAsync: jest.fn(),
};

jest.mock("../ExpoBeaconModule.js", () => ({
  __esModule: true,
  default: mockNativeModule,
}));

const {
  pairBeacon,
  pairEddystone,
  scanForBeacons,
  scanForEddystones,
} = require("../helpers");

describe("object-based public helpers", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("maps named scan options to the native positional API", async () => {
    mockNativeModule.scanForBeaconsAsync.mockResolvedValueOnce([]);
    mockNativeModule.scanForEddystonesAsync.mockResolvedValueOnce([]);
    const uuids = ["E2C56DB5-DFFB-48D2-B060-D0F5A71096E0"] as const;

    await scanForBeacons({ uuids, durationMs: 2_500 });
    await scanForEddystones({ durationMs: 3_000 });

    expect(mockNativeModule.scanForBeaconsAsync).toHaveBeenCalledWith(
      [...uuids],
      2_500,
    );
    expect(mockNativeModule.scanForEddystonesAsync).toHaveBeenCalledWith(3_000);
  });

  it("maps pairing objects to the native positional API", () => {
    pairBeacon({
      identifier: "lobby",
      uuid: "E2C56DB5-DFFB-48D2-B060-D0F5A71096E0",
      major: 1,
      minor: 2,
      name: "Lobby",
      timeoutSeconds: 30,
    });
    pairEddystone({
      identifier: "meeting-room",
      namespace: "edd1ebeac04e5defa017",
      instance: "0123456789ab",
      name: "Meeting room",
      timeoutSeconds: 60,
    });

    expect(mockNativeModule.pairBeacon).toHaveBeenCalledWith(
      "lobby",
      "E2C56DB5-DFFB-48D2-B060-D0F5A71096E0",
      1,
      2,
      "Lobby",
      30,
    );
    expect(mockNativeModule.pairEddystone).toHaveBeenCalledWith(
      "meeting-room",
      "edd1ebeac04e5defa017",
      "0123456789ab",
      "Meeting room",
      60,
    );
  });
});
