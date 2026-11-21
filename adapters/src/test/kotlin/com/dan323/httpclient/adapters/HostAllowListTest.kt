package com.dan323.httpclient.adapters

import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HostAllowListTest {
    private fun address(s: String) = InetAddress.getByName(s)
    private fun list(vararg entries: String, dns: Map<String, List<String>> = emptyMap()) =
        HostAllowList(entries.toList()) { name -> dns[name]?.map(::address) ?: throw UnknownHostException(name) }

    private fun HostAllowList.refuses(url: String, vararg inMessage: String) {
        val e = assertFailsWith<RequestRefusedException>(url) { check(url) }
        inMessage.forEach { assertTrue(it in e.message!!, "'$it' in: ${e.message}") }
    }

    @Test fun defaultAllowsLoopbackOnly() {
        val l = HostAllowList { if (it == "localhost") listOf(address("127.0.0.1"), address("::1")) else throw UnknownHostException(it) }
        l.check("http://localhost:8080/x")
        l.check("http://127.0.0.1/x")
        l.check("https://127.1.2.3/x")
        l.check("http://[::1]:9/x")
        l.refuses("http://example.com/")
        l.refuses("http://8.8.8.8/")
        l.refuses("http://169.254.169.254/latest/meta-data/")
    }

    @Test fun nameMatchingIsExactOrWildcardBelowTheSuffix() {
        val l = list("api.example.com", "*.internal.test", dns = mapOf(
            "api.example.com" to listOf("93.184.216.34"), "a.b.internal.test" to listOf("93.184.216.35"),
            "internal.test" to listOf("93.184.216.36"), "evil-api.example.com" to listOf("93.184.216.37"),
        ))
        l.check("https://api.example.com/x")
        l.check("https://API.Example.com./x")
        l.check("https://a.b.internal.test/")
        l.refuses("https://internal.test/")
        l.refuses("https://evil-api.example.com/")
        l.refuses("https://api.example.com.evil.net/")
    }

    @Test fun userinfoDoesNotChangeTheHostChecked() {
        val l = list("good.test", dns = mapOf("good.test" to listOf("93.184.216.34"), "evil.test" to listOf("93.184.216.35")))
        l.check("http://good.test/")
        l.refuses("http://good.test@evil.test/")
        l.refuses("http://good.test:80@evil.test/")
        l.check("http://evil.test@good.test/")
    }

    @Test fun aListedNameThatResolvesToARestrictedAddressIsRefused() {
        val l = list("*.nip.test", dns = mapOf(
            "metadata.nip.test" to listOf("169.254.169.254"), "lan.nip.test" to listOf("10.0.0.5"), "ula.nip.test" to listOf("fd12::1"),
            "shared.nip.test" to listOf("100.64.0.1"), "mixed.nip.test" to listOf("93.184.216.34", "192.168.1.1"),
            "ok.nip.test" to listOf("93.184.216.34"),
        ))
        l.check("http://ok.nip.test/")
        l.refuses("http://metadata.nip.test/", "169.254.169.254")
        l.refuses("http://lan.nip.test/", "10.0.0.5")
        l.refuses("http://ula.nip.test/")
        l.refuses("http://shared.nip.test/")
        l.refuses("http://mixed.nip.test/", "192.168.1.1")
    }

    @Test fun listedAddressesAndRangesOpenRestrictedOnes() {
        val l = list("*.nip.test", "10.0.0.0/8", "169.254.169.254", dns = mapOf("lan.nip.test" to listOf("10.1.2.3"), "other.nip.test" to listOf("192.168.1.1")))
        l.check("http://lan.nip.test/")
        l.check("http://10.9.9.9/")
        l.check("http://169.254.169.254/")
        l.refuses("http://169.254.169.253/")
        l.refuses("http://other.nip.test/")
    }

    @Test fun ipv6Ranges() {
        val l = list("2001:db8::/32", "::1")
        l.check("http://[2001:db8::5]/")
        l.check("http://[::1]/")
        l.refuses("http://[2001:db9::5]/")
        l.refuses("http://[fe80::1]/")
    }

    @Test fun ipv4MappedIpv6IsJudgedAsTheIpv4Address() {
        val l = list("127.0.0.0/8")
        l.check("http://[::ffff:127.0.0.1]/")
        l.refuses("http://[::ffff:169.254.169.254]/")
    }

    @Test fun numericHostFormsAreRefused() {
        val l = list("127.0.0.0/8", "localhost", dns = mapOf("localhost" to listOf("127.0.0.1")))
        l.refuses("http://2130706433/")
        l.refuses("http://0x7f.1/")
        l.refuses("http://0177.0.0.1/")
        l.refuses("http://127.1/")
    }

    @Test fun otherSchemesAndBrokenUrlsAreRefused() {
        val l = list("localhost", "127.0.0.0/8", dns = mapOf("localhost" to listOf("127.0.0.1")))
        l.refuses("file:///etc/passwd")
        l.refuses("ftp://localhost/")
        l.refuses("http://localhost\\@evil.test/")
        l.refuses("not a url")
        l.refuses("/relative/path")
        l.refuses("http://[fe80::1%25eth0]/")
    }

    @Test fun unresolvableNamesAreRefused() {
        list("x.test").refuses("http://x.test/", "cannot resolve")
        HostAllowList(listOf("x.test")) { emptyList() }.refuses("http://x.test/", "cannot resolve")
    }

    @Test fun badEntriesAreRejected() {
        listOf("", "  ", "*", "*.", "a@b", "host:80", "10.0.0.0/33", "10.0.0.0/x", "::1/129").forEach { e ->
            assertFailsWith<IllegalArgumentException>(e) { HostAllowList(listOf(e)) }
        }
    }

    @Test fun messageNamesTheFlag() = list("a.test").refuses("http://b.test/", "--allow-host", "b.test")
}
