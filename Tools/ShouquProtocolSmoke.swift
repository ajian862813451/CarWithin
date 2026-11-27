import Foundation

private enum Failure: Error { case failed(String) }
private func expect(_ value: @autoclosure () throws -> Bool, _ text: String) throws {
    if try !value() { throw Failure.failed(text) }
}
private final class Box<T>: @unchecked Sendable {
    private let lock = NSLock(); private var value: T
    init(_ value:T) { self.value = value }
    func get() -> T { lock.lock(); defer { lock.unlock() }; return value }
    func set(_ value:T) { lock.lock(); self.value = value; lock.unlock() }
}
@main struct ShouquTests {
    static func main() throws {
        let hello = try ShouquProtocol.hello
        try expect(hello.count == 81, "hello must match 81-byte capture")
        try expect(Array(hello.prefix(16)) == [0x10,0,0,0x70,0x51,0,0,0,0x41,0,0,0x70,0,0,0,0], "independent hello header fixture")
        let view = try ShouquProtocol.viewHandshake(Data([0xe0,1,0xd0,0]))
        let expected = Data([1,0,0,0,0xe0,1,0xd0,0,1,0,0,0])
        try expect(view == expected, "view identity echo fixture")
        let wire = try TailingProtocol.auxiliary(0x11,payload:view)
        try expect(wire == Data([0x11,0,0x0c,0,0,0,0,0]) + expected, "10711 full ACK fixture")
        try expect(try ShouquProtocol.viewHandshake(Data([9,8,7,6])) == Data([1,0,0,0,9,8,7,6,1,0,0,0]), "opaque handshake echo must not be mistaken for resolution")
        var rejected = false
        do { _ = try ShouquProtocol.viewHandshake(Data([0xe0,1,0x10])) } catch { rejected = true }
        try expect(rejected, "truncated echo rejected")
        rejected = false
        do { _ = try ShouquProtocol.imageResponse(Data([1,2,3,4])) } catch { rejected = true }
        try expect(rejected, "bad JPEG rejected")
        let jpeg = Data([255,216,255,217])
        try expect(try ShouquProtocol.imageResponse(nil) == Data([0,0,0,0]), "no new frame sends zero")
        try expect(try ShouquProtocol.imageResponse(jpeg) == Data([4,0,0,0])+jpeg, "JPEG length prefix")
        rejected = false
        do { _ = try ShouquProtocol.imageResponse(Data([255,216])+Data(repeating:0,count:ShouquProtocol.maximumJPEGBytes)+Data([255,217])) } catch { rejected = true }
        try expect(rejected,"oversized JPEG rejected")
        try session(jpeg)
        try cancelledStartup()
        print("PASS Shouqu: captured byte fixtures, dimensions, fragmented hello, multiple reverse controls, fresh RSA HUID proof, 10711 handshake/view/heartbeat, JPEG polling/static dedupe, initialization, stop and restart")
    }
    static func cancelledStartup() throws {
        let sockets = try (0..<4).map { _ in try TailingSocket.listening(localIP:"127.0.0.1",port:0) }
        let ports = try sockets.map { try $0.localPort }; sockets.dropFirst().forEach { $0.close() }
        defer { sockets.forEach { $0.close() } }
        let identity = try TailingIdentity.generate()
        let connection = ShouquConnection(local:"127.0.0.1",target:"127.0.0.1",
            ports:ShouquPorts(control:ports[1],view:ports[2],image:ports[3],hello:ports[0]),identityProvider:{identity},log:{_ in})
        let done = DispatchSemaphore(value:0), probed = DispatchSemaphore(value:0)
        let failed = Box(false)
        DispatchQueue.global().async {
            do {
                let s = try sockets[0].accept(remoteIP:"127.0.0.1"); defer { s.close() }
                _ = try TailingProtocol.readControl { try s.readExactly($0) }
                try s.write(TailingProtocol.control(0x70000011,payload:Data("{\"status\":true}".utf8)))
                probed.signal()
            } catch { probed.signal() }
        }
        DispatchQueue.global().async {
            do { _ = try connection.start() } catch { failed.set(true) }
            done.signal()
        }
        try expect(probed.wait(timeout:.now()+4) == .success,"startup probe reached")
        connection.close()
        try expect(done.wait(timeout:.now()+2) == .success && failed.get(),"stop wakes incomplete handshake")
    }
    static func session(_ jpeg:Data) throws {
        let local = "127.0.0.1"
        // Reserve all four ephemeral ports together, never bind the user's actual instrument ports.
        let reservations = try (0..<4).map { _ in try TailingSocket.listening(localIP:local,port:0) }
        let numbers = try reservations.map { try $0.localPort }
        let hello = reservations[0]
        let ports = ShouquPorts(control:numbers[1],view:numbers[2],image:numbers[3],hello:numbers[0])
        reservations.dropFirst().forEach { $0.close() }
        defer { reservations.forEach { $0.close() } }
        let identity = try TailingIdentity.generate()
        let connection = ShouquConnection(local:local,target:local,ports:ports,identityProvider:{ identity },log:{ _ in })
        defer { connection.close() }
        let done = DispatchSemaphore(value:0), ready = DispatchSemaphore(value:0), offered = DispatchSemaphore(value:0)
        let peerError = Box<Error?>(nil)
        DispatchQueue.global(qos:.userInitiated).async {
            var sockets:[TailingSocket] = []
            defer { sockets.forEach { $0.close() }; done.signal() }
            func open(_ port:UInt16) throws -> TailingSocket {
                let s = try TailingSocket.create(); sockets.append(s)
                try s.connect(localIP:local,remoteIP:local,port:port); return s
            }
            func readA(_ s:TailingSocket) throws -> TailingProtocol.Control { try TailingProtocol.readControl { try s.readExactly($0,timeout:5) } }
            func sendA(_ s:TailingSocket,_ command:UInt32,_ object:[String:Any]? = nil,_ seq:UInt32 = 77) throws {
                let payload = try object.map(TailingProtocol.json) ?? Data()
                try s.write(TailingProtocol.control(command,identifier:seq,payload:payload))
            }
            func exchangeCaps(_ s:TailingSocket,_ first:Bool) throws {
                try sendA(s,0x10000)
                let ack = try readA(s); try expect(ack.command == 0x10001 && ack.identifier == 77,"control negotiation echo")
                try sendA(s,0x10010,["HUID":"fixture-shouqu-identity","carModel":"fcm362k","carConfig":"Plus"])
                if first { try expect(try readA(s).command == 0x70000020,"initial identity confirm") }
                let caps = try readA(s)
                try expect(caps.command == 0x10011 && caps.identifier == 77,"capabilities reply")
                let object = try TailingProtocol.object(caps.payload)
                try expect(object["package"] as? String == "com.skymotor.wujie","client identifier")
                guard let encoded = object["encryptedHUID"] as? String, let proof = Data(base64Encoded:encoded) else { throw Failure.failed("proof missing") }
                try identity.verify(proof,huid:"fixture-shouqu-identity")
                try expect(object["phoneUUID"] as? String == identity.phoneID,"identity is local, not captured")
            }
            do {
                let probe = try hello.accept(remoteIP:local); sockets.append(probe)
                let request = try readA(probe)
                try expect(request.command == 0x70000010,"hello opcode")
                let reply = try TailingProtocol.control(0x70000011,payload:Data("{\n \"status\": true\n}".utf8))
                try probe.write(Data(reply.prefix(16))); Thread.sleep(forTimeInterval:0.015)
                try probe.write(Data(reply.dropFirst(16))); probe.close()
                let first = try open(ports.control); try exchangeCaps(first,true); first.close()
                let ctl = try open(ports.control); try exchangeCaps(ctl,false)
                try sendA(ctl,0x20000)
                try expect(try readA(ctl).command == 0x20001,"session ACK")
                let media = try open(ports.view)
                var body = Data([0xe0,1,0xd0,0]);body.append(Data(repeating:0,count:28))
                body.append(Data(try identity.sign("fixture-shouqu-identity").base64EncodedString().utf8))
                try media.write(TailingProtocol.auxiliary(0x10,token:9,payload:body))
                let response = try TailingProtocol.readAuxiliary { try media.readExactly($0) }
                try expect(response.command == 0x11 && response.token == 9 && response.payload == Data([1,0,0,0,0xe0,1,0xd0,0,1,0,0,0]),"identity view ACK")
                try media.write(TailingProtocol.auxiliary(0x60,payload:TailingProtocol.json(["viewAreaConfig":["viewAreas":[["width":480,"height":208,"safeArea":["width":336,"height":208]]]]])))
                let config = try TailingProtocol.readAuxiliary { try media.readExactly($0) }
                try expect(config.command == 0x61 && config.payload == Data("{\"viewAreaConfig\":{\"state\":0}}".utf8),"compact view ACK")
                for command:UInt16 in [0x40,0x70] {
                    try media.write(TailingProtocol.auxiliary(command))
                    let pong = try TailingProtocol.readAuxiliary { try media.readExactly($0) }
                    try expect(pong.command == command+1 && pong.payload.isEmpty,"10711 heartbeat")
                }
                let image = try open(ports.image)
                ready.signal()
                _ = offered.wait(timeout:.now()+4)
                for expectedLength in [jpeg.count,0] {
                    try image.write(Data([0x72,0,0,0,0,0,0,0]))
                    let length = Int(try image.readExactly(4).uint32LE(0))
                    try expect(length == expectedLength,"new frame then empty poll")
                    if length > 0 { try expect(try image.readExactly(length) == jpeg,"frame bytes") }
                }
                // A repeated identical frame is not re-sent.
                try connection.send(jpeg)
                try image.write(Data([0x72,0,0,0,0,0,0,0]))
                try expect(try image.readExactly(4) == Data([0,0,0,0]),"static frame dedupe")
                var commands = Set<UInt32>()
                for _ in 0..<11 {
                    let f = try readA(ctl); commands.insert(f.command)
                    if f.command == 0x70000000 { try sendA(ctl,0x70000001,nil,f.identifier) }
                    if commands.contains(0x200e0) && commands.contains(0x70000000) { break }
                }
                try expect(Set<UInt32>([0x204c0,0x20490,0x20020,0x20210,0x201e0,0x200e0,0x70000000]).isSubset(of:commands),"initialization sequence and ping")
            } catch { peerError.set(error) }
        }
        let size = try connection.start()
        try expect(size.0 == 480 && size.1 == 208,"ready only after all channels")
        try expect(ready.wait(timeout:.now()+2) == .success,"reverse image ready")
        try connection.send(jpeg); offered.signal()
        try expect(done.wait(timeout:.now()+8) == .success,"simulator ended")
        if let error = peerError.get() { throw error }
        connection.close()
        var failed = false
        do { try connection.send(jpeg) } catch { failed = true }
        try expect(failed,"sending after stop rejected")
        // Ports must be released for immediate user retry.
        let restarted = try [ports.control,ports.view,ports.image].map { try TailingSocket.listening(localIP:local,port:$0) }
        restarted.forEach { $0.close() }
    }
}
