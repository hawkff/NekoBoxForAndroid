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

package io.nekohasekai.sagernet.fmt.masterdnsvpn

import android.os.Parcelable
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.Serializable

class MasterDnsVpnBean : AbstractBean() {
    @JvmField
    var domains: String? = null

    @JvmField
    var dataEncryptionMethod: Int? = null

    @JvmField
    var encryptionKey: String? = null

    @JvmField
    var resolvers: String? = null

    @JvmField
    var resolverBalancingStrategy: Int? = null

    @JvmField
    var packetDuplicationCount: Int? = null

    @JvmField
    var setupPacketDuplicationCount: Int? = null

    @JvmField
    var autoDisableTimeoutServers: Boolean? = null

    @JvmField
    var autoRemoveLowMtuServers: Boolean? = null

    @JvmField
    var baseEncodeData: Boolean? = null

    @JvmField
    var uploadCompressionType: Int? = null

    @JvmField
    var downloadCompressionType: Int? = null

    @JvmField
    var compressionMinSize: Int? = null

    @JvmField
    var minUploadMtu: Int? = null

    @JvmField
    var minDownloadMtu: Int? = null

    @JvmField
    var maxUploadMtu: Int? = null

    @JvmField
    var maxDownloadMtu: Int? = null

    @JvmField
    var localDnsEnabled: Boolean? = null

    @JvmField
    var localDnsPort: Int? = null

    @JvmField
    var logLevel: String? = null

    @JvmField
    var advancedJson: String? = null

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        domains = domains ?: ""
        dataEncryptionMethod = dataEncryptionMethod ?: 0
        encryptionKey = encryptionKey ?: ""
        resolvers = resolvers ?: "8.8.8.8\n1.1.1.1"
        resolverBalancingStrategy = resolverBalancingStrategy ?: 3
        packetDuplicationCount = packetDuplicationCount ?: 3
        setupPacketDuplicationCount = setupPacketDuplicationCount ?: 4
        autoDisableTimeoutServers = autoDisableTimeoutServers ?: true
        autoRemoveLowMtuServers = autoRemoveLowMtuServers ?: true
        baseEncodeData = baseEncodeData ?: false
        uploadCompressionType = uploadCompressionType ?: 0
        downloadCompressionType = downloadCompressionType ?: 0
        compressionMinSize = compressionMinSize ?: 120
        minUploadMtu = minUploadMtu ?: 38
        minDownloadMtu = minDownloadMtu ?: 200
        maxUploadMtu = maxUploadMtu ?: 150
        maxDownloadMtu = maxDownloadMtu ?: 4000
        localDnsEnabled = localDnsEnabled ?: false
        localDnsPort = localDnsPort ?: 53
        logLevel = logLevel ?: "INFO"
        advancedJson = advancedJson ?: ""
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(0)
        super.serialize(output)
        output.writeString(domains)
        output.writeInt(dataEncryptionMethod!!)
        output.writeString(encryptionKey)
        output.writeString(resolvers)
        output.writeInt(resolverBalancingStrategy!!)
        output.writeInt(packetDuplicationCount!!)
        output.writeInt(setupPacketDuplicationCount!!)
        output.writeBoolean(autoDisableTimeoutServers!!)
        output.writeBoolean(autoRemoveLowMtuServers!!)
        output.writeBoolean(baseEncodeData!!)
        output.writeInt(uploadCompressionType!!)
        output.writeInt(downloadCompressionType!!)
        output.writeInt(compressionMinSize!!)
        output.writeInt(minUploadMtu!!)
        output.writeInt(minDownloadMtu!!)
        output.writeInt(maxUploadMtu!!)
        output.writeInt(maxDownloadMtu!!)
        output.writeBoolean(localDnsEnabled!!)
        output.writeInt(localDnsPort!!)
        output.writeString(logLevel)
        output.writeString(advancedJson)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        domains = input.readString()
        dataEncryptionMethod = input.readInt()
        encryptionKey = input.readString()
        resolvers = input.readString()
        resolverBalancingStrategy = input.readInt()
        packetDuplicationCount = input.readInt()
        setupPacketDuplicationCount = input.readInt()
        autoDisableTimeoutServers = input.readBoolean()
        autoRemoveLowMtuServers = input.readBoolean()
        baseEncodeData = input.readBoolean()
        uploadCompressionType = input.readInt()
        downloadCompressionType = input.readInt()
        compressionMinSize = input.readInt()
        minUploadMtu = input.readInt()
        minDownloadMtu = input.readInt()
        maxUploadMtu = input.readInt()
        maxDownloadMtu = input.readInt()
        localDnsEnabled = input.readBoolean()
        localDnsPort = input.readInt()
        logLevel = input.readString()
        advancedJson = input.readString()
    }

    override fun canTCPing(): Boolean = false

    override fun canMapping(): Boolean = false

    override fun clone(): MasterDnsVpnBean = KryoConverters.deserialize(MasterDnsVpnBean(), KryoConverters.serialize(this))

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<MasterDnsVpnBean> = object : Serializable.CREATOR<MasterDnsVpnBean>() {
            override fun newInstance() = MasterDnsVpnBean()
            override fun newArray(size: Int): Array<MasterDnsVpnBean?> = arrayOfNulls(size)
        }
    }
}
