package io.github.tensaiapp.websearch

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlGuardTest {

    private fun addr(literal: String) = InetAddress.getByName(literal)

    /** Resolver that must not be reached for IP literals, and maps a few names. */
    private val guard = UrlGuard { host ->
        when (host) {
            "example.com" -> listOf(addr("93.184.216.34"), addr("2606:2800:220:1:248:1893:25c8:1946"))
            "rebind.example" -> listOf(addr("93.184.216.34"), addr("10.0.0.5"))
            "rebind6.example" -> listOf(addr("fd12:3456::1"))
            else -> throw java.net.UnknownHostException(host)
        }
    }

    private fun assertUnsafe(url: String) {
        assertFails<SearchError.UnsafeUrl> { guard.check(url) }
    }

    @Test fun publicHostsPass() {
        guard.check("https://example.com/page?q=1")
        guard.check("http://example.com")
        guard.check("http://93.184.216.34/")
    }

    @Test fun onlyHttpAndHttps() {
        assertUnsafe("file:///etc/passwd")
        assertUnsafe("ftp://example.com/x")
        assertUnsafe("javascript:alert(1)")
        assertUnsafe("example.com/no-scheme")
    }

    @Test fun loopbackIsRefused() {
        assertUnsafe("http://127.0.0.1/")
        assertUnsafe("http://127.1.2.3:8080/")
        assertUnsafe("http://[::1]/")
        assertUnsafe("http://localhost/")
        assertUnsafe("http://LOCALHOST./")
        assertUnsafe("http://app.localhost/")
        assertUnsafe("http://0.0.0.0/")
    }

    @Test fun privateIpv4IsRefused() {
        assertUnsafe("http://10.0.0.1/")
        assertUnsafe("http://172.16.5.4/")
        assertUnsafe("http://192.168.1.1/admin")
        assertUnsafe("http://100.64.0.1/")
    }

    @Test fun linkLocalIsRefused() {
        assertUnsafe("http://169.254.169.254/latest/meta-data")
        assertUnsafe("http://[fe80::1]/")
    }

    @Test fun uniqueLocalIpv6IsRefused() {
        assertUnsafe("http://[fc00::1]/")
        assertUnsafe("http://[fd12:3456::1]/")
    }

    @Test fun ipv4MappedIpv6IsRefused() {
        assertUnsafe("http://[::ffff:127.0.0.1]/")
        assertUnsafe("http://[::ffff:192.168.0.1]/")
    }

    @Test fun alternateIpv4NotationsAreRefused() {
        assertUnsafe("http://2130706433/")
        assertUnsafe("http://0x7f000001/")
    }

    @Test fun dotLocalAndInternalNamesAreRefused() {
        assertUnsafe("http://printer.local/")
        assertUnsafe("http://db.internal/")
    }

    @Test fun publicNameResolvingToPrivateIpIsRefused() {
        assertUnsafe("http://rebind.example/")
        assertUnsafe("http://rebind6.example/")
    }

    @Test fun credentialsInUrlAreRefused() {
        assertUnsafe("http://user:pass@example.com/")
    }

    @Test fun unresolvableHostIsNetworkError() {
        assertFails<SearchError.Network> { guard.check("http://nope.example/") }
    }

    @Test fun isPrivateCoversRanges() {
        assertTrue(UrlGuard.isPrivate(addr("172.31.255.255")))
        assertTrue(!UrlGuard.isPrivate(addr("172.32.0.1")))
        assertTrue(!UrlGuard.isPrivate(addr("100.128.0.1")))
        assertTrue(!UrlGuard.isPrivate(addr("2001:4860:4860::8888")))
    }

    @Test fun embeddedIpv4InIpv6IsDecoded() {
        val private = listOf(
            "::127.0.0.1", "::10.1.2.3", "::ffff:10.0.0.1", "::ffff:127.0.0.1",
            "64:ff9b::7f00:1", "64:ff9b::a9fe:a9fe", "64:ff9b:1::1", "64:ff9b:1:abcd::1",
            "2002:7f00:1::1", "2002:c0a8:101::1", "2002:a9fe:a9fe::",
            "2001:0:4136:e378:8000:63bf:80ff:fefe", // Teredo, client 127.0.0.1
            "2001:0:7f00:1:8000:63bf:3fff:fdd2", // Teredo, server 127.0.0.1
            "::", "::1", "ff02::1", "fec0::1",
        )
        for (literal in private) assertTrue(literal, UrlGuard.isPrivate(addr(literal)))
        // Inet6Address kept as such even when it is an IPv4-mapped address
        val mapped = java.net.Inet6Address.getByAddress(null, ByteArray(16).also { it[10] = -1; it[11] = -1; it[12] = 10; it[15] = 1 }, -1)
        assertTrue(UrlGuard.isPrivate(mapped))
        val publicV6 = listOf("64:ff9b::808:808", "2002:5db8:d822::1", "2001:4860:4860::8888", "2606:4700::1111")
        for (literal in publicV6) assertTrue(literal, !UrlGuard.isPrivate(addr(literal)))
    }

    @Test fun reservedIpv4RangesAreRefused() {
        for (literal in listOf("0.1.2.3", "192.0.0.8", "198.18.0.1", "198.19.255.255", "240.0.0.1", "255.255.255.255", "224.0.0.1", "239.1.1.1")) {
            assertTrue(literal, UrlGuard.isPrivate(addr(literal)))
        }
        for (literal in listOf("198.17.0.1", "198.20.0.1", "192.0.1.1", "223.255.255.254", "8.8.8.8")) {
            assertTrue(literal, !UrlGuard.isPrivate(addr(literal)))
        }
    }

    @Test fun portPolicy() {
        guard.check("https://example.com:443/")
        guard.check("http://example.com:80/")
        guard.check("http://example.com:8080/")
        guard.check("http://example.com:65535/")
        assertUnsafe("http://example.com:22/")
        assertUnsafe("http://example.com:25/")
        assertUnsafe("http://example.com:1023/")
        assertUnsafe("http://example.com:81/")
    }

    @Test fun internationalizedHostsAreNormalizedAndChecked() {
        val seen = mutableListOf<String>()
        val idnGuard = UrlGuard { host -> seen += host; listOf(addr("93.184.216.34")) }
        assertEquals("https://xn--bcher-kva.example/x", idnGuard.check("https://bücher.example/x"))
        assertEquals(listOf("xn--bcher-kva.example"), seen)
        // fullwidth dot cannot smuggle a local suffix
        assertUnsafe("http://printer．local/")
    }
}
