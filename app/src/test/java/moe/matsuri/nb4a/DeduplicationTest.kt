package moe.matsuri.nb4a

import io.nekohasekai.sagernet.fmt.ArchivedBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeduplicationTest {

    @Test
    fun archivedPayloads_areNotConflated() {
        val first = ArchivedBean(25, byteArrayOf(1, 2, 3))
        val second = ArchivedBean(25, byteArrayOf(1, 2, 4))
        val values = LinkedHashSet<Protocols.Deduplication>()

        assertTrue(values.add(wrap(first)))
        assertTrue(values.add(wrap(second)))
    }

    @Test
    fun socksDifferentPasswords_areNotDuplicates() {
        val first = socks("alpha")
        val second = socks("beta")
        val values = LinkedHashSet<Protocols.Deduplication>()

        assertTrue(values.add(wrap(first)))
        assertTrue(values.add(wrap(second)))
    }

    @Test
    fun sameValuesDifferentNames_areDuplicates() {
        val first = shadowsocks("first")
        val second = shadowsocks("second")
        val values = LinkedHashSet<Protocols.Deduplication>()

        assertTrue(values.add(wrap(first)))
        assertFalse(values.add(wrap(second)))
    }

    @Test
    fun configBeanIdentity_remainsConfigStringOnly() {
        val first = ConfigBean().apply {
            name = "first"
            config = "{\"type\":\"direct\"}"
            initializeDefaultValues()
        }
        val same = ConfigBean().apply {
            name = "second"
            config = "{\"type\":\"direct\"}"
            initializeDefaultValues()
        }
        val different = ConfigBean().apply {
            name = "third"
            config = "{\"type\":\"block\"}"
            initializeDefaultValues()
        }
        val values = LinkedHashSet<Protocols.Deduplication>()

        assertTrue(values.add(wrap(first)))
        assertFalse(values.add(wrap(same)))
        assertTrue(values.add(wrap(different)))
    }

    @Test
    fun differentTypesAtSameEndpoint_areNotDuplicates() {
        val socks = SOCKSBean().apply {
            serverAddress = "192.0.2.20"
            serverPort = 8080
            initializeDefaultValues()
        }
        val http = HttpBean().apply {
            serverAddress = "192.0.2.20"
            serverPort = 8080
            initializeDefaultValues()
        }
        val values = LinkedHashSet<Protocols.Deduplication>()

        assertTrue(values.add(wrap(socks)))
        assertTrue(values.add(wrap(http)))
    }

    private fun socks(password: String) = SOCKSBean().apply {
        serverAddress = "192.0.2.10"
        serverPort = 1080
        username = "reader"
        this.password = password
        initializeDefaultValues()
    }

    private fun shadowsocks(displayName: String) = ShadowsocksBean().apply {
        serverAddress = "example.com"
        serverPort = 443
        method = "aes-256-gcm"
        password = "alpha"
        name = displayName
        initializeDefaultValues()
    }

    private fun wrap(bean: io.nekohasekai.sagernet.fmt.AbstractBean) = Protocols.Deduplication(bean)
}
