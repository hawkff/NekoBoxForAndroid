package io.nekohasekai.sagernet.database

import android.os.Parcelable
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.Serializable

class SubscriptionBean : Serializable() {
    @JvmField
    var type: Int? = null

    @JvmField
    var link: String? = null

    @JvmField
    var token: String? = null

    @JvmField
    var forceResolve: Boolean? = null

    @JvmField
    var deduplication: Boolean? = null

    @JvmField
    var updateWhenConnectedOnly: Boolean? = null

    @JvmField
    var customUserAgent: String? = null

    @JvmField
    var autoUpdate: Boolean? = null

    @JvmField
    var autoUpdateDelay: Int? = null

    @JvmField
    var lastUpdated: Int? = null

    @JvmField
    var filterMode: Int? = null

    @JvmField
    var filterRegex: String? = null

    @JvmField
    var customDnsResolver: String? = null

    @JvmField
    var bytesUsed: Long? = null

    @JvmField
    var bytesRemaining: Long? = null

    @JvmField
    var username: String? = null

    @JvmField
    var expiryDate: Int? = null

    @JvmField
    var protocols: MutableList<String>? = null

    @JvmField
    var subscriptionUserinfo: String? = null

    override fun serializeToBuffer(output: ByteBufferOutput) {
        output.writeInt(3)
        output.writeInt(type!!)
        output.writeString(link)
        output.writeBoolean(forceResolve!!)
        output.writeBoolean(deduplication!!)
        output.writeBoolean(updateWhenConnectedOnly!!)
        output.writeString(customUserAgent)
        output.writeBoolean(autoUpdate!!)
        output.writeInt(autoUpdateDelay!!)
        output.writeInt(lastUpdated!!)
        output.writeString(subscriptionUserinfo)
        output.writeInt(filterMode!!)
        output.writeString(filterRegex)
        output.writeString(customDnsResolver)
    }

    fun serializeForShare(output: ByteBufferOutput) {
        output.writeInt(0)
        output.writeInt(type!!)
        output.writeString(link)
        output.writeBoolean(forceResolve!!)
        output.writeBoolean(deduplication!!)
        output.writeBoolean(updateWhenConnectedOnly!!)
        output.writeString(customUserAgent)
    }

    override fun deserializeFromBuffer(input: ByteBufferInput) {
        val version = input.readInt()
        type = input.readInt()
        link = input.readString()
        forceResolve = input.readBoolean()
        deduplication = input.readBoolean()
        updateWhenConnectedOnly = input.readBoolean()
        customUserAgent = input.readString()
        autoUpdate = input.readBoolean()
        autoUpdateDelay = input.readInt()
        lastUpdated = input.readInt()
        subscriptionUserinfo = input.readString()
        if (version >= 2) {
            filterMode = input.readInt()
            filterRegex = input.readString()
        }
        if (version >= 3) {
            customDnsResolver = input.readString()
        }
    }

    fun deserializeFromShare(input: ByteBufferInput) {
        val version = input.readInt()
        type = input.readInt()
        link = input.readString()
        forceResolve = input.readBoolean()
        deduplication = input.readBoolean()
        updateWhenConnectedOnly = input.readBoolean()
        customUserAgent = input.readString()
    }

    override fun initializeDefaultValues() {
        type = type ?: 0
        link = link ?: ""
        token = token ?: ""
        forceResolve = forceResolve ?: false
        deduplication = deduplication ?: false
        updateWhenConnectedOnly = updateWhenConnectedOnly ?: false
        customUserAgent = customUserAgent ?: ""
        autoUpdate = autoUpdate ?: false
        autoUpdateDelay = autoUpdateDelay ?: 1440
        lastUpdated = lastUpdated ?: 0
        filterMode = filterMode ?: 0
        filterRegex = filterRegex ?: ""
        customDnsResolver = customDnsResolver ?: ""
        bytesUsed = bytesUsed ?: 0L
        bytesRemaining = bytesRemaining ?: 0L
        username = username ?: ""
        expiryDate = expiryDate ?: 0
        protocols = protocols ?: mutableListOf()
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<SubscriptionBean> = object : Serializable.CREATOR<SubscriptionBean>() {
            override fun newInstance() = SubscriptionBean()
            override fun newArray(size: Int): Array<SubscriptionBean?> = arrayOfNulls(size)
        }
    }
}
