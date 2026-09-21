package io.nekohasekai.sagernet.fmt.internal

import android.os.Parcelable
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.Serializable
import moe.matsuri.nb4a.utils.JavaUtil

class ChainBean : InternalBean() {
    @JvmField
    var proxies: List<Long>? = null

    override fun displayName(): String {
        if (JavaUtil.isNotBlank(name)) {
            return name!!
        } else {
            return ("Chain " + kotlin.math.abs(hashCode()))
        }
    }

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        name = name ?: ""
        proxies = proxies ?: mutableListOf()
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(1)
        output.writeInt(proxies!!.size)
        for (proxy in proxies!!) {
            output.writeLong(proxy)
        }
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        if (version < 1) {
            input.readString()
            input.readInt()
        }
        val length = input.readInt()
        val decoded = mutableListOf<Long>()
        proxies = decoded
        repeat(length) {
            decoded.add(input.readLong())
        }
    }

    override fun clone(): ChainBean = KryoConverters.deserialize(ChainBean(), KryoConverters.serialize(this))

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<ChainBean> = object : Serializable.CREATOR<ChainBean>() {
            override fun newInstance() = ChainBean()
            override fun newArray(size: Int): Array<ChainBean?> = arrayOfNulls(size)
        }
    }
}
