package com.dan323.httpclient.core

import com.dan323.httpclient.core.application.CookieJar
import com.dan323.httpclient.core.application.PublicSuffixList
import com.dan323.httpclient.core.domain.Header
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CookieJarTest {
    private var now = Instant.parse("2024-06-01T12:00:00Z")
    private val jar = CookieJar { now }

    private fun set(url: String, vararg raw: String) = jar.store(url, raw.map { Header("Set-Cookie", it) })

    @Test fun hostOnlyVersusDomain() {
        set("http://www.h.com/", "a=1", "b=2; Domain=h.com", "c=3; Domain=.H.com")
        assertEquals("a=1; b=2; c=3", jar.headerFor("http://www.h.com/"))
        assertEquals("b=2; c=3", jar.headerFor("http://api.h.com/"))
        assertEquals("b=2; c=3", jar.headerFor("http://h.com/"))
        assertNull(jar.headerFor("http://evilh.com/"))
    }

    @Test fun rejectsForeignAndOverbroadDomains() {
        set("http://a.com/", "x=1; Domain=com", "y=1; Domain=b.com")
        set("http://a.co.uk/", "z=1; Domain=co.uk")
        assertEquals(0, jar.size())
        set("http://localhost/", "l=1; Domain=localhost")
        assertEquals("l=1", jar.headerFor("http://localhost/"))
        assertNull(jar.headerFor("http://other.localhost/")) // became host-only
    }

    @Test fun publicSuffixListRules() {
        val suffixes = listOf("com", "co.uk", "com.au", "github.io", "blogspot.com", "s3.amazonaws.com", "foo.ck", "a.kobe.jp", "xyz", "localhost", "unknowntld")
        suffixes.forEach { assertEquals(true, CookieJar.isPublicSuffix(it), it) }
        // wildcard `*.ck` with exception `!www.ck`, and `*.kobe.jp` with `!city.kobe.jp`
        listOf("example.com", "example.co.uk", "me.github.io", "www.ck", "city.kobe.jp", "a.b.example.com").forEach {
            assertEquals(false, CookieJar.isPublicSuffix(it), it)
        }
    }

    @Test fun publicSuffixListResourceIsLoaded() {
        // a missing resource makes the lazy load throw instead of silently accepting every domain
        assertTrue(PublicSuffixList.ruleCount() > 1000, "rules loaded: ${PublicSuffixList.ruleCount()}")
    }

    @Test fun aMissingPublicSuffixResourceFailsLoudly() {
        val e = kotlin.test.assertFailsWith<IllegalStateException> { PublicSuffixList.loadForTest("/no/such/list.txt") }
        assertTrue("/no/such/list.txt is missing from the classpath" in e.message!!)
    }

    @Test fun publicSuffixEdgeInputs() {
        assertEquals(true, CookieJar.isPublicSuffix("com.")) // trailing dot is ignored
        assertEquals(false, CookieJar.isPublicSuffix("example.com."))
        assertEquals(true, CookieJar.isPublicSuffix("")) // a single empty label falls under the implicit `*` rule
        assertEquals(false, CookieJar.isPublicSuffix("a..com")) // empty middle label is not part of the suffix
        // input is documented as lowercase: rules are lowercase, so uppercase names do not match them
        assertEquals(false, CookieJar.isPublicSuffix("CO.UK"))
        assertEquals(false, CookieJar.isPublicSuffix("EXAMPLE.COM"))
    }

    @Test fun rejectsPublicSuffixDomainsBeyondTheOldShortList() {
        set("http://a.example.ck/", "w=1; Domain=example.ck") // wildcard rule: example.ck is a suffix
        set("http://me.github.io/", "g=1; Domain=github.io") // private-section suffix
        set("http://a.b.s3.amazonaws.com/", "s=1; Domain=s3.amazonaws.com")
        set("http://x.com.br/", "p=1; Domain=com.br")
        assertEquals(0, jar.size())
    }

    @Test fun acceptsRegistrableDomainUnderPublicSuffix() {
        set("http://www.example.co.uk/", "ok=1; Domain=example.co.uk")
        assertEquals("ok=1", jar.headerFor("http://api.example.co.uk/"))
        set("http://www.city.kobe.jp/", "k=1; Domain=city.kobe.jp") // exception rule: not a suffix
        assertEquals("k=1", jar.headerFor("http://x.city.kobe.jp/"))
    }

    @Test fun publicSuffixDomainEqualToHostIsHostOnlyAndNotSentAcross() {
        set("http://github.io/", "h=1; Domain=github.io")
        assertEquals("h=1", jar.headerFor("http://github.io/"))
        assertNull(jar.headerFor("http://victim.github.io/"))
    }

    @Test fun ipLiteralsMatchExactlyOnly() {
        set("http://1.2.3.4/", "a=1; Domain=3.4", "b=2; Domain=1.2.3.4", "c=3")
        assertEquals("b=2; c=3", jar.headerFor("http://1.2.3.4/"))
        assertNull(jar.headerFor("http://9.1.2.3.4/"))
        set("http://[::1]:8080/", "v6=1")
        assertEquals("v6=1", jar.headerFor("http://[::1]:9/"))
    }

    @Test fun hostWithUnderscoreWorks() {
        set("http://my_service:8080/x", "a=1")
        assertEquals("a=1", jar.headerFor("http://my_service:8080/y"))
    }

    @Test fun secureOnlyOverHttpsAndNotSetFromHttp() {
        set("https://h/", "s=1; Secure", "p=2")
        assertEquals("s=1; p=2", jar.headerFor("https://h/"))
        assertEquals("p=2", jar.headerFor("http://h/"))
        set("http://h/", "t=1; Secure") // ignored
        assertEquals("p=2", jar.headerFor("http://h/"))
        set("http://h/", "s=hijack", "s=; Max-Age=0") // insecure origin cannot overwrite or delete a Secure cookie
        assertEquals("s=1; p=2", jar.headerFor("https://h/"))
        set("https://h/", "s=2; Secure")
        assertEquals("s=2; p=2", jar.headerFor("https://h/"))
    }

    @Test fun maxAgeBeatsExpiresAndClockDrivesExpiry() {
        set("http://h/", "a=1; Max-Age=60; Expires=Thu, 01 Jan 1970 00:00:00 GMT", "b=2; Max-Age=3600; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
        assertEquals("a=1; b=2", jar.headerFor("http://h/"))
        now = now.plus(Duration.ofSeconds(61))
        assertEquals("b=2", jar.headerFor("http://h/"))
        set("http://h/", "c=3; Expires=Sat, 01 Jun 2024 12:05:00 GMT", "d=4; Max-Age=60; Expires=Sat, 01 Jun 2024 18:00:00 GMT")
        now = now.plus(Duration.ofMinutes(2))
        assertEquals("b=2; c=3", jar.headerFor("http://h/"))
        now = now.plus(Duration.ofMinutes(10))
        assertEquals("b=2", jar.headerFor("http://h/"))
    }

    @Test fun lenientExpiresFormatsDeleteCookies() {
        for (date in listOf("Thu, 01-Jan-1970 00:00:00 GMT", "Thursday, 01-Jan-70 00:00:00 GMT", "Thu Jan  1 00:00:00 1970", "Wed, 21 Oct 2015 07:28:00 GMT")) {
            set("http://h/", "sid=live")
            set("http://h/", "sid=; Expires=$date")
            assertNull(jar.headerFor("http://h/"), date)
        }
        set("http://h/", "k=1; Expires=Wed, 21-Oct-30 07:28:00 GMT") // 2-digit year 30 -> 2030
        assertEquals("k=1", jar.headerFor("http://h/"))
        set("http://h/", "g=1; Expires=garbage") // unparseable Expires is ignored: session cookie
        assertEquals("k=1; g=1", jar.headerFor("http://h/"))
    }

    @Test fun maxAgeExtremesAndSigns() {
        set("http://h/", "a=1; Max-Age=-9223372036854775808", "b=1; Max-Age=9223372036854775807", "c=1; Max-Age=99999999999999999999", "d=1; Max-Age=+5", "e=1; Max-Age=abc")
        assertEquals("b=1; c=1; d=1; e=1", jar.headerFor("http://h/")) // d and e: invalid Max-Age ignored, session cookies
        set("http://h/", "b=; Max-Age=-99999999999999999999")
        assertEquals("c=1; d=1; e=1", jar.headerFor("http://h/"))
    }

    @Test fun pathMatchingAndDefaultPath() {
        set("http://h/a/b/c", "dp=1")
        set("http://h/", "api=1; Path=/api")
        set("http://h/", "slash=1; Path=/dir/")
        assertEquals("dp=1", jar.headerFor("http://h/a/b/x"))
        assertNull(jar.headerFor("http://h/a/bx"))
        assertNull(jar.headerFor("http://h/a"))
        assertEquals("api=1", jar.headerFor("http://h/api"))
        assertEquals("api=1", jar.headerFor("http://h/api/"))
        assertNull(jar.headerFor("http://h/apix"))
        assertEquals("slash=1", jar.headerFor("http://h/dir/x"))
        assertNull(jar.headerFor("http://h/dir"))
        set("http://h/page", "root=1") // default path of /page is /
        assertEquals("root=1", jar.headerFor("http://h/zzz"))
    }

    @Test fun rawPathIsUsedForMatching() {
        set("http://h/", "a=1; Path=/a/b")
        assertNull(jar.headerFor("http://h/a%2Fb"))
        assertEquals("a=1", jar.headerFor("http://h/a/b"))
    }

    @Test fun duplicateNamesAreOrderedLongerPathFirstThenCreation() {
        set("http://h/", "n=root; Path=/", "n=deep; Path=/a/b", "n=mid; Path=/a", "m=1; Path=/a")
        assertEquals("n=deep; n=mid; m=1; n=root", jar.headerFor("http://h/a/b/c"))
        set("http://h/", "n=root2; Path=/") // replaces same key, keeps creation order
        assertEquals("n=deep; n=mid; m=1; n=root2", jar.headerFor("http://h/a/b/c"))
    }

    @Test fun portIsIgnoredAndMultipleHeadersAreStored() {
        set("http://h:8080/", "a=1", "b=2")
        assertEquals("a=1; b=2", jar.headerFor("http://h:9090/"))
    }

    @Test fun malformedAreIgnored() {
        set("http://h/", "novalue", "=x", " ; a=b", "ok=1")
        assertEquals("ok=1", jar.headerFor("http://h/"))
        jar.store("not a url", listOf(Header("Set-Cookie", "a=1")))
        assertNull(jar.headerFor("::::"))
    }

    @Test fun limitsAreEnforced() {
        set("http://h/", "big=" + "x".repeat(5000))
        assertEquals(0, jar.size())
        for (i in 1..60) set("http://h/", "c$i=1")
        assertEquals(CookieJar.MAX_PER_DOMAIN, jar.size())
        assertNull(jar.headerFor("http://h/")!!.takeIf { "c1=" in it.split("; ") || "c10=" in it.split("; ") })
        for (i in 1..3100) set("http://h$i/", "a=1")
        assertEquals(CookieJar.MAX_TOTAL, jar.size())
    }

    @Test fun expiredCookiesArePurgedOnStore() {
        set("http://h/", "a=1; Max-Age=10")
        now = now.plusSeconds(11)
        set("http://other/", "b=1")
        assertEquals(1, jar.size())
    }
}
