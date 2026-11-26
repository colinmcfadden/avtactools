import CoreJS

/// A map from text to values that keeps the order its keys were added in, as the Kotlin port's `Map` (a LinkedHashMap) does: a
/// route's ground elevations and per-point values, a workspace's diagrams. Swift's `Dictionary` has no order, so a document written
/// from one would come out in a different order each run.
///
/// Keys are compared by their UTF-16 units, as Kotlin and JavaScript compare text (a `[String: …]` takes `"é"` and `"e\u{301}"` for
/// one key). Setting a key that is there keeps its place and replaces its value, as `LinkedHashMap.put` does. Two maps are equal
/// when they hold the same keys and values, in any order, as Kotlin's maps are.
public struct OrderedMap<Value> {
    public private(set) var keys: [String] = []
    private var storage: [UTF16Key: Value] = [:]

    public init() {}

    /// The pairs in order; a key given twice keeps its first place and its last value.
    public init(_ pairs: some Sequence<(String, Value)>) {
        for (key, value) in pairs { self[key] = value }
    }

    public var count: Int { keys.count }
    public var isEmpty: Bool { keys.isEmpty }

    /// The value at `key`; setting nil removes it.
    public subscript(key: String) -> Value? {
        get { storage[UTF16Key(key)] }
        set {
            let k = UTF16Key(key)
            if let newValue {
                if storage.updateValue(newValue, forKey: k) == nil { keys.append(key) }
            } else if storage.removeValue(forKey: k) != nil {
                keys.removeAll { UTF16Key($0) == k }
            }
        }
    }

    public func has(_ key: String) -> Bool { storage[UTF16Key(key)] != nil }

    /// The entries in order.
    public var entries: [(key: String, value: Value)] { keys.map { (key: $0, value: storage[UTF16Key($0)]!) } }

    /// The values in order.
    public var values: [Value] { keys.map { storage[UTF16Key($0)]! } }

    /// `map + (key to value)`: a new map with `key` set.
    public func setting(_ key: String, _ value: Value) -> OrderedMap {
        var copy = self
        copy[key] = value
        return copy
    }

    /// `map - key`.
    public func removing(_ key: String) -> OrderedMap {
        var copy = self
        copy[key] = nil
        return copy
    }

    /// The entries whose value passes `isIncluded`, in order.
    public func filter(_ isIncluded: (String, Value) -> Bool) -> OrderedMap {
        OrderedMap(entries.filter { isIncluded($0.key, $0.value) }.map { ($0.key, $0.value) })
    }

    public func mapValues<T>(_ transform: (Value) throws -> T) rethrows -> OrderedMap<T> {
        OrderedMap<T>(try entries.map { ($0.key, try transform($0.value)) })
    }
}

extension OrderedMap: Sequence {
    public func makeIterator() -> IndexingIterator<[(key: String, value: Value)]> { entries.makeIterator() }
}

extension OrderedMap: ExpressibleByDictionaryLiteral {
    public init(dictionaryLiteral elements: (String, Value)...) { self.init(elements) }
}

extension OrderedMap: Equatable where Value: Equatable {
    public static func == (a: OrderedMap, b: OrderedMap) -> Bool {
        a.count == b.count && a.storage.allSatisfy { b.storage[$0.key] == $0.value }
    }
}

extension OrderedMap: Sendable where Value: Sendable {}

extension OrderedMap: CustomStringConvertible {
    public var description: String {
        "[" + entries.map { "\($0.key): \($0.value)" }.joined(separator: ", ") + "]"
    }
}
