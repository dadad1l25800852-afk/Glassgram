package dev.youfree.app.network

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class HappLinkParserTest {
    @Test fun standardHttpsSubscription() {
        assertTrue(HappLinkParser.classify("https://vpn.example.test/subscription?token=abc") is HappLinkParser.Input.Subscription)
    }
    @Test fun rejectPlaintextHttp() {
        assertThrows(IllegalStateException::class.java) { HappLinkParser.classify("http://vpn.example.test/sub") }
    }
    @Test fun rejectEncryptedHapp() {
        assertThrows(IllegalArgumentException::class.java) { HappLinkParser.classify("happ://crypt5/test") }
    }
    @Test fun decodeBase64AndFilterComments() {
        val line = "vless://550e8400-e29b-41d4-a716-446655440000@vpn.example.test:443#MyServer"
        val encoded = Base64.getEncoder().encodeToString(("# demo\n" + line + "\n").toByteArray())
        assertEquals(line, HappLinkParser.cleanNodes(encoded))
    }
    @Test fun acceptHappImportUrl() {
        val link = "happ://add/https%3A%2F%2Fvpn.example.test%2Fsub"
        assertTrue(HappLinkParser.classify(link) is HappLinkParser.Input.Subscription)
    }
    @Test fun rejectUnsupportedLinks() {
        assertThrows(IllegalArgumentException::class.java) { HappLinkParser.cleanNodes("ftp://nope") }
    }
}
