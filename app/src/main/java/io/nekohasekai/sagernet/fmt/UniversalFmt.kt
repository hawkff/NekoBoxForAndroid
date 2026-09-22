package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProtocolRegistry
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import moe.matsuri.nb4a.utils.Util

fun parseUniversal(link: String): AbstractBean = if (link.contains("?")) {
    val type = link.substringAfter("sn://").substringBefore("?")
    ProxyEntity(type = universalProfileType(type)).apply {
        putByteArray(Util.zlibDecompress(Util.b64Decode(link.substringAfter("?"))))
    }.requireBean()
} else {
    val type = link.substringAfter("sn://").substringBefore(":")
    ProxyEntity(type = universalProfileType(type)).apply {
        putByteArray(Util.b64Decode(link.substringAfter(":").substringAfter(":")))
    }.requireBean()
}

private fun universalProfileType(name: String): Int {
    TypeMap[name]?.let { return it }
    if (name.startsWith("archive-")) {
        name.removePrefix("archive-").toIntOrNull()?.takeIf { ProtocolRegistry.forType(it) == null }?.let { return it }
    }
    error("Unknown profile type")
}

fun AbstractBean.toUniversalLink(): String {
    var link = "sn://"
    val type = ProxyEntity().putBean(this).type
    link += TypeMap.reversed[type] ?: "archive-$type"
    link += "?"
    link += Util.b64EncodeUrlSafe(Util.zlibCompress(KryoConverters.serialize(this), 9))
    return link
}

fun ProxyGroup.toUniversalLink(): String {
    var link = "sn://subscription?"
    export = true
    link += Util.b64EncodeUrlSafe(Util.zlibCompress(KryoConverters.serialize(this), 9))
    export = false
    return link
}
