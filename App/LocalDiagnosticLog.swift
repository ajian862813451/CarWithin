import Foundation

/// Bounded, on-device diagnostics. No network upload; exclude logs from backups.
enum LocalDiagnosticLog {
    private static let queue = DispatchQueue(label: "CarWithin.diagnostic-log", qos: .utility)
    private static let maximumBytes = 512 * 1024
    private static var directory: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Diagnostics", isDirectory: true)
    }
    static func append(_ line: String) {
        queue.async {
            do {
                var folder = directory
                try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                var values = URLResourceValues(); values.isExcludedFromBackup = true
                try folder.setResourceValues(values)
                let file = folder.appendingPathComponent("current.log")
                if let size = (try? file.resourceValues(forKeys: [.fileSizeKey]))?.fileSize, size >= maximumBytes {
                    let previous = folder.appendingPathComponent("previous.log")
                    try? FileManager.default.removeItem(at: previous)
                    try FileManager.default.moveItem(at: file, to: previous)
                }
                // Defensive redaction for accidentally interpolated credentials in future logs.
                let clean = line.replacingOccurrences(of: #"(?i)(password|pwd|privateKey|pairingKey)\s*[=:]\s*[^\s,}]+"#, with: "$1=<redacted>", options: .regularExpression)
                let bytes = Data((clean.prefix(8192) + "\n").utf8)
                if !FileManager.default.fileExists(atPath: file.path) {
                    try bytes.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
                } else {
                    let handle = try FileHandle(forWritingTo: file); defer { try? handle.close() }
                    try handle.seekToEnd(); try handle.write(contentsOf: bytes)
                }
            } catch { /* Diagnostics must not stop a projection session. */ }
        }
    }
    static func export(_ completion: @escaping (URL?) -> Void) {
        queue.async {
            let content = ["previous.log", "current.log"].compactMap { try? Data(contentsOf: directory.appendingPathComponent($0)) }.reduce(Data(), +)
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("CarWithin-diagnostics.txt")
            do { try content.write(to: url, options: .atomic); DispatchQueue.main.async { completion(url) } }
            catch { DispatchQueue.main.async { completion(nil) } }
        }
    }
}
