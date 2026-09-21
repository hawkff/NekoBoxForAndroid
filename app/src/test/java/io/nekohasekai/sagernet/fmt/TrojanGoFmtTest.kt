package io.nekohasekai.sagernet.fmt

import android.app.Application
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.trojan_go.toUri
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class TrojanGoFmtTest {
    @Test
    fun unsetEncryptionIsNotExported() {
        for (value in listOf(null, "", "none")) {
            val bean = TrojanGoBean().apply {
                initializeDefaultValues()
                encryption = value
            }
            val uri = bean.toUri().replaceFirst("trojan-go://", "https://").toHttpUrl()
            assertNull(uri.queryParameter("encryption"))
        }
    }

    @Test
    fun encryptionDoesNotDependOnTransportType() {
        val bean = TrojanGoBean().apply {
            initializeDefaultValues()
            type = "none"
            encryption = "ss;aes-128-gcm:example-key"
        }
        val uri = bean.toUri().replaceFirst("trojan-go://", "https://").toHttpUrl()
        assertEquals(bean.encryption, uri.queryParameter("encryption"))
    }
}
