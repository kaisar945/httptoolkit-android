package tech.httptoolkit.android

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.security.KeyChain
import android.security.KeyChain.EXTRA_CERTIFICATE
import android.security.KeyChain.EXTRA_NAME
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.beust.klaxon.Klaxon
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.sentry.Sentry
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit


const val START_VPN_REQUEST = 123
const val INSTALL_CERT_REQUEST = 456
const val SCAN_REQUEST = 789

enum class MainState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    FAILED
}

private fun getCertificateFingerprint(cert: X509Certificate): String {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(cert.publicKey.encoded)
    val fingerprint = md.digest()
    return Base64.encodeToString(fingerprint, Base64.NO_WRAP)
}

private val ACTIVATE_INTENT = "tech.httptoolkit.android.ACTIVATE"
private val DEACTIVATE_INTENT = "tech.httptoolkit.android.DEACTIVATE"

class MainActivity : AppCompatActivity(), CoroutineScope by MainScope() {

    private val TAG = MainActivity::class.simpleName
    private lateinit var app: HttpToolkitApplication

    private var localBroadcastManager: LocalBroadcastManager? = null
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == VPN_STARTED_BROADCAST) {
                mainState = MainState.CONNECTED
                currentProxyConfig = intent.getParcelableExtra(PROXY_CONFIG_EXTRA)
                updateUi()
            } else if (intent.action == VPN_STOPPED_BROADCAST) {
                mainState = MainState.DISCONNECTED
                currentProxyConfig = null
                updateUi()
            }
        }
    }

    private var mainState: MainState = if (isVpnActive()) MainState.CONNECTED else MainState.DISCONNECTED
    // If connected/late-stage connecting, the proxy we're connected/trying to connect to. Otherwise null.
    private var currentProxyConfig: ProxyConfig? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        localBroadcastManager = LocalBroadcastManager.getInstance(this)
        localBroadcastManager!!.registerReceiver(broadcastReceiver, IntentFilter().apply {
            addAction(VPN_STARTED_BROADCAST)
            addAction(VPN_STOPPED_BROADCAST)
        })

        app = this.application as HttpToolkitApplication
        setContentView(R.layout.main_layout)
        updateUi()

        Log.i(TAG, "Main activity created")

        // Are we being opened by an intent? I.e. a barcode scan/URL elsewhere on the device
        if (intent != null) {
            onNewIntent(intent)
        } else {
            // If not, check if this is a post-install run, and if so configure automatically
            // using the install referrer
            launch {
                val firstRunParams = app.popFirstRunParams()
                if (
                    firstRunParams != null &&
                    firstRunParams.startsWith("https://android.httptoolkit.tech/connect/")
                ) {
                    launch { connectToVpnFromUrl(firstRunParams) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
        app.trackScreen("Main")
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause")
        app.clearScreen()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        localBroadcastManager!!.unregisterReceiver(broadcastReceiver)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        app.trackEvent("Setup", "action-view")

        // RC intents are intents that have passed the RC permission requirement in the manifest.
        // Implicit intents with the matching actions will always use the RC activity, this check
        // protects against explicit intents targeting MainActivity. RC intents are known to be
        // trustworthy, so are allowed to silently activate/deactivate the VPN connection.
        val isRCIntent = intent.component?.className == "tech.httptoolkit.android.RemoteControlMainActivity"

        when {
            // ACTION_VIEW means that somebody had the app installed, and scanned the barcode with
            // a separate barcode app anyway (or opened the QR code URL in a browser)
            intent.action == Intent.ACTION_VIEW -> {
                if (app.lastProxy != null && isVpnConfigured()) {
                    Log.i(TAG, "Showing prompt for ACTION_VIEW intent")

                    // If we were started from an intent (e.g. another barcode scanner/link), and we
                    // had a proxy before (so no prompts required) then confirm before starting the VPN.
                    // Without this any QR code you scan could instantly MitM you.
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Enable Interception")
                        .setIcon(R.drawable.ic_exclamation_triangle)
                        .setMessage(
                            "Do you want to share all this device's HTTP traffic with HTTP Toolkit?" +
                                    "\n\n" +
                                    "Only accept this if you trust the source."
                        )
                        .setPositiveButton("Enable") { _, _ ->
                            Log.i(TAG, "Prompt confirmed")
                            launch { connectToVpnFromUrl(intent.data!!) }
                        }
                        .setNegativeButton("Cancel") { _, _ ->
                            Log.i(TAG, "Prompt cancelled")
                        }
                        .show()
                } else {
                    Log.i(TAG, "Launching from ACTION_VIEW intent")
                    launch { connectToVpnFromUrl(intent.data!!) }
                }
            }

            // RC setup API, used by ADB to enable/disable without prompts.
            // Permission required, checked for via activity-alias in the manifest
            isRCIntent && intent.action == ACTIVATE_INTENT -> {
                launch { connectToVpnFromUrl(intent.data!!) }
            }
            isRCIntent && intent.action == DEACTIVATE_INTENT -> {
                disconnect()
            }

            else -> Log.w(TAG, "Unknown intent. Action ${
                intent.action
            }, data: ${
                intent.data
            }, ${
                if (isRCIntent) "sent as RC intent" else "non-RC"
            }")
        }
    }

    private fun updateUi() {
        val statusText = findViewById<TextView>(R.id.statusText)
        val detailText = findViewById<TextView>(R.id.detailText)

        val buttonContainer = findViewById<LinearLayout>(R.id.buttonLayoutContainer)
        buttonContainer.removeAllViews()

        when (mainState) {
            MainState.DISCONNECTED -> {
                statusText.setText(R.string.disconnected_status)

                detailText.visibility = View.VISIBLE
                detailText.setText(R.string.disconnected_details)

                buttonContainer.visibility = View.VISIBLE
                buttonContainer.addView(primaryButton(R.string.scan_button, ::scanCode))

                val lastProxy = app.lastProxy
                if (lastProxy != null) {
                    buttonContainer.addView(secondaryButton(R.string.reconnect_button) {
                        launch { reconnect(lastProxy) }
                    })
                }
            }
            MainState.CONNECTING -> {
                statusText.setText(R.string.connecting_status)

                detailText.visibility = View.GONE
                buttonContainer.visibility = View.GONE
            }
            MainState.CONNECTED -> {
                statusText.setText(R.string.connected_status)

                detailText.visibility = View.VISIBLE
                detailText.text = getString(
                    R.string.connected_details,
                    currentProxyConfig!!.ip,
                    currentProxyConfig!!.port
                )

                buttonContainer.visibility = View.VISIBLE
                buttonContainer.addView(primaryButton(R.string.disconnect_button, ::disconnect))
            }
            MainState.DISCONNECTING -> {
                statusText.setText(R.string.disconnecting_status)

                detailText.visibility = View.GONE
                buttonContainer.visibility = View.GONE
            }
            MainState.FAILED -> {
                statusText.setText(R.string.failed_status)

                detailText.visibility = View.VISIBLE
                detailText.setText(R.string.failed_details)

                buttonContainer.visibility = View.VISIBLE
                buttonContainer.addView(primaryButton(R.string.try_again_button, ::resetAfterFailure))
            }
        }

        if (buttonContainer.visibility == View.VISIBLE) {
            buttonContainer.addView(secondaryButton(R.string.docs_button, ::openDocs))
        }
    }

    private fun primaryButton(@StringRes contentId: Int, clickHandler: () -> Unit): Button {
        val button = layoutInflater.inflate(R.layout.primary_button, null) as Button
        button.setText(contentId)
        button.setOnClickListener { clickHandler() }
        return button
    }

    private fun secondaryButton(@StringRes contentId: Int, clickHandler: () -> Unit): Button {
        val button = layoutInflater.inflate(R.layout.secondary_button, null) as Button
        button.setText(contentId)
        button.setOnClickListener { clickHandler() }
        return button
    }

    private fun scanCode() {
        app.trackEvent("Button", "scan-code")
        startActivityForResult(Intent(this, ScanActivity::class.java), SCAN_REQUEST)
    }

    private suspend fun connectToVpn(config: ProxyConfig) {
        Log.i(TAG, "Connect to VPN")

        this.currentProxyConfig = config
        this.mainState = MainState.CONNECTING

        withContext(Dispatchers.Main) { updateUi() }

        app.trackEvent("Button", "start-vpn")
        val vpnIntent = VpnService.prepare(this)
        Log.i(TAG, if (vpnIntent != null) "got intent" else "no intent")
        val vpnNotConfigured = vpnIntent != null

        if (!isCertTrusted(config)) {
            // The cert isn't trusted, and the VPN may need setup, so there'll be a series of prompts
            // here. Explain them beforehand, so users understand what's going on.
            withContext(Dispatchers.Main) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Enable interception")
                    .setIcon(R.drawable.ic_info_circle)
                    .setMessage(
                        "To intercept traffic from this device, you need to " +
                        (if (vpnNotConfigured) "activate HTTP Toolkit's VPN and " else "") +
                        "trust your HTTP Toolkit's certificate authority. " +
                        "\n\n" +
                        "Please accept the following prompts to allow this." +
                        if (!isDeviceSecured())
                            "\n\n" +
                            "Due to Android security requirements, trusting the certificate will " +
                            "require you to set a PIN, password or pattern for this device."
                        else " To trust the certificate, your device PIN will be required."
                    )
                    .setPositiveButton("Ok") { _, _ ->
                        if (vpnNotConfigured) {
                            startActivityForResult(vpnIntent, START_VPN_REQUEST)
                        } else {
                            onActivityResult(START_VPN_REQUEST, RESULT_OK, null)
                        }
                    }
                    .show()
            }
        } else if (vpnNotConfigured) {
            // In this case the VPN needs setup, but the cert is trusted already, so it's
            // a single confirmation. Pretty clear, no need to explain. This happens if the
            // VPN/app was removed from the device in the past, or when using injected system certs.
            startActivityForResult(vpnIntent, START_VPN_REQUEST)
        } else {
            // VPN is trusted & cert setup already, lets get to it.
            onActivityResult(START_VPN_REQUEST, RESULT_OK, null)
        }

    }

    private fun disconnect() {
        currentProxyConfig = null
        mainState = MainState.DISCONNECTING
        updateUi()

        app.trackEvent("Button", "stop-vpn")
        startService(Intent(this, ProxyVpnService::class.java).apply {
            action = STOP_VPN_ACTION
        })
    }

    private suspend fun reconnect(lastProxy: ProxyConfig) {
        app.trackEvent("Button", "reconnect")

        withContext(Dispatchers.Main) {
            mainState = MainState.CONNECTING
            updateUi()
        }

        try {
            // Revalidates the config, to ensure the server is available (and drop retries if not)
            val config = getProxyConfig(
                ProxyInfo(
                    listOf(lastProxy.ip),
                    lastProxy.port,
                    getCertificateFingerprint(lastProxy.certificate as X509Certificate)
                )
            )
            connectToVpn(config)
        } catch (e: Exception) {
            app.lastProxy = null

            Log.e(TAG, e.toString())
            e.printStackTrace()
            Sentry.capture(e)
            withContext(Dispatchers.Main) {
                app.trackEvent("Setup", "reconnect-failed")
                mainState = MainState.FAILED
                updateUi()
            }
        }
    }

    private fun resetAfterFailure() {
        app.trackEvent("Button", "try-again")
        currentProxyConfig = null
        mainState = MainState.DISCONNECTED
        updateUi()
    }

    private fun openDocs() {
        app.trackEvent("Button", "open-docs")
        val browserIntent = Intent(Intent.ACTION_VIEW,
            Uri.parse("https://httptoolkit.tech/docs/guides/android")
        )
        startActivity(browserIntent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        Log.i(TAG, "onActivityResult")
        Log.i(TAG, when (requestCode) {
            START_VPN_REQUEST -> "start-vpn"
            INSTALL_CERT_REQUEST -> "install-cert"
            SCAN_REQUEST -> "scan-request"
            else -> requestCode.toString()
        })
        Log.i(TAG, if (resultCode == RESULT_OK) "ok" else resultCode.toString())

        if (resultCode == RESULT_OK) {
            if (requestCode == START_VPN_REQUEST && currentProxyConfig != null) {
                Log.i(TAG, "Installing cert")
                ensureCertificateTrusted(currentProxyConfig!!)
            } else if (requestCode == INSTALL_CERT_REQUEST) {
                Log.i(TAG, "Starting VPN")
                startService(Intent(this, ProxyVpnService::class.java).apply {
                    action = START_VPN_ACTION
                    putExtra(PROXY_CONFIG_EXTRA, currentProxyConfig)
                })
            } else if (requestCode == SCAN_REQUEST && data != null) {
                val url = data.getStringExtra(SCANNED_URL_EXTRA)
                launch { connectToVpnFromUrl(url) }
            }
        } else {
            Sentry.capture("Non-OK result $resultCode for requestCode $requestCode")
            mainState = MainState.FAILED
            updateUi()
        }
    }

    private suspend fun connectToVpnFromUrl(url: String) {
        connectToVpnFromUrl(Uri.parse(url))
    }

    private suspend fun connectToVpnFromUrl(uri: Uri) {
        Log.i(TAG, "Connecting to VPN from URL: $uri")
        if (
            mainState != MainState.DISCONNECTED &&
            mainState != MainState.FAILED
        ) return

        withContext(Dispatchers.Main) {
            mainState = MainState.CONNECTING
            updateUi()
        }

        withContext(Dispatchers.IO) {
            try {
                val dataBase64 = uri.getQueryParameter("data")

                // Data is a JSON string, encoded as base64, to solve escaping & ensure that the
                // most popular standard barcode apps treat it as a single URL (some get confused by
                // JSON that contains ip addresses otherwise)
                val data = String(Base64.decode(dataBase64, Base64.URL_SAFE), StandardCharsets.UTF_8)
                Log.d(TAG, "URL data is $data")

                val proxyInfo = Klaxon().parse<ProxyInfo>(data)
                    ?: throw IllegalArgumentException("Invalid proxy JSON: $data")

                val config = getProxyConfig(proxyInfo)
                connectToVpn(config)
            } catch (e: Exception) {
                Log.e(TAG, e.toString())
                e.printStackTrace()
                Sentry.capture(e)
                withContext(Dispatchers.Main) {
                    app.trackEvent("Setup", "connect-failed")
                    mainState = MainState.FAILED
                    updateUi()
                }
            }
        }
    }

    private suspend fun getProxyConfig(proxyInfo: ProxyInfo): ProxyConfig {
        return withContext(Dispatchers.IO) {
            Log.v(TAG, "Validating proxy info $proxyInfo")

            val proxyTests = proxyInfo.addresses.map { address ->
                supervisorScope {
                    async {
                        testProxyAddress(
                            address,
                            proxyInfo.port,
                            proxyInfo.certFingerprint
                        )
                    }
                }
            }

            // Returns with the first working proxy config (cert & address),
            // or throws if all possible addresses are unreachable/invalid
            // Once the first test succeeds, we cancel any others
            val result = proxyTests.awaitFirst()
            proxyTests.forEach { test ->
                test.cancel()
            }
            return@withContext result
        }
    }

    private suspend fun testProxyAddress(
        address: String,
        port: Int,
        expectedFingerprint: String
    ): ProxyConfig {
        return withContext(Dispatchers.IO) {
            val certFactory = CertificateFactory.getInstance("X.509")

            val httpClient = OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(address, port)))
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url("http://android.httptoolkit.tech/config")
                .build()

            try {
                val configString = httpClient.newCall(request).execute().use { response ->
                    if (response.code != 200) {
                        throw ConnectException("Proxy responded with non-200: ${response.code}")
                    }
                    response.body!!.string()
                }
                val config = Klaxon().parse<ReceivedProxyConfig>(configString)!!

                val foundCert = certFactory.generateCertificate(
                    ByteArrayInputStream(config.certificate.toByteArray(Charsets.UTF_8))
                ) as X509Certificate
                val foundCertFingerprint = getCertificateFingerprint(foundCert)

                if (foundCertFingerprint == expectedFingerprint) {
                    ProxyConfig(
                        address,
                        port,
                        foundCert
                    )
                } else {
                    throw CertificateException(
                        "Proxy returned mismatched certificate: '${
                            expectedFingerprint
                        }' != '$foundCertFingerprint' ($address)"
                    )
                }
            } catch (e: Exception) {
                Log.i(TAG, "Error testing proxy address $address: $e")
                throw e
            }
        }
    }

    /**
     * Does the device have a PIN/pattern/password set? Relevant because if not, the cert
     * setup will require the user to add one.
     */
    private fun isDeviceSecured(): Boolean {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            keyguardManager.isDeviceSecure
        } else {
            // Imperfect but close though: also returns true if the device has a locked SIM card.
            keyguardManager.isKeyguardSecure
        }
    }

    private fun isVpnConfigured(): Boolean {
        return VpnService.prepare(this) == null
    }

    private fun isCertTrusted(proxyConfig: ProxyConfig): Boolean {
        val keyStore = KeyStore.getInstance("AndroidCAStore")
        keyStore.load(null, null)

        val certificateAlias = keyStore.getCertificateAlias(proxyConfig.certificate)
        return certificateAlias != null
    }

    private fun ensureCertificateTrusted(proxyConfig: ProxyConfig) {
        if (!isCertTrusted(proxyConfig)) {
            app.trackEvent("Setup", "installing-cert")
            Log.i(TAG, "Certificate not trusted, prompting to install")
            val certInstallIntent = KeyChain.createInstallIntent()
            certInstallIntent.putExtra(EXTRA_NAME, "HTTP Toolkit CA")
            certInstallIntent.putExtra(EXTRA_CERTIFICATE, proxyConfig.certificate.encoded)
            startActivityForResult(certInstallIntent, INSTALL_CERT_REQUEST)
        } else {
            app.trackEvent("Setup", "existing-cert")
            Log.i(TAG, "Certificate already trusted, continuing")
            onActivityResult(INSTALL_CERT_REQUEST, RESULT_OK, null)
        }
    }

}
