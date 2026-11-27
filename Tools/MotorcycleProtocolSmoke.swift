import Foundation

private enum Failure: Error { case failed(String) }
private func expect(_ condition: @autoclosure () throws -> Bool, _ message: String) throws {
    if try !condition() { throw Failure.failed(message) }
}
private final class Box<T>: @unchecked Sendable {
    let lock = NSLock(); var value: T
    init(_ value: T) { self.value = value }
    func get() -> T { lock.lock(); defer { lock.unlock() }; return value }
    func set(_ value: T) { lock.lock(); self.value = value; lock.unlock() }
}
struct CfmotoProfile {
    let width: Int; let height: Int; let fps: Int; let bitrate: Int; let label: String; var discoversEasyConn = false
}
struct ConfigurationError: Error { let message: String; init(_ message: String) { self.message = message } }
final class ThinkerRideBLE {
    static let connected = DispatchSemaphore(value: 0)
    func connect(id: UUID, byteCat: Bool, ready: @escaping () -> Void, failure: @escaping (String) -> Void) { ready(); Self.connected.signal() }
    func stop() {}
}
@main struct MotorcycleProtocolTests {
    static func main() throws {
        try expect(MotorcycleProfile.allCases.count == 15, "profile list")
        try expect(MotorcycleProfile.kove625X.transport == .yunmo && MotorcycleProfile.kove450Rally.transport == .thinkerRide, "KOVE transports isolated")
        try expect(MotorcycleProfile.kove450Rally.byteCatBLE && !MotorcycleProfile.kove800X.byteCatBLE, "BLE dialect per model")
        try expect(MotorcycleProfile.benelliTRK.pxcProfile.discoversEasyConn, "new EasyConn discovery gated")
        let query = YunmoProtocol.simple(0xb0, Data([1, 0, 1]))
        try expect(query == Data([254,254,254,254,176,0,3,3,1,0,1,184]), "Yunmo query fixture")
        let data = Box(query)
        let frame = try YunmoProtocol.read { count in
            let pending = data.get(); guard pending.count >= count else { throw Failure.failed("truncated") }
            data.set(Data(pending.dropFirst(count))); return Data(pending.prefix(count))
        }
        try expect(frame.command == 0xb0 && frame.payload == Data([1,0,1]), "query decode")
        let shifted = Box(Data([0x12, 0x34, 0xfe, 0xfe]) + query)
        let shiftedFrame = try YunmoProtocol.read { n in
            let bytes = shifted.get(); guard bytes.count >= n else { throw Failure.failed("truncated resync") }
            shifted.set(Data(bytes.dropFirst(n))); return Data(bytes.prefix(n))
        }
        try expect(shiftedFrame.command == 0xb0 && shiftedFrame.payload == Data([1,0,1]), "noise and extra FE bytes resynchronized")
        var corrupt = query; corrupt[corrupt.count - 1] ^= 1
        let corruptBytes = Box(corrupt)
        var rejected = false
        do { _ = try YunmoProtocol.read { n in
            let bytes = corruptBytes.get(); corruptBytes.set(Data(bytes.dropFirst(n))); return Data(bytes.prefix(n))
        } } catch { rejected = true }
        try expect(rejected, "corrupt checksum rejected")
        let wire = try YunmoProtocol.jpeg(Data([255,216,255,217]), id: 0x12345678)
        try expect(wire.count == 72 && wire[4] == 0x1d && wire[15] == 0, "JPEG envelope")
        try expect(wire[5] == 0 && wire[6] == 2 && wire[8] == 4, "JPEG length")
        try expect(Array(wire[16..<20]) == [0x78,0x56,0x34,0x12], "JPEG frame ID")
        try expect(wire[12] == 175 && wire[13] == 3, "JPEG payload checksum")
        try expect(YunmoProtocol.dimensions(Data([0,0,0,0,2,128,1,224]))!.0 == 640, "native dimensions")
        try expect(YunmoProtocol.dimensions(Data([0,0,0,0,255,255,255,255])) == nil, "dimension bounds")
        try expect(YunmoProtocol.ack(Data([0,0x78,0x56,0x34,0x12])) == 0x12345678, "ACK ID")
        let vh = ThinkerRideProtocol.videoHeader(width: 1280, height: 640)
        try expect(vh.count == 69 && Array(vh[65..<69]) == [5,0,2,128], "Thinker landscape video header")
        let ph = ThinkerRideProtocol.videoHeader(width: 600, height: 1024)
        try expect(Array(ph[65..<69]) == [2,88,4,0], "Thinker portrait video header")
        try expect(ThinkerRideProtocol.handshake.count == 290, "Thinker binary handshake")
        var seq: UInt16 = 0xffff
        let cats = ThinkerRideProtocol.byteCat(Data(repeating: 65, count: 120), sequence: &seq)
        try expect(cats.count == 2 && cats[0].count == 104 && cats[0][1] == 255 && cats[1][1] == 0 && seq == 1, "byteCat split and sequence wrap")
        var stream = ThinkerRideProtocol.JSONStream()
        try expect(stream.append(Data("{\"act\":\"send_pair".utf8)).isEmpty, "fragment retained")
        let objects = stream.append(Data("result\",\"result\":1}{\"a\":\"}\\\"\"}".utf8))
        try expect(objects.count == 2 && (objects[0]["result"] as? Int) == 1, "fragment assembly and escaped quote")
        try eryaFlow()
        try socketFlow()
        try thinkerFlow()
        print("PASS motorcycle: Erya/Yunmo wire fixtures, bounds, JPEG, BLE framing, TCP negotiation, ACK backpressure, cancellation")
    }
    static func eryaFlow() throws {
        try expect(EryaProtocol.start() == Data([0xaa,0xbb,0xcc,4,0,0,0,0,4,0,0,0,0xe0,1,0x80,2]), "Erya independent start fixture")
        let wire = try EryaProtocol.jpeg(Data([255,216,255,217]), milliseconds: 0x12345678)
        try expect(wire == Data([0xaa,0xbb,0xcc,7,0,0,0,0,8,0,0,0,0x78,0x56,0x34,0x12,255,216,255,217]), "Erya JPEG fixture")
        let malformed = Box(Data([0xaa,0xbb,0xcc,15,0,0,0,0,255,255,255,255]))
        var rejected = false
        do { _ = try EryaProtocol.read { n in
            let b = malformed.get(); malformed.set(Data(b.dropFirst(n))); return Data(b.prefix(n))
        } } catch { rejected = true }
        try expect(rejected, "Erya oversized JSON rejected")
        let controlBytes = Box(Data([0x45,0x59,0,0,0,2,0,0,0,0,0x7b,0x7d]))
        let controlJSON = try EryaProtocol.readControl { n in
            let b = controlBytes.get(); controlBytes.set(Data(b.dropFirst(n))); return Data(b.prefix(n))
        }
        try expect(controlJSON.isEmpty, "Erya EY big endian JSON")
        for invalid in [Data([0xfe,0xfe]), Data([0x4f,0x4b,0,0,0,0,255,255,255,255,10,0xa4,1])] {
            let input = Box(invalid); var failed = false
            do { _ = try EryaProtocol.read { n in
                let b = input.get(); input.set(Data(b.dropFirst(n))); return Data(b.prefix(n))
            } } catch { failed = true }
            try expect(failed, "Erya rejects other protocol or invalid dimensions")
        }
        let control = try TailingSocket.listening(localIP: "127.0.0.1", port: 0)
        let video = try TailingSocket.listening(localIP: "127.0.0.1", port: 0)
        let retryVideoPort = try video.localPort
        video.close() // The peer opens video only after its control connection is accepted.
        let connection = EryaConnection(log: { _ in })
        defer { connection.close(); control.close(); video.close() }
        let mayAck = DispatchSemaphore(value: 0), done = DispatchSemaphore(value: 0)
        let peerError = Box<Error?>(nil)
        DispatchQueue.global().async {
            defer { done.signal() }
            do {
                let c = try control.accept(remoteIP: "127.0.0.1")
                Thread.sleep(forTimeInterval: 0.3)
                let delayedVideo = try TailingSocket.listening(localIP: "127.0.0.1", port: retryVideoPort)
                defer { delayedVideo.close() }
                let v = try delayedVideo.accept(remoteIP: "127.0.0.1")
                defer { c.close(); v.close() }
                try expect(v.readExactly(16) == EryaProtocol.start(), "Erya start on video TCP")
                // Split OK across writes to exercise exact TCP reads.
                try v.write(Data([0x4f])); try v.write(Data([0x4b,0xe0,1,0x80,2,0x80,2,0xe0,1,10,0xa4,1]))
                try expect(mayAck.wait(timeout: .now() + 3) == .success, "Erya ack gate")
                try v.write(Data([0xaa,0xbb,0xcc,15,0,0,0,0,9,0,0,0]) + Data("{\"ack\":1}".utf8))
                let jpeg = try v.readExactly(20)
                try expect(jpeg.prefix(12) == wire.prefix(12) && jpeg.suffix(4) == wire.suffix(4), "Erya frame on TCP")
                try expect(v.readExactly(12) == EryaProtocol.packet(5), "Erya stop packet")
            } catch { peerError.set(error) }
        }
        let size = try connection.start(local: "127.0.0.1", target: "127.0.0.1", videoPort: retryVideoPort, controlPort: control.localPort)
        try expect(size.0 == 640 && size.1 == 480, "Erya OK dimensions")
        let writable = DispatchSemaphore(value: 0), sendError = Box<Error?>(nil)
        DispatchQueue.global().async {
            defer { writable.signal() }
            do { try connection.waitWritable(); try connection.send(Data([255,216,255,217])) } catch { sendError.set(error) }
        }
        try expect(writable.wait(timeout: .now() + 0.1) == .timedOut, "Erya waits for first ACK")
        mayAck.signal()
        try expect(writable.wait(timeout: .now() + 3) == .success && sendError.get() == nil, "Erya ACK releases frame")
        let cancelled = DispatchSemaphore(value: 0)
        DispatchQueue.global().async { defer { cancelled.signal() }; do { try connection.waitWritable(); sendError.set(Failure.failed("extra permit")) } catch {} }
        try expect(cancelled.wait(timeout: .now() + 0.1) == .timedOut, "Erya consumes one permit")
        connection.close()
        try expect(cancelled.wait(timeout: .now() + 2) == .success, "Erya cancellation wakes producer")
        try expect(done.wait(timeout: .now() + 3) == .success && peerError.get() == nil && sendError.get() == nil, "Erya TCP teardown")
    }
    static func thinkerFlow() throws {
        let connection = ThinkerRideConnection(profile: .kove450Rally, log: { _ in })
        let failureBox = Box<Error?>(nil), started = DispatchSemaphore(value: 0)
        DispatchQueue.global().async {
            defer { started.signal() }
            do { _ = try connection.start(local: "127.0.0.1", target: "127.0.0.1", device: UUID()) }
            catch { failureBox.set(error) }
        }
        try expect(ThinkerRideBLE.connected.wait(timeout: .now() + 3) == .success, "listeners ready before BLE")
        let control = try TailingSocket.create(), heartbeat = try TailingSocket.create(), video = try TailingSocket.create()
        defer { connection.close(); control.close(); heartbeat.close(); video.close() }
        try control.connect(localIP: "127.0.0.1", remoteIP: "127.0.0.1", port: 17818)
        let opening = try control.readExactly(6)
        try expect(Array(opening.prefix(2)) == [0xee,0xfd], "Thinker TUC query")
        let length = opening.dropFirst(2).reduce(0) { ($0 << 8) | Int($1) }
        _ = try control.readExactly(length + 1)
        try control.write(ThinkerRideProtocol.keepalive)
        try expect(try control.readExactly(6) == ThinkerRideProtocol.keepalive, "control heartbeat echo")
        try expect(try control.readExactly(290) == ThinkerRideProtocol.handshake, "binary handshake after first packet")
        try heartbeat.connect(localIP: "127.0.0.1", remoteIP: "127.0.0.1", port: 15457)
        try expect(try heartbeat.readExactly(6, timeout: 2) == ThinkerRideProtocol.keepalive, "dedicated heartbeat")
        try video.connect(localIP: "127.0.0.1", remoteIP: "127.0.0.1", port: 15456)
        try expect(try video.readExactly(69) == ThinkerRideProtocol.videoHeader(width: 1280, height: 640), "TCP landscape header")
        try expect(started.wait(timeout: .now() + 2) == .success, "Thinker video ready")
        if let failure = failureBox.get() { throw failure }
        let sample = Data([0,0,0,1,0x65,0x88])
        try connection.send(sample)
        try expect(try video.readExactly(sample.count) == sample, "Annex-B transport")
        connection.close()
        var rejected = false
        do { try connection.send(sample) } catch { rejected = true }
        try expect(rejected, "Thinker stopped connection rejects frames")
    }
    static func socketFlow() throws {
        let server = try TailingSocket.listening(localIP: "127.0.0.1", port: 8200)
        let peerError = Box<Error?>(nil), frames = Box(0)
        let mayAck = DispatchSemaphore(value: 0), peerDone = DispatchSemaphore(value: 0)
        DispatchQueue.global().async {
            defer { peerDone.signal() }
            do {
                let socket = try server.accept(remoteIP: "127.0.0.1"); defer { socket.close() }
                let query = try YunmoProtocol.read { try socket.readExactly($0) }
                try expect(query.command == 0xb0, "TCP dimension query")
                let mode = try YunmoProtocol.read { try socket.readExactly($0) }
                try expect(mode.command == 0xa0 && mode.payload == Data([6]), "TCP mode request")
                try socket.write(YunmoProtocol.simple(0x32, Data([0,0,0,0,2,128,1,224])))
                try socket.write(YunmoProtocol.simple(0xa0, Data([6])))
                for i in 0..<3 {
                    let h = [UInt8](try socket.readExactly(40))
                    let length = (0..<4).reduce(0) { $0 | Int(h[8+$1]) << (8*$1) }
                    _ = try socket.readExactly((length + 31) / 32 * 32)
                    try expect(h[16] == i && h[15] == 0, "TCP media frame ID/type")
                    frames.set(i + 1)
                    if i == 1 { _ = mayAck.wait(timeout: .now() + 4); try socket.write(YunmoProtocol.simple(0xa0, Data([0,1,0,0,0]))) }
                }
                _ = try socket.readExactly(1, timeout: 4)
            } catch { peerError.set(error) }
        }
        let connection = YunmoConnection(log: { _ in }); defer { connection.close(); server.close() }
        let size = try connection.start(local: "127.0.0.1", target: "127.0.0.1")
        try expect(size.0 == 640 && size.1 == 480, "TCP negotiated size")
        try connection.send(Data([255,216,255,217])); try connection.send(Data([255,216,255,217]))
        let third = DispatchSemaphore(value: 0)
        DispatchQueue.global().async { do { try connection.send(Data([255,216,255,217])) } catch { peerError.set(error) }; third.signal() }
        try expect(third.wait(timeout: .now() + 0.2) == .timedOut, "window blocks third frame")
        mayAck.signal()
        try expect(third.wait(timeout: .now() + 3) == .success, "ACK releases window")
        try connection.send(Data([255,216,255,217]))
        let cancelled = DispatchSemaphore(value: 0)
        DispatchQueue.global().async { do { try connection.send(Data([1])) ; peerError.set(Failure.failed("send after close")) } catch { }; cancelled.signal() }
        Thread.sleep(forTimeInterval: 0.1); connection.close()
        try expect(cancelled.wait(timeout: .now() + 2) == .success, "close wakes blocked producer")
        _ = peerDone.wait(timeout: .now() + 2)
        // Server may stop reading after teardown; client cancellation must remain prompt.
        try expect(frames.get() == 3, "three frames received")
        if let error = peerError.get() { throw error }
    }
}
