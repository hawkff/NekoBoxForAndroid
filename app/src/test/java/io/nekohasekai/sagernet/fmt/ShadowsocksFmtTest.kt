package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocks
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Characterization tests for the Shadowsocks (ss://) URI decoder.
 *
 * These pin CURRENT behavior of parseShadowsocks (see Plan 007). Golden values are
 * hand-traced from ShadowsocksFmt.kt; if one looks wrong, it is recorded as-is and flagged,
 * not "fixed" here.
 */
class ShadowsocksFmtTest {

    /**
     * SIP002-style with base64url userinfo: ss://base64(method:password)@host:port#name
     * userinfo "aes-256-gcm:secret123" -> base64url "YWVzLTI1Ni1nY206c2VjcmV0MTIz".
     * Since the link contains '@', the parser takes the ss-android branch; link.username is
     * the (non-blank) base64 userinfo and link.password is blank, so it decodes username via
     * base64 into method:password.
     */
    @Test
    fun parseShadowsocks_sip002Base64Userinfo() {
        val link = "ss://YWVzLTI1Ni1nY206c2VjcmV0MTIz@192.0.2.5:8388#node-a"
        val bean = parseShadowsocks(link)
        assertEquals("192.0.2.5", bean.serverAddress)
        assertEquals(8388, bean.serverPort)
        assertEquals("aes-256-gcm", bean.method)
        assertEquals("secret123", bean.password)
        assertEquals("node-a", bean.name)
        assertEquals("", bean.plugin)
    }

    /**
     * Plaintext userinfo form: ss://method:password@host:port#name (username + password
     * present, so the ss-android branch returns them directly).
     */
    @Test
    fun parseShadowsocks_plaintextUserinfo() {
        val link = "ss://aes-128-gcm:pw@example.com:443#my%20node"
        val bean = parseShadowsocks(link)
        assertEquals("example.com", bean.serverAddress)
        assertEquals(443, bean.serverPort)
        assertEquals("aes-128-gcm", bean.method)
        assertEquals("pw", bean.password)
        // link.fragment is URL-decoded by the HttpUrl parser.
        assertEquals("my node", bean.name)
    }
}
