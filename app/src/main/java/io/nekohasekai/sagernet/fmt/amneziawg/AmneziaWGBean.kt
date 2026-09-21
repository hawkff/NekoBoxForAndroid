/*
 * Copyright (C) 2026 by nekohasekai <contact-git@sekai.icu>                  *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 ******************************************************************************/

package io.nekohasekai.sagernet.fmt.amneziawg

import android.os.Parcelable
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.Serializable

class AmneziaWGBean : AbstractBean() {
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

    @JvmField
    var jc: Int? = null

    @JvmField
    var jmin: Int? = null

    @JvmField
    var jmax: Int? = null

    @JvmField
    var s1: Int? = null

    @JvmField
    var s2: Int? = null

    @JvmField
    var s3: Int? = null

    @JvmField
    var s4: Int? = null

    @JvmField
    var h1: String? = null

    @JvmField
    var h2: String? = null

    @JvmField
    var h3: String? = null

    @JvmField
    var h4: String? = null

    @JvmField
    var i1: String? = null

    @JvmField
    var i2: String? = null

    @JvmField
    var i3: String? = null

    @JvmField
    var i4: String? = null

    @JvmField
    var i5: String? = null

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        localAddress = localAddress ?: ""
        privateKey = privateKey ?: ""
        peerPublicKey = peerPublicKey ?: ""
        peerPreSharedKey = peerPreSharedKey ?: ""
        mtu = mtu ?: 1420
        reserved = reserved ?: ""
        jc = jc ?: 0
        jmin = jmin ?: 0
        jmax = jmax ?: 0
        s1 = s1 ?: 0
        s2 = s2 ?: 0
        s3 = s3 ?: 0
        s4 = s4 ?: 0
        h1 = h1 ?: ""
        h2 = h2 ?: ""
        h3 = h3 ?: ""
        h4 = h4 ?: ""
        i1 = i1 ?: ""
        i2 = i2 ?: ""
        i3 = i3 ?: ""
        i4 = i4 ?: ""
        i5 = i5 ?: ""
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(0)
        super.serialize(output)
        output.writeString(localAddress)
        output.writeString(privateKey)
        output.writeString(peerPublicKey)
        output.writeString(peerPreSharedKey)
        output.writeInt(mtu!!)
        output.writeString(reserved)
        output.writeInt(jc!!)
        output.writeInt(jmin!!)
        output.writeInt(jmax!!)
        output.writeInt(s1!!)
        output.writeInt(s2!!)
        output.writeInt(s3!!)
        output.writeInt(s4!!)
        output.writeString(h1)
        output.writeString(h2)
        output.writeString(h3)
        output.writeString(h4)
        output.writeString(i1)
        output.writeString(i2)
        output.writeString(i3)
        output.writeString(i4)
        output.writeString(i5)
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
        jc = input.readInt()
        jmin = input.readInt()
        jmax = input.readInt()
        s1 = input.readInt()
        s2 = input.readInt()
        s3 = input.readInt()
        s4 = input.readInt()
        h1 = input.readString()
        h2 = input.readString()
        h3 = input.readString()
        h4 = input.readString()
        i1 = input.readString()
        i2 = input.readString()
        i3 = input.readString()
        i4 = input.readString()
        i5 = input.readString()
    }

    override fun canTCPing(): Boolean = false

    override fun clone(): AmneziaWGBean = KryoConverters.deserialize(AmneziaWGBean(), KryoConverters.serialize(this))

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<AmneziaWGBean> = object : Serializable.CREATOR<AmneziaWGBean>() {
            override fun newInstance() = AmneziaWGBean()
            override fun newArray(size: Int): Array<AmneziaWGBean?> = arrayOfNulls(size)
        }
    }
}
