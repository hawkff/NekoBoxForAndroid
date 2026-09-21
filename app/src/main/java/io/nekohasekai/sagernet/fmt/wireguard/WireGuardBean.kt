package io.nekohasekai.sagernet.fmt.wireguard

import android.os.Parcelable
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.Serializable

class WireGuardBean : AbstractBean() {
    @JvmField
    var localAddress: String? = null

    @JvmField
    var privateKey: String? = null

    @JvmField
    var peerPublicKey: String? = null

    @JvmField
    var peerPreSharedKey: String? = null

    @JvmField
    var mtu: Int? = null

    @JvmField
    var reserved: String? = null

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        localAddress = localAddress ?: ""
        privateKey = privateKey ?: ""
        peerPublicKey = peerPublicKey ?: ""
        peerPreSharedKey = peerPreSharedKey ?: ""
        mtu = mtu ?: 1420
        reserved = reserved ?: ""
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(2)
        super.serialize(output)
        output.writeString(localAddress)
        output.writeString(privateKey)
        output.writeString(peerPublicKey)
        output.writeString(peerPreSharedKey)
        output.writeInt(mtu!!)
        output.writeString(reserved)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        localAddress = input.readString()
        privateKey = input.readString()
        peerPublicKey = input.readString()
        peerPreSharedKey = input.readString()
        mtu = input.readInt()
        reserved = input.readString()
    }

    override fun canTCPing(): Boolean = false

    override fun clone(): WireGuardBean = KryoConverters.deserialize(WireGuardBean(), KryoConverters.serialize(this))

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<WireGuardBean> = object : Serializable.CREATOR<WireGuardBean>() {
            override fun newInstance() = WireGuardBean()
            override fun newArray(size: Int): Array<WireGuardBean?> = arrayOfNulls(size)
        }
    }
}
