package io.nekohasekai.sagernet.fmt.internal

import io.nekohasekai.sagernet.fmt.AbstractBean

abstract class InternalBean : AbstractBean() {
    override fun displayAddress() = ""
    override fun canICMPing() = false
    override fun canTCPing() = false
    override fun canMapping() = false
}
