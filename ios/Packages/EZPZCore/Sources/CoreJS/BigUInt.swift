// The little arbitrary-precision arithmetic JavaScript's number rules need and Swift does not have: `toFixed` rounds the exact
// binary value (a few hundred bits at 100 places), and `0x…` text or a long run of digits is read whole, then rounded once to the
// nearest double. Kotlin used java.math.BigInteger and BigDecimal for both.

/// A non-negative whole number of any size, little-endian in 32-bit limbs, never with a zero limb on top.
struct BigUInt: Equatable, Sendable {
    private(set) var limbs: [UInt32]

    init(_ value: UInt64 = 0) {
        limbs = value == 0 ? [] : [UInt32(truncatingIfNeeded: value), UInt32(value >> 32)]
        normalize()
    }

    var isZero: Bool { limbs.isEmpty }

    /// How many bits the number takes, 0 for zero.
    var bitWidth: Int {
        guard let top = limbs.last else { return 0 }
        return limbs.count * 32 - top.leadingZeroBitCount
    }

    private mutating func normalize() {
        while limbs.last == 0 { limbs.removeLast() }
    }

    /// `self * factor + addend`, in place.
    mutating func multiply(by factor: UInt32, adding addend: UInt32 = 0) {
        var carry = UInt64(addend)
        for i in limbs.indices {
            let product = UInt64(limbs[i]) * UInt64(factor) + carry
            limbs[i] = UInt32(truncatingIfNeeded: product)
            carry = product >> 32
        }
        if carry != 0 { limbs.append(UInt32(carry)) }
        normalize()
    }

    /// `self * 10^power`, in place.
    mutating func multiply(byPowerOfTen power: Int) {
        var left = power
        while left >= 9 {
            multiply(by: 1_000_000_000)
            left -= 9
        }
        var rest: UInt32 = 1
        for _ in 0..<left { rest *= 10 }
        if rest != 1 { multiply(by: rest) }
    }

    mutating func add(_ other: BigUInt) {
        var carry: UInt64 = 0
        let count = max(limbs.count, other.limbs.count)
        if limbs.count < count { limbs.append(contentsOf: repeatElement(0, count: count - limbs.count)) }
        for i in 0..<count {
            let sum = UInt64(limbs[i]) + UInt64(i < other.limbs.count ? other.limbs[i] : 0) + carry
            limbs[i] = UInt32(truncatingIfNeeded: sum)
            carry = sum >> 32
        }
        if carry != 0 { limbs.append(UInt32(carry)) }
        normalize()
    }

    func shiftedLeft(by bits: Int) -> BigUInt {
        guard !isZero, bits > 0 else { return self }
        var result = BigUInt()
        let whole = bits / 32
        let part = bits % 32
        result.limbs = Array(repeating: 0, count: whole)
        var carry: UInt32 = 0
        for limb in limbs {
            if part == 0 {
                result.limbs.append(limb)
            } else {
                result.limbs.append((limb << UInt32(part)) | carry)
                carry = limb >> UInt32(32 - part)
            }
        }
        if carry != 0 { result.limbs.append(carry) }
        result.normalize()
        return result
    }

    func shiftedRight(by bits: Int) -> BigUInt {
        guard bits > 0 else { return self }
        let whole = bits / 32
        guard whole < limbs.count else { return BigUInt() }
        let part = bits % 32
        var result = BigUInt()
        result.limbs = []
        for i in whole..<limbs.count {
            var limb = limbs[i] >> UInt32(part)
            if part != 0, i + 1 < limbs.count { limb |= limbs[i + 1] << UInt32(32 - part) }
            result.limbs.append(limb)
        }
        result.normalize()
        return result
    }

    /// Whether any of the lowest `bits` bits is set.
    func hasBits(below bits: Int) -> Bool {
        let whole = min(bits / 32, limbs.count)
        if limbs[0..<whole].contains(where: { $0 != 0 }) { return true }
        let part = bits % 32
        guard part != 0, whole < limbs.count else { return false }
        return limbs[whole] & ((1 << UInt32(part)) - 1) != 0
    }

    /// The decimal digits, `"0"` for zero.
    var decimalText: String {
        if isZero { return "0" }
        var chunks: [UInt32] = []
        var rest = limbs
        while !rest.isEmpty {
            var remainder: UInt64 = 0
            for i in rest.indices.reversed() {
                let current = (remainder << 32) | UInt64(rest[i])
                rest[i] = UInt32(current / 1_000_000_000)
                remainder = current % 1_000_000_000
            }
            while rest.last == 0 { rest.removeLast() }
            chunks.append(UInt32(remainder))
        }
        var text = String(chunks.removeLast())
        for chunk in chunks.reversed() {
            let digits = String(chunk)
            text += String(repeating: "0", count: 9 - digits.count) + digits
        }
        return text
    }

    /// The nearest double, a half to the even one, as BigInteger.toDouble and JavaScript's reading of digits round. Infinity past
    /// the largest double.
    var nearestDouble: Double {
        let width = bitWidth
        if width <= 64 {
            let low = UInt64(limbs.first ?? 0) | (limbs.count > 1 ? UInt64(limbs[1]) << 32 : 0)
            return Double(low)  // Swift rounds a UInt64 to the nearest double, a half to even.
        }
        // The top 64 bits, with any lower bit set folded into the lowest of them: 64 bits are more than a double's 53 and its round
        // and sticky bits, so rounding these rounds the whole number the same way.
        let shift = width - 64
        let top = shiftedRight(by: shift)
        var top64 = UInt64(top.limbs[0]) | UInt64(top.limbs[1]) << 32
        if hasBits(below: shift) { top64 |= 1 }
        return Double(sign: .plus, exponent: shift, significand: Double(top64))
    }

    /// Digits in `radix` (2 to 36), or nil when one is not a digit there.
    init?(digits: some Sequence<Unicode.Scalar>, radix: UInt32) {
        self.init()
        var any = false
        for scalar in digits {
            guard let digit = BigUInt.digit(scalar), digit < radix else { return nil }
            multiply(by: radix, adding: digit)
            any = true
        }
        if !any { return nil }
    }

    static func digit(_ scalar: Unicode.Scalar) -> UInt32? {
        switch scalar.value {
        case 0x30...0x39: return scalar.value - 0x30
        case 0x41...0x5A: return scalar.value - 0x41 + 10
        case 0x61...0x7A: return scalar.value - 0x61 + 10
        default: return nil
        }
    }
}
