package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.databinding.LayoutShareConnectionBinding
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.socks.toUri
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.widget.QRCodeDialog
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Hands the running proxy to other devices on the local networks: the mixed inbound binds to
 * every IPv4 interface with a dedicated credential. Sharing is the existing allowAccess flag;
 * this screen shows where the proxy is reachable and what to import.
 */
class ShareConnectionActivity :
    ThemedActivity(),
    SagerConnection.Callback {

    private lateinit var binding: LayoutShareConnectionBinding
    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_SHARE_CONNECTION)
    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) {
        if (it) snackbar(R.string.vpn_permission_denied).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutShareConnectionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.share_connection)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.baseline_arrow_back_24)
        }

        binding.shareSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked == DataStore.allowAccess) return@setOnCheckedChangeListener
            DataStore.allowAccess = checked
            applyToService(startIfStopped = checked)
            refresh()
        }
        binding.shareCopyCredentials.setOnClickListener {
            copy("${Key.SHARE_USERNAME}:${DataStore.shareSecret}")
        }
        binding.shareRegenerate.setOnClickListener {
            DataStore.regenerateShareSecret()
            applyToService(startIfStopped = false)
            refresh()
            snackbar(R.string.share_connection_regenerated).show()
        }
        connection.connect(this, this)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        connection.disconnect(this)
        super.onDestroy()
    }

    override fun snackbarInternal(text: CharSequence): Snackbar = Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG)

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) = refresh()

    override fun onServiceConnected(service: ISagerNetService) = refresh()

    // The inbound is generated at start: a running service reloads, a stopped one starts when
    // sharing is switched on. The flag lives in the cached store, so wait for the write before
    // the service process reads it.
    private fun applyToService(startIfStopped: Boolean) {
        runOnDefaultDispatcher {
            try {
                DataStore.configurationStore.awaitWrites()
                when {
                    DataStore.serviceState.canStop -> SagerNet.reloadService()

                    // The switch may have been turned off again while the write was pending.
                    startIfStopped && DataStore.allowAccess -> onMainDispatcher { connect.launch(null) }
                }
            } catch (e: Exception) {
                Logs.w(e)
                onMainDispatcher { snackbar(R.string.service_failed).show() }
            }
        }
    }

    private fun refresh() {
        val sharing = DataStore.allowAccess
        binding.shareSwitch.isChecked = sharing
        binding.shareCredentials.text = getString(R.string.share_connection_username) + ": " + Key.SHARE_USERNAME +
            "\n" + getString(R.string.share_connection_password) + ": " + DataStore.shareSecret
        val port = DataStore.mixedPort
        val addresses = lanAddresses()
        binding.shareAddresses.removeAllViews()
        if (addresses.isEmpty()) {
            binding.shareAddresses.addView(TextView(this).apply { setText(R.string.share_connection_no_address) })
        }
        addresses.forEach { (label, address) -> binding.shareAddresses.addView(addressRow(label, address, port)) }
        runOnDefaultDispatcher {
            val status = when {
                !sharing -> getString(R.string.share_connection_off)

                !DataStore.serviceState.connected -> getString(R.string.share_connection_waiting)

                else -> {
                    val profile = ProfileManager.getProfile(DataStore.currentProfile)
                    getString(R.string.share_connection_on, profile?.displayName() ?: "", port)
                }
            }
            onMainDispatcher { binding.shareStatus.text = status }
        }
    }

    private fun addressRow(label: String, address: String, port: Int): LinearLayout {
        val link = SOCKSBean().apply {
            serverAddress = address
            serverPort = port
            username = Key.SHARE_USERNAME
            password = DataStore.shareSecret
            name = getString(R.string.share_connection_link_name)
            initializeDefaultValues()
        }.toUri()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                TextView(context).apply {
                    text = "$address:$port\n$label"
                    setTextIsSelectable(true)
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(textButton(R.string.share_qr_nfc) { QRCodeDialog(link, "$address:$port").show(supportFragmentManager, null) })
            addView(textButton(R.string.share_connection_copy_link) { copy(link) })
        }
    }

    private fun textButton(textRes: Int, onClick: () -> Unit) = MaterialButton(this, null, android.R.attr.borderlessButtonStyle).apply {
        setText(textRes)
        setOnClickListener { onClick() }
    }

    private fun copy(text: String) {
        snackbar(if (SagerNet.trySetPrimaryClip(text)) R.string.copy_success else R.string.copy_failed).show()
    }

    // IPv4 only: the inbound listens on 0.0.0.0. The tun address belongs to this device alone.
    // Whether an address outside the private ranges is reachable depends on the carrier or
    // router in front of it, so the label only says that it is not private.
    // ponytail: interface names are vendor specific, so the label is the raw name.
    private fun lanAddresses(): List<Pair<String, String>> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.name.startsWith("tun") }
            .flatMap { iface ->
                iface.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filterNot { it.isLinkLocalAddress }
                    .map { address ->
                        val label = if (address.isSiteLocalAddress) iface.name else iface.name + ", " + getString(R.string.share_connection_public)
                        label to address.hostAddress!!
                    }
            }
    }.getOrDefault(emptyList())
}
