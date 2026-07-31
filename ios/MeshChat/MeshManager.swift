import Foundation
import CoreBluetooth

/// Core BLE mesh flooding engine — mirrors the Android BleMeshManager protocol
/// exactly, so Android and iPhone devices can relay to each other.
///
/// Packet layout (single characteristic write):
///   [0..15]  message UUID (16 bytes)     -> dedup / loop prevention
///   [16]     TTL (1 byte)                -> decremented per hop
///   [17..24] timestamp millis (8 bytes, big-endian)
///   [25..]   UTF-8 text payload
///
/// Every device is both a peripheral (GATT server others write into) and a
/// central (scans for peers, connects, writes outgoing/relayed messages).
final class MeshManager: NSObject {

    // MUST match the UUIDs used in the Android BleMeshManager.
    static let serviceUUID = CBUUID(string: "5A1E0001-92C4-4C53-A3F1-2E7D0F6B9C10")
    static let messageCharUUID = CBUUID(string: "5A1E0002-92C4-4C53-A3F1-2E7D0F6B9C10")

    static let defaultTTL: UInt8 = 5
    static let maxSeenCache = 500

    weak var delegate: MeshManagerDelegate?

    private var centralManager: CBCentralManager!
    private var peripheralManager: CBPeripheralManager!

    private var messageCharacteristic: CBMutableCharacteristic!

    // Connected peripherals we can write into, keyed by identifier string
    private var connectedPeripherals: [String: CBPeripheral] = [:]
    private var discoveredCharacteristics: [String: CBCharacteristic] = [:]

    // Dedup cache — simple ordered set with FIFO eviction
    private var seenMessages: [String] = []
    private var seenSet: Set<String> = []

    override init() {
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: nil)
        peripheralManager = CBPeripheralManager(delegate: self, queue: nil)
    }

    // MARK: - Public API

    func sendMessage(_ text: String) {
        let id = UUID()
        let timestamp = Int64(Date().timeIntervalSince1970 * 1000)
        markSeen(id)
        delegate?.meshManager(self, didReceiveMessage: text, timestamp: timestamp, hopRelayed: false)
        let packet = buildPacket(id: id, ttl: Self.defaultTTL, timestamp: timestamp, text: text)
        broadcast(packet)
    }

    func stop() {
        centralManager.stopScan()
        peripheralManager.stopAdvertising()
        for (_, peripheral) in connectedPeripherals {
            centralManager.cancelPeripheralConnection(peripheral)
        }
        connectedPeripherals.removeAll()
        discoveredCharacteristics.removeAll()
    }

    // MARK: - Packet build / parse

    private func buildPacket(id: UUID, ttl: UInt8, timestamp: Int64, text: String) -> Data {
        var data = Data()
        data.append(contentsOf: id.uuidBytes)
        data.append(ttl)
        var tsBE = timestamp.bigEndian
        withUnsafeBytes(of: &tsBE) { data.append(contentsOf: $0) }
        data.append(text.data(using: .utf8) ?? Data())
        return data
    }

    private func handleIncomingPacket(_ data: Data) {
        guard data.count >= 25 else { return }
        let bytes = [UInt8](data)

        let idBytes = Array(bytes[0..<16])
        let id = UUID(uuidBytes: idBytes)

        let ttl = bytes[16]

        let tsBytes = Array(bytes[17..<25])
        let timestamp = tsBytes.withUnsafeBytes { $0.load(as: Int64.self).bigEndian }

        let textBytes = Array(bytes[25...])
        let text = String(bytes: textBytes, encoding: .utf8) ?? ""

        let idString = id.uuidString
        if seenSet.contains(idString) { return } // already processed, prevent loop
        markSeen(id)

        delegate?.meshManager(self, didReceiveMessage: text, timestamp: timestamp, hopRelayed: true)

        if ttl > 0 {
            let relayPacket = buildPacket(id: id, ttl: ttl - 1, timestamp: timestamp, text: text)
            broadcast(relayPacket)
        }
    }

    private func broadcast(_ packet: Data) {
        for (identifier, peripheral) in connectedPeripherals {
            guard let characteristic = discoveredCharacteristics[identifier] else { continue }
            peripheral.writeValue(packet, for: characteristic, type: .withoutResponse)
        }
    }

    private func markSeen(_ id: UUID) {
        let s = id.uuidString
        seenSet.insert(s)
        seenMessages.append(s)
        if seenMessages.count > Self.maxSeenCache {
            let removed = seenMessages.removeFirst()
            seenSet.remove(removed)
        }
    }
}

