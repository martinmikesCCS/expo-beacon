import CoreBluetooth

// MARK: - CBCentralManagerDelegate (Eddystone BLE scanning)

internal final class BluetoothDelegate: NSObject, CBCentralManagerDelegate {
    private weak var module: ExpoBeaconModule?

    init(module: ExpoBeaconModule) {
        self.module = module
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard let module, !module.isModuleDestroyed else { return }
        switch central.state {
        case .poweredOn:
            module.handleBluetoothPoweredOn()
        case .unauthorized:
            print("[ExpoBeacon] Bluetooth authorization denied — Eddystone scanning/monitoring unavailable. " +
                  "Ensure NSBluetoothAlwaysUsageDescription is set in Info.plist.")
            module.handleBluetoothStateError(code: "BLUETOOTH_UNAUTHORIZED", message: "Bluetooth authorization denied — Eddystone scanning/monitoring unavailable")
            module.failEddystoneScan(code: "BLUETOOTH_UNAUTHORIZED", message: "Bluetooth permission denied")
        case .poweredOff:
            print("[ExpoBeacon] Bluetooth is powered off — Eddystone scanning/monitoring unavailable.")
            module.handleBluetoothStateError(code: "BLUETOOTH_OFF", message: "Bluetooth is powered off — Eddystone scanning/monitoring unavailable")
            module.failEddystoneScan(code: "BLUETOOTH_OFF", message: "Bluetooth is powered off")
        case .unsupported:
            module.handleBluetoothStateError(code: "BLUETOOTH_UNSUPPORTED", message: "Bluetooth LE is not supported on this device")
            module.failEddystoneScan(code: "BLUETOOTH_UNSUPPORTED", message: "Bluetooth LE is not supported on this device")
        default:
            break
        }
    }

    func centralManager(_ central: CBCentralManager,
                         didDiscover peripheral: CBPeripheral,
                         advertisementData: [String: Any],
                         rssi RSSI: NSNumber) {
        guard let module, !module.isModuleDestroyed else { return }
        module.handleEddystoneDiscovery(advertisementData: advertisementData, rssi: RSSI)
    }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        // State restoration: CBCentralManager was recreated by iOS after app was killed.
        // Scanning will be re-started in centralManagerDidUpdateState when state is .poweredOn.
    }
}
