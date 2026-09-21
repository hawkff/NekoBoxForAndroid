package io.nekohasekai.sagernet.fmt

import android.app.Application
import android.os.Parcel
import android.os.Parcelable
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.SubscriptionBean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Modifier

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class StoredProfileCompatibilityTest {
    private val gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()

    private fun resource(name: String) = checkNotNull(javaClass.getResource("/profiles/$name")).readText()
    private fun String.bytes() = trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun bean(name: String) = Class.forName(name).getDeclaredConstructor().newInstance() as Serializable

    @Test
    fun frozenJavaBlobsDecodeAndReencodeWithoutChangingStoredFields() {
        for (entry in JsonParser.parseString(resource("profiles.json")).asJsonArray) {
            val fixture = entry.asJsonObject
            val name = fixture["name"].asString
            val bytes = fixture["hex"].asString.bytes()
            val decoded = bean(fixture["class"].asString)
            ByteBufferInput(bytes).use { input ->
                decoded.deserializeFromBuffer(input)
                assertEquals(name, bytes.size, input.position())
            }
            decoded.initializeDefaultValues()
            assertEquals(name, fixture["decoded"], gson.toJsonTree(decoded))
            assertArrayEquals(name, fixture["canonicalHex"].asString.bytes(), KryoConverters.serialize(decoded))
            if (decoded is AbstractBean) {
                assertArrayEquals(name, KryoConverters.serialize(decoded), KryoConverters.serialize(decoded.clone()))
            }
        }
    }

    @Test
    fun publicParcelableCreatorsPreserveFrozenProfiles() {
        for (entry in JsonParser.parseString(resource("profiles.json")).asJsonArray) {
            val fixture = entry.asJsonObject
            val name = fixture["name"].asString
            val instance = KryoConverters.deserialize(bean(fixture["class"].asString), fixture["hex"].asString.bytes())
            val creator = instance.javaClass.getField("CREATOR").get(null) as Parcelable.Creator<*>
            val array = creator.newArray(2)
            assertEquals(name, 2, array.size)
            assertTrue(name, array.all { it == null })
            val parcel = Parcel.obtain()
            try {
                instance.writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                val restored = creator.createFromParcel(parcel) as Serializable
                assertEquals(name, fixture["decoded"], gson.toJsonTree(restored))
                assertArrayEquals(name, fixture["canonicalHex"].asString.bytes(), KryoConverters.serialize(restored))
            } finally {
                parcel.recycle()
            }
        }
    }

    @Test
    fun freshInstancesKeepNullableFieldsAndTheirOriginalDefaults() {
        for (entry in JsonParser.parseString(resource("constructors.json")).asJsonArray) {
            val fixture = entry.asJsonObject
            val name = fixture["class"].asString
            val instance = bean(name)
            assertEquals(name, fixture["fresh"], gson.toJsonTree(instance))
            assertEquals(
                name,
                fixture["fields"],
                gson.toJsonTree(
                    instance.javaClass.fields.filterNot { Modifier.isStatic(it.modifiers) }
                        .associate { it.name to it.type.name },
                ),
            )
            instance.initializeDefaultValues()
            assertEquals(name, fixture["defaults"], gson.toJsonTree(instance))
        }
    }

    @Test
    fun subscriptionShareBytesRemainCompatible() {
        val bytes = resource("subscription-share.hex").bytes()
        val subscription = SubscriptionBean()
        ByteBufferInput(bytes).use { input ->
            subscription.deserializeFromShare(input)
            assertEquals(bytes.size, input.position())
        }
        subscription.initializeDefaultValues()
        assertEquals("https://example.com/subscription", subscription.link)
        ByteBufferOutput(8192).use { output ->
            subscription.serializeForShare(output)
            assertArrayEquals(bytes, output.toBytes())
        }
    }
}