protocol MeshManagerDelegate: AnyObject {
    func meshManager(_ manager: MeshManager, didReceiveMessage text: String, timestamp: Int64, hopRelayed: Bool)
    func meshManager(_ manager: MeshManager, statusChanged status: String)
    func meshManager(_ manager: MeshManager, peerCountChanged count: Int)
}

// MARK: - CBPeripheralManagerDelegate (GATT server / advertising role)

extension MeshManager: CBPeripheralManagerDelegate {
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        guard peripheral.state == .poweredOn else {
            delegate?.meshManager(self, statusChanged: "Bluetooth is off")
            return
        }

        let characteristic = CBMutableCharacteristic(
            type: Self.messageCharUUID,
            properties: [.write, .writeWithoutResponse],
            value: nil,
            permissions: [.writeable]
        )
        messageCharacteristic = characteristic

        let service = CBMutableService(type: Self.serviceUUID, primary: true)
        service.characteristics = [characteristic]
        peripheral.add(service)

        peripheral.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [Self.serviceUUID]
        ])

        delegate?.meshManager(self, statusChanged: "Mesh active")

        if centralManager.state == .poweredOn {
            startScanning()
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for request in requests {
            if request.characteristic.uuid == Self.messageCharUUID, let value = request.value {
                handleIncomingPacket(value)
            }
            peripheral.respond(to: request, withResult: .success)
        }
    }
}

// MARK: - CBCentralManagerDelegate (scanning / connecting role)

extension MeshManager: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard central.state == .poweredOn else { return }
        if peripheralManager.state == .poweredOn {
            startScanning()
        }
    }

    private func startScanning() {
        centralManager.scanForPeripherals(withServices: [Self.serviceUUID], options: nil)
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let identifier = peripheral.identifier.uuidString
        guard connectedPeripherals[identifier] == nil else { return }
        peripheral.delegate = self
        centralManager.connect(peripheral, options: nil)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices([Self.serviceUUID])
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        let identifier = peripheral.identifier.uuidString
        connectedPeripherals.removeValue(forKey: identifier)
        discoveredCharacteristics.removeValue(forKey: identifier)
        delegate?.meshManager(self, peerCountChanged: connectedPeripherals.count)
    }
}

// MARK: - CBPeripheralDelegate (discover characteristic on connected peers)

extension MeshManager: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let services = peripheral.services else { return }
        for service in services where service.uuid == Self.serviceUUID {
            peripheral.discoverCharacteristics([Self.messageCharUUID], for: service)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let characteristics = service.characteristics else { return }
        for characteristic in characteristics where characteristic.uuid == Self.messageCharUUID {
            let identifier = peripheral.identifier.uuidString
            connectedPeripherals[identifier] = peripheral
            discoveredCharacteristics[identifier] = characteristic
            delegate?.meshManager(self, peerCountChanged: connectedPeripherals.count)
        }
    }
}

// MARK: - UUID byte helpers

private extension UUID {
    var uuidBytes: [UInt8] {
        let u = self.uuid
        return [u.0, u.1, u.2, u.3, u.4, u.5, u.6, u.7, u.8, u.9, u.10, u.11, u.12, u.13, u.14, u.15]
    }

    init(uuidBytes bytes: [UInt8]) {
        precondition(bytes.count == 16)
        let u: uuid_t = (bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
                          bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15])
        self.init(uuid: u)
    }
}
