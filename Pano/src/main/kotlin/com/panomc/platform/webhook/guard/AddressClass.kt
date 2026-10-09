package com.panomc.platform.webhook.guard

import java.net.InetAddress

/**
 * The class of an IP address for the SSRF guard (11 section 7.2 step 9). Pure, no resolution.
 *
 * [LOOPBACK], [PRIVATE] and [RESERVED] are reachable only when the site owner switched `webhooks.allow-private-targets`
 * on; every other non-public class is refused with the flag as well. [METADATA] is the addition of this
 * implementation: the well known cloud metadata endpoints that do not sit in a link-local range
 * (`100.100.100.200` Alibaba, `192.0.0.192` Oracle, `168.63.129.16` Azure) are refused like the link-local ones.
 *
 * IPv4-mapped (`::ffff:0:0/96`), IPv4-compatible (`::/96`), NAT64 (`64:ff9b::/96`) and 6to4 (`2002::/16`) addresses are
 * classified by the IPv4 address they embed.
 */
enum class AddressClass(private val allowedWithPrivateFlag: Boolean, private val alwaysAllowed: Boolean = false) {
    LINK_LOCAL(false),
    METADATA(false),
    UNSPECIFIED(false),

    /** `255.255.255.255` and the rest of `240.0.0.0/4`. */
    BROADCAST(false),
    MULTICAST(false),
    LOOPBACK(true),
    PRIVATE(true),
    RESERVED(true),
    PUBLIC(true, alwaysAllowed = true);

    /** `true` when an address of this class may be connected to; [allowPrivate] is the effective setting. */
    fun isAllowed(allowPrivate: Boolean): Boolean = alwaysAllowed || (allowPrivate && allowedWithPrivateFlag)

    companion object {
        fun of(address: InetAddress): AddressClass = of(address.address)

        /** [bytes] is 4 (IPv4) or 16 (IPv6) network-order bytes. */
        fun of(bytes: ByteArray): AddressClass {
            require(bytes.size == 4 || bytes.size == 16) { "an IP address has 4 or 16 bytes" }
            if (bytes.size == 4) return ofV4(bytes[0].toInt() and 0xff, bytes[1].toInt() and 0xff, bytes[2].toInt() and 0xff, bytes[3].toInt() and 0xff)

            val u = IntArray(16) { bytes[it].toInt() and 0xff }

            fun zero(from: Int, to: Int) = (from until to).all { u[it] == 0 }

            // IPv4-mapped ::ffff:a.b.c.d
            if (zero(0, 10) && u[10] == 0xff && u[11] == 0xff) return ofV4(u[12], u[13], u[14], u[15])
            // ::, ::1 and the deprecated IPv4-compatible ::a.b.c.d
            if (zero(0, 12)) {
                if (zero(12, 16)) return UNSPECIFIED
                if (zero(12, 15) && u[15] == 1) return LOOPBACK
                return ofV4(u[12], u[13], u[14], u[15])
            }
            // NAT64 64:ff9b::/96
            if (u[0] == 0x00 && u[1] == 0x64 && u[2] == 0xff && u[3] == 0x9b && zero(4, 12)) return ofV4(u[12], u[13], u[14], u[15])
            // 6to4 2002::/16 (the IPv4 address is bits 16..47)
            if (u[0] == 0x20 && u[1] == 0x02) return ofV4(u[2], u[3], u[4], u[5])
            // ff00::/8
            if (u[0] == 0xff) return MULTICAST
            // fe80::/10 link-local, fec0::/10 (deprecated site-local)
            if (u[0] == 0xfe && (u[1] and 0xc0) == 0x80) return LINK_LOCAL
            if (u[0] == 0xfe && (u[1] and 0xc0) == 0xc0) return PRIVATE
            // fd00:ec2::/32 (AWS IPv6 metadata) before the unique-local range that contains it
            if (u[0] == 0xfd && u[1] == 0x00 && u[2] == 0x0e && u[3] == 0xc2) return LINK_LOCAL
            // fc00::/7
            if ((u[0] and 0xfe) == 0xfc) return PRIVATE
            // 2001:db8::/32
            if (u[0] == 0x20 && u[1] == 0x01 && u[2] == 0x0d && u[3] == 0xb8) return RESERVED
            return PUBLIC
        }

        private fun ofV4(a: Int, b: Int, c: Int, d: Int): AddressClass = when {
            a == 0 -> UNSPECIFIED
            a == 10 -> PRIVATE
            a == 100 && b == 100 && c == 100 && d == 200 -> METADATA
            a == 100 && (b and 0xc0) == 64 -> PRIVATE
            a == 127 -> LOOPBACK
            a == 169 && b == 254 -> LINK_LOCAL
            a == 168 && b == 63 && c == 129 && d == 16 -> METADATA
            a == 172 && (b and 0xf0) == 16 -> PRIVATE
            a == 192 && b == 0 && c == 0 && d == 192 -> METADATA
            a == 192 && b == 0 && c == 0 -> RESERVED
            a == 192 && b == 0 && c == 2 -> RESERVED
            a == 192 && b == 168 -> PRIVATE
            a == 198 && (b and 0xfe) == 18 -> RESERVED
            a == 198 && b == 51 && c == 100 -> RESERVED
            a == 203 && b == 0 && c == 113 -> RESERVED
            a in 224..239 -> MULTICAST
            a >= 240 -> BROADCAST
            else -> PUBLIC
        }
    }
}
