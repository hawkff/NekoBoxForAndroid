package moe.matsuri.nb4a

import android.content.Context
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.getColorAttr
import moe.matsuri.nb4a.proxy.config.ConfigBean

// Settings for all protocols, built-in or plugin
object Protocols {

    // Deduplication

    class Deduplication(
        val bean: AbstractBean,
        val type: String,
    ) {

        override fun hashCode(): Int {
            if (bean is ConfigBean) return bean.config.hashCode()
            return bean.hashCode()
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Deduplication) return false
            if (bean is ConfigBean && other.bean is ConfigBean) {
                return bean.config == other.bean.config
            }
            if (bean.javaClass != other.bean.javaClass) return false
            // AbstractBean equality serializes without the display name. Both dedup callers run
            // serially; they must not compare the same mutable beans concurrently.
            return bean == other.bean
        }
    }

    // Display

    fun Context.getProtocolColor(type: Int): Int {
        return getColorAttr(R.attr.protocolColor)
    }

    // Test

    fun genFriendlyMsg(msg: String): String {
        val msgL = msg.lowercase()
        return when {
            msgL.contains("timeout") || msgL.contains("deadline") -> {
                app.getString(R.string.connection_test_timeout_error)
            }

            msgL.contains("refused") || msgL.contains("closed pipe") -> {
                app.getString(R.string.connection_test_refused)
            }

            else -> msg
        }
    }
}
