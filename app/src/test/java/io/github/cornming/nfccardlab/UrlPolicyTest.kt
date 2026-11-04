package io.github.cornming.nfccardlab

import io.github.cornming.nfccardlab.UrlPolicy.Check
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlPolicyTest {

    private fun ok(url: String) = assertEquals("應該允許：$url", Check.Ok, UrlPolicy.check(url))
    private fun rejected(url: String) =
        assertTrue("應該拒絕：$url", UrlPolicy.check(url) is Check.Rejected)

    @Test
    fun httpsToAnyHostIsAllowed() {
        ok("https://example.com/hook")
        ok("https://hooks.example.com/a/b?token=abc")
        ok("https://203.0.113.5:8443/x")
    }

    @Test
    fun httpToPublicHostIsRejected() {
        rejected("http://example.com/hook")
        rejected("http://203.0.113.5/x")
        rejected("http://8.8.8.8/x")
    }

    @Test
    fun httpToPrivateNetworksIsAllowed() {
        ok("http://192.168.1.10:8123/api/webhook/x")
        ok("http://10.0.0.5/x")
        ok("http://172.16.0.1/x")
        ok("http://172.31.255.254/x")
        ok("http://127.0.0.1:8080/x")
        ok("http://169.254.1.1/x")
        ok("http://localhost:3000/x")
        ok("http://homeassistant.local:8123/x")
    }

    @Test
    fun httpJustOutsidePrivateRangesIsRejected() {
        rejected("http://172.15.0.1/x")
        rejected("http://172.32.0.1/x")
        rejected("http://192.169.1.1/x")
        rejected("http://11.0.0.1/x")
    }

    @Test
    fun lookalikeHostsAreRejected() {
        rejected("http://192.168.1.1.evil.com/x")
        rejected("http://notlocal/x")
        rejected("http://999.168.1.1/x")
    }

    @Test
    fun credentialsInUrlAreRejected() {
        rejected("https://user:pass@example.com/x")
    }

    @Test
    fun otherSchemesAndGarbageAreRejected() {
        rejected("ftp://example.com/x")
        rejected("file:///etc/passwd")
        rejected("javascript:alert(1)")
        rejected("example.com/x")
        rejected("")
        rejected("https://")
        rejected("not a url")
    }

    @Test
    fun isLocalHostIsCaseInsensitive() {
        assertTrue(UrlPolicy.isLocalHost("LOCALHOST"))
        assertTrue(UrlPolicy.isLocalHost("Printer.LOCAL"))
        assertFalse(UrlPolicy.isLocalHost("example.com"))
    }
}
