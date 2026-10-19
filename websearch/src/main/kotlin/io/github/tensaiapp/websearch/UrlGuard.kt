package io.github.tensaiapp.websearch

import java.net.IDN
import java.net.InetAddress
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Host name to addresses, injectable so tests can simulate DNS rebinding. */
fun interface HostResolver {
    fun resolve(host: String): List<InetAddress>

    companion object {
        val System = HostResolver { host -> InetAddress.getAllByName(host).toList() }
    }
}

/**
 * Decides whether a URL chosen by the model may be fetched: http(s) only, ports 80, 443 and
 * 1024-65535 only, and never a target on the local machine or a private network. The host is
 * resolved and every address checked, so a public name pointing at a private IP is refused too.
 *
 * This is a check at one point in time. It only holds together with the [HttpFetcher] contract:
 * no automatic redirects (the module re-checks each hop) and DNS pinned through
 * [isPrivate] at connect time.
 */
class UrlGuard(private val resolver: HostResolver = HostResolver.System) {

    /** [check] off the caller's thread: resolving a name blocks. */
    suspend fun verify(url: String): String = withContext(Dispatchers.IO) { check(url) }

    /**
     * Throws [SearchError.UnsafeUrl] when [url] must not be fetched, otherwise returns it with an
     * ASCII (punycode) host, which is the form to request.
     */
    fun check(url: String): String {
        val normalized = normalize(url.trim())
        val uri = try {
            URI(normalized)
        } catch (e: Exception) {
            throw SearchError.UnsafeUrl("malformed URL")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") throw SearchError.UnsafeUrl("only http and https are allowed")
        if (uri.rawUserInfo != null) throw SearchError.UnsafeUrl("credentials in URL")
        val port = uri.port
        if (port != -1 && port != 80 && port != 443 && port !in 1024..65535) {
            throw SearchError.UnsafeUrl("port $port is not allowed")
        }
        val host = uri.host?.removeSurrounding("[", "]")?.trimEnd('.')?.lowercase()
        if (host.isNullOrEmpty()) throw SearchError.UnsafeUrl("missing host")
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
            host.endsWith(".internal")
        ) {
            throw SearchError.UnsafeUrl("local host name")
        }
        // Hex and octal IPv4 forms are read differently by different stacks: refuse, don't guess.
        if (host.matches(Regex("0[xX][0-9a-fA-F.]+")) || host.matches(Regex("([0-9]+\\.)*0[0-9]+(\\.[0-9]+)*"))) {
            throw SearchError.UnsafeUrl("non-standard IPv4 notation")
        }
        val addresses = try {
            if (isIpLiteral(host)) listOf(InetAddress.getByName(host)) else resolver.resolve(host)
        } catch (e: Exception) {
            throw SearchError.Network("cannot resolve $host", e)
        }
        if (addresses.isEmpty()) throw SearchError.Network("cannot resolve $host")
        addresses.firstOrNull { isPrivate(it) }?.let {
            throw SearchError.UnsafeUrl("$host resolves to a private or local address (${it.hostAddress})")
        }
        return normalized
    }

    /** Converts an internationalized host to punycode before [URI] sees it. */
    private fun normalize(url: String): String {
        val match = AUTHORITY.matchEntire(url) ?: return url
        val (prefix, authority, rest) = match.destructured
        val at = authority.lastIndexOf('@')
        val userInfo = if (at >= 0) authority.substring(0, at + 1) else ""
        val hostPort = authority.substring(at + 1)
        if (hostPort.startsWith("[")) return url
        val colon = hostPort.lastIndexOf(':')
        val host = if (colon >= 0) hostPort.substring(0, colon) else hostPort
        val port = if (colon >= 0) hostPort.substring(colon) else ""
        if (host.all { it.code < 128 }) return url
        val ascii = try {
            IDN.toASCII(host, IDN.ALLOW_UNASSIGNED)
        } catch (e: IllegalArgumentException) {
            throw SearchError.UnsafeUrl("invalid internationalized host")
        }
        return "$prefix$userInfo$ascii$port$rest"
    }

