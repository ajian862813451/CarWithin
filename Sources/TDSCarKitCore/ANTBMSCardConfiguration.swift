import Foundation

/// Shared card content and scale for the phone and instrument video.
public struct ANTBMSCardConfiguration: Equatable, Sendable {
    public enum Field: String, CaseIterable, Sendable {
        case charge, voltage, temperature, current, power, cellDelta, capacity

        public var title: String {
            switch self {
            case .charge: "电量"
            case .voltage: "总压"
            case .temperature: "最高温度"
            case .current: "电流"
            case .power: "功率"
            case .cellDelta: "单体压差"
            case .capacity: "容量"
            }
        }

        public func text(_ reading: ANTBMSReading) -> String {
            if [.charge, .voltage, .current, .power, .capacity].contains(self), !reading.available.contains(rawValue) {
                return self == .charge ? "--%" : self == .voltage ? "--V" : self == .current ? "--A" : self == .power ? "--W" : "--/--A"
            }
            switch self {
            case .charge: return "\(reading.stateOfCharge)%"
            case .voltage: return String(format: "%.2fV", reading.voltage)
            case .temperature: return reading.maximumTemperature.map { String(format: "%.0f°C", $0) } ?? "--°C"
            case .current: return String(format: "%+.1fA", reading.current)
            case .power: return "\(reading.power)W"
            case .cellDelta: return "压差 " + (reading.cellDelta.map { String(format: "%.0fmV", $0 * 1000) } ?? "--mV")
            case .capacity:
                // ANT BMS already parses capacityAh (total) and remainingAh
                // (current) from the protocol. Show remaining/total in the
                // compact form requested for the dashboard card.
                // ANT 协议已解析总容量 capacityAh 与当前容量 remainingAh；
                // 按小程序样式显示「当前/总容量」，例如 116.79/210.0A。
                guard reading.available.contains("capacity"),
                      reading.available.contains("remaining") else { return "--/--A" }
                return String(format: "%.2f/%.1fA", reading.remainingAh, reading.capacityAh)
            }
        }
    }

    public enum Size: Int, CaseIterable, Sendable {
        case small, medium, large
        public var title: String { ["小", "中", "大"][rawValue] }
        /// Both compositors use this fraction of their video width.
        public var widthFraction: Double { [0.36, 0.46, 0.58][rawValue] }
    }

    /// Keep the two original right-side values so existing preferences migrate.
    public enum Position: Int, CaseIterable, Sendable {
        case topRight, bottomRight, topLeft, bottomLeft
        public var title: String { ["右上角", "右下角", "左上角", "左下角"][rawValue] }
        public var isAtTop: Bool { self == .topRight || self == .topLeft }
        public var isAtRight: Bool { self == .topRight || self == .bottomRight }
    }

    public enum Background: Int, CaseIterable, Sendable {
        case light, standard, opaque
        public var title: String { ["轻透", "标准", "不透明"][rawValue] }
        public var opacity: Double { [0.45, 0.86, 1][rawValue] }
    }

    /// Capacity is included in the compact preset so riders can see the
    /// remaining/total Ah without enabling every telemetry field.
    /// 容量加入紧凑预设，用户不用打开全部字段也能看到剩余/总容量。
    public static let compactFields: [Field] = [.charge, .voltage, .cellDelta, .capacity]
    public private(set) var fields: [Field]
    public var size: Size
    public var position: Position
    public var background: Background

    /// Missing/invalid saved preferences preserve the original six-field card.
    public init(savedFields: [String]? = nil, sizeRawValue: Int? = nil,
                positionRawValue: Int? = nil, backgroundRawValue: Int? = nil) {
        var seen = Set<Field>()
        let normalized = (savedFields?.compactMap(Field.init(rawValue:)) ?? Field.allCases)
            .filter { seen.insert($0).inserted }
        var migrated = normalized.isEmpty ? Field.allCases : normalized
        // Migration: existing users saved the six-field card before capacity
        // existed. Add the new field once so it is visible without having to
        // find it in the field switch list. New installs already get all cases.
        // 迁移：老用户保存的是旧的六字段配置；自动补一次容量字段，
        // 不需要用户自己去字段开关里找。新安装默认包含全部字段。
        if savedFields != nil, migrated.count >= 6, !migrated.contains(.capacity) {
            migrated.append(.capacity)
        }
        fields = migrated
        size = sizeRawValue.flatMap(Size.init(rawValue:)) ?? .medium
        position = positionRawValue.flatMap(Position.init(rawValue:)) ?? .topRight
        background = backgroundRawValue.flatMap(Background.init(rawValue:)) ?? .standard
    }

    /// Keep at least one metric visible, preserving the user's preferred order.
    @discardableResult
    public mutating func set(_ field: Field, visible: Bool) -> Bool {
        if !visible, fields == [field] { return false }
        if visible {
            if !fields.contains(field) { fields.append(field) }
        } else { fields.removeAll { $0 == field } }
        return true
    }

    public mutating func move(_ field: Field, to destination: Int) {
        guard let index = fields.firstIndex(of: field), fields.indices.contains(destination) else { return }
        fields.remove(at: index)
        fields.insert(field, at: destination)
    }

    public func lines(for reading: ANTBMSReading) -> [String] {
        let tokens = fields.map { $0.text(reading) }
        let split = tokens.count <= 3 ? tokens.count : (tokens.count + 1) / 2
        var lines = [reading.family.shortName + "  " + tokens.prefix(split).joined(separator: "  ")]
        if reading.hasAlarm { lines[0] += " · 告警" }
        if split < tokens.count { lines.append(tokens.dropFirst(split).joined(separator: "  ")) }
        return lines
    }
}
