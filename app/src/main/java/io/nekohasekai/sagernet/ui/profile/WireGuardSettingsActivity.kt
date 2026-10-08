package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.wireguard.MAX_WIREGUARD_PEERS
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type

class WireGuardSettingsActivity : ProfileSettingsActivity<WireGuardBean>() {

    override fun createEntity() = WireGuardBean()

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, "name"))
    private val serverAddress = pbm.add(PreferenceBinding(Type.Text, "serverAddress"))
    private val serverPort = pbm.add(PreferenceBinding(Type.TextToInt, "serverPort"))
    private val localAddress = pbm.add(PreferenceBinding(Type.Text, "localAddress"))
    private val privateKey = pbm.add(PreferenceBinding(Type.Text, "privateKey"))
    private val peerPublicKey = pbm.add(PreferenceBinding(Type.Text, "peerPublicKey"))
    private val peerPreSharedKey = pbm.add(PreferenceBinding(Type.Text, "peerPreSharedKey"))
    private val mtu = pbm.add(PreferenceBinding(Type.TextToInt, "mtu"))
    private val reserved = pbm.add(PreferenceBinding(Type.Text, "reserved"))
    private val allowedIPs = pbm.add(PreferenceBinding(Type.Text, "allowedIPs"))
    private val persistentKeepalive = pbm.add(PreferenceBinding(Type.TextToInt, "persistentKeepalive"))
    private val extraPeers = pbm.add(PreferenceBinding(Type.Text, "extraPeers"))
    private val serverPeerPosition = pbm.add(PreferenceBinding(Type.TextToInt, "serverPeerPosition"))
    private val dnsMode = pbm.add(PreferenceBinding(Type.TextToInt, "dnsMode"))
    private val importedDnsServers = pbm.add(PreferenceBinding(Type.Text, "importedDnsServers"))
    private val importedDnsDomains = pbm.add(PreferenceBinding(Type.Text, "importedDnsDomains"))
    private val customDnsServers = pbm.add(PreferenceBinding(Type.Text, "customDnsServers"))
    private val customDnsDomains = pbm.add(PreferenceBinding(Type.Text, "customDnsDomains"))
    private val status = WireGuardStatusLoader(this)

    override fun WireGuardBean.init() {
        pbm.writeToCacheAll(this)
    }

    override fun WireGuardBean.serialize() {
        pbm.fromCacheAll(this)
    }

    override suspend fun saveAndExit() {
        val draft = ((proxyEntity?.requireBean() as? WireGuardBean)?.clone() ?: createEntity().applyDefaultValues()).apply { serialize() }
        val problem = wireGuardDraftProblem(draft)
        if (problem != null) {
            onMainDispatcher { Toast.makeText(this@WireGuardSettingsActivity, problem, Toast.LENGTH_LONG).show() }
            return
        }
        super.saveAndExit()
    }

    override fun PreferenceFragmentCompat.createPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.wireguard_preferences)
        pbm.setPreferenceFragment(this)

        (serverPort.preference as EditTextPreference)
            .setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        (privateKey.preference as EditTextPreference).summaryProvider = PasswordSummaryProvider
        (mtu.preference as EditTextPreference).setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        (persistentKeepalive.preference as EditTextPreference).acceptNumbers(0..65535)
        (serverPeerPosition.preference as EditTextPreference).acceptNumbers(0..MAX_WIREGUARD_PEERS - 1)
        (extraPeers.preference as EditTextPreference).apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Monospace)
            summaryProvider = ExtraPeersSummaryProvider
        }
        status.bind(findPreference("wireguardStatus")!!)
    }
}