    /** True for dotted/decimal IPv4 forms and anything with a colon, parsed without DNS. */
    private fun isIpLiteral(host: String): Boolean =
        host.contains(':') || host.matches(Regex("[0-9.]+"))

    companion object {
        private val AUTHORITY = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*://)([^/?#]*)(.*)$", RegexOption.DOT_MATCHES_ALL)

        /**
         * True for loopback, unspecified, link-local, private, carrier-grade NAT, benchmarking,
         * reserved, multicast and unique-local addresses, including IPv4 hidden inside IPv6
         * (compatible, mapped, NAT64, 6to4, Teredo). Reusable for the fetcher's `Dns` hook.
         */
        fun isPrivate(address: InetAddress): Boolean {
            val b = address.address
            return if (b.size == 4) isPrivateV4(b) else isPrivateV6(b)
        }

        private fun isPrivateV4(b: ByteArray): Boolean {
            val a = b[0].toInt() and 0xFF
            val c = b[1].toInt() and 0xFF
            val d = b[2].toInt() and 0xFF
            return a == 0 || a == 10 || a == 127 ||
                (a == 100 && (c and 0xC0) == 64) ||
                (a == 169 && c == 254) ||
                (a == 172 && (c and 0xF0) == 16) ||
                (a == 192 && c == 168) ||
                (a == 192 && c == 0 && d == 0) ||
                (a == 198 && (c and 0xFE) == 18) ||
                a >= 224 // multicast, reserved 240/4, broadcast
        }

        private fun isPrivateV6(b: ByteArray): Boolean {
            fun v4At(offset: Int, flip: Boolean = false) =
                ByteArray(4) { i -> (b[offset + i].toInt() xor if (flip) 0xFF else 0).toByte() }

            fun zero(from: Int, to: Int) = (from until to).all { b[it].toInt() == 0 }
            val b0 = b[0].toInt() and 0xFF
            val b1 = b[1].toInt() and 0xFF

            // ::/96 (unspecified, loopback, IPv4-compatible): last 4 bytes decide, 0/8 catches ::0 and ::1.
            if (zero(0, 12)) return isPrivateV4(v4At(12))
            // ::ffff:0:0/96 IPv4-mapped.
            if (zero(0, 10) && (b[10].toInt() and 0xFF) == 0xFF && (b[11].toInt() and 0xFF) == 0xFF) {
                return isPrivateV4(v4At(12))
            }
            // 64:ff9b::/96 NAT64 well-known prefix.
            if (b0 == 0x00 && b1 == 0x64 && (b[2].toInt() and 0xFF) == 0xFF && (b[3].toInt() and 0xFF) == 0x9B &&
                zero(4, 12)
            ) {
                return isPrivateV4(v4At(12))
            }
            // 64:ff9b:1::/48 local-use NAT64, not globally routable.
            if (b0 == 0x00 && b1 == 0x64 && (b[2].toInt() and 0xFF) == 0xFF && (b[3].toInt() and 0xFF) == 0x9B &&
                b[4].toInt() == 0 && (b[5].toInt() and 0xFF) == 0x01
            ) {
                return true
            }
            // 2002::/16 6to4: IPv4 in bytes 2..5.
            if (b0 == 0x20 && b1 == 0x02 && isPrivateV4(v4At(2))) return true
            // 2001::/32 Teredo: server IPv4 in bytes 4..7, client IPv4 inverted in bytes 12..15.
            if (b0 == 0x20 && b1 == 0x01 && b[2].toInt() == 0 && b[3].toInt() == 0) {
                return isPrivateV4(v4At(4)) || isPrivateV4(v4At(12, flip = true))
            }
            return b0 == 0xFF ||                        // ff00::/8 multicast
                (b0 and 0xFE) == 0xFC ||                // fc00::/7 unique local
                (b0 == 0xFE && (b1 and 0xC0) != 0x00)   // fe80::/10 link-local, fec0::/10 site-local
        }
    }
}
