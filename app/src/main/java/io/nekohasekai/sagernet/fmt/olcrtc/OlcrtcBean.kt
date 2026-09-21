/*
 * Copyright (C) 2026 by nekohasekai <contact-git@sekai.icu>                  *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.fmt.olcrtc

import android.os.Parcelable
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.Serializable

class OlcrtcBean : AbstractBean() {
    @JvmField
    var carrier: String? = null

    @JvmField
    var roomId: String? = null

    @JvmField
    var clientId: String? = null

    @JvmField
    var keyHex: String? = null

    @JvmField
    var transport: String? = null

    @JvmField
    var vp8Fps: Int? = null

    @JvmField
    var vp8BatchSize: Int? = null

    @JvmField
    var dnsServer: String? = null

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        carrier = carrier ?: "jitsi"
        roomId = roomId ?: ""
        clientId = clientId ?: ""
        keyHex = keyHex ?: ""
        transport = transport ?: "vp8channel"
        vp8Fps = vp8Fps ?: 30
        vp8BatchSize = vp8BatchSize ?: 8
        dnsServer = dnsServer ?: ""
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(0)
        super.serialize(output)
        output.writeString(carrier)
        output.writeString(roomId)
        output.writeString(clientId)
        output.writeString(keyHex)
        output.writeString(transport)
        output.writeInt(vp8Fps!!)
        output.writeInt(vp8BatchSize!!)
        output.writeString(dnsServer)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        carrier = input.readString()
        roomId = input.readString()
        clientId = input.readString()
        keyHex = input.readString()
        transport = input.readString()
        vp8Fps = input.readInt()
        vp8BatchSize = input.readInt()
        dnsServer = input.readString()
    }

    override fun canTCPing(): Boolean = false

    override fun canMapping(): Boolean = false

    override fun clone(): OlcrtcBean = KryoConverters.deserialize(OlcrtcBean(), KryoConverters.serialize(this))

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<OlcrtcBean> = object : Serializable.CREATOR<OlcrtcBean>() {
            override fun newInstance() = OlcrtcBean()
            override fun newArray(size: Int): Array<OlcrtcBean?> = arrayOfNulls(size)
        }
    }
}
