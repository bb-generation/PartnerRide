package net.bbgen.karoo.partnerride.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.PlayBeepPattern
import io.hammerhead.karooext.models.ReleaseBluetooth
import io.hammerhead.karooext.models.RequestBluetooth
import io.hammerhead.karooext.models.TurnScreenOn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.bbgen.karoo.partnerride.MainActivity
import net.bbgen.karoo.partnerride.R
import net.bbgen.karoo.partnerride.core.CoupleCode
import net.bbgen.karoo.partnerride.core.GapEngine
import net.bbgen.karoo.partnerride.core.GapRepository
import net.bbgen.karoo.partnerride.core.GapResult
import net.bbgen.karoo.partnerride.core.GpsFix
import net.bbgen.karoo.partnerride.core.PacketCodec
import net.bbgen.karoo.partnerride.core.ZoneTracker
import net.bbgen.karoo.partnerride.core.roundGapForDisplay
import net.bbgen.karoo.partnerride.data.PartnerRideSettings
import net.bbgen.karoo.partnerride.data.streamSettings
import kotlin.math.abs

/**
 * Foreground service that runs the whole partner link: broadcasts our own GPS position over
 * connectionless BLE advertising, scans for the partner's broadcasts, and feeds the [GapEngine].
 *
 * Active whenever the extension is enabled in settings, independent of ride recording. Both
 * devices run identical roles — no pairing, no GATT, no reconnect logic; recovery after the
 * partner goes out of range is inherent to the stateless design.
 */
class PartnerLinkService : Service() {

    private lateinit var karooSystem: KarooSystemService
    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var wakeLock: PowerManager.WakeLock? = null
    private val engine = GapEngine()
    private val zoneTracker = ZoneTracker()

    @Volatile
    private var settings = PartnerRideSettings()

    @Volatile
    private var coupleTag = CoupleCode.tag("")

    private var advertisingSet: AdvertisingSet? = null
    private var advertisingStartPending = false
    private var scanning = false
    private var alertArmed = true

    /** Start times of recent BLE scans; Android blocks apps starting >5 scans per 30 s. */
    private val recentScanStarts = ArrayDeque<Long>()

    /**
     * Backoff for retrying a failed scan start. A scan failure used to be terminal — nothing
     * retried and the 20-minute restart loop skipped a non-scanning link — so one transient
     * SCAN_FAILED_* killed the partner link for the rest of the ride.
     */
    private var scanRetryDelayMs = SCAN_RETRY_BASE_MS

    /** Named (so it is cancellable and cannot stack up) retry for [startScanIfAllowed]. */
    private val scanRetryRunnable = Runnable { startScanIfAllowed() }

    /** Schedules the next attempt after a *failure*, growing the backoff. Link thread only. */
    private fun scheduleScanRetry() {
        val delayMs = scanRetryDelayMs
        scanRetryDelayMs = (scanRetryDelayMs * 2).coerceAtMost(SCAN_RETRY_MAX_MS)
        postScanRetry(delayMs)
    }

    /** Re-attempts a start after [delayMs] without counting it as a failure. Link thread only. */
    private fun postScanRetry(delayMs: Long) {
        handler.removeCallbacks(scanRetryRunnable)
        handler.postDelayed(scanRetryRunnable, delayMs)
    }

    private val bluetoothAdapter: BluetoothAdapter?
        get() = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    // ------------------------------------------------------------------ lifecycle

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()

        val missing = missingPermissions(this)
        if (missing.isNotEmpty()) {
            GapRepository.update {
                it.copy(
                    missingPermissions = missing,
                    statusMessage = getString(R.string.status_permissions_missing),
                )
            }
            stopSelf()
            return
        }

        handlerThread = HandlerThread("partnerride-link").also { it.start() }
        handler = Handler(handlerThread.looper)

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "partnerride:link")
            .also { it.acquire() }

        karooSystem = KarooSystemService(applicationContext)
        karooSystem.connect { connected ->
            if (connected) {
                // Karoo OS owns the radios: ask for BT to be (and stay) on for us.
                karooSystem.dispatch(RequestBluetooth(BT_RESOURCE_ID))
            }
        }

        registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))

        GapRepository.update {
            it.copy(
                serviceRunning = true,
                missingPermissions = emptyList(),
                statusMessage = null,
                bluetoothReady = bluetoothAdapter?.isEnabled == true,
                // Fresh session: clear leftover fix/packet ages from an earlier run so the field
                // starts at the gray "no contact yet" state, not at a stale red "signal lost".
                lastOwnFixElapsedMs = null,
                lastPacketElapsedMs = null,
                smoothedGapMeters = null,
            )
        }

        startLocationUpdates()

        scope.launch { collectSettings() }
        scope.launch { scanRestartLoop() }

        if (bluetoothAdapter?.isEnabled == true) {
            handler.post { startScanIfAllowed() }
        } else {
            GapRepository.update { it.copy(statusMessage = getString(R.string.status_bluetooth_off)) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        if (this::handler.isInitialized) {
            handler.removeCallbacksAndMessages(null)
            stopScan()
            stopAdvertising()
            (getSystemService(Context.LOCATION_SERVICE) as LocationManager)
                .removeUpdates(locationListener)
            runCatching { unregisterReceiver(btStateReceiver) }
            handlerThread.quitSafely()
        }
        if (this::karooSystem.isInitialized) {
            karooSystem.dispatch(ReleaseBluetooth(BT_RESOURCE_ID))
            karooSystem.disconnect()
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        GapRepository.serviceStopped()
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_partnerride)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    // ------------------------------------------------------------------ settings

    private suspend fun collectSettings() {
        applicationContext.streamSettings().collect { new ->
            settings = new
            coupleTag = CoupleCode.tag(new.coupleCode)
            if (!new.enabled) {
                stopSelf()
            }
        }
    }

    // ------------------------------------------------------------------ GPS

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onOwnFix(location)

        override fun onProviderEnabled(provider: String) {}

        override fun onProviderDisabled(provider: String) {
            GapRepository.update { it.copy(statusMessage = getString(R.string.status_gps_off)) }
        }
    }

    @SuppressLint("MissingPermission") // checked in onCreate
    private fun startLocationUpdates() {
        try {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            // GPS provider: Location.getTime() is satellite-derived UTC, which is what the
            // packet timestamps require. Fused/network providers are unavailable anyway (no GMS).
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                LOCATION_INTERVAL_MS,
                0f,
                locationListener,
                handlerThread.looper,
            )
        } catch (e: Exception) {
            Log.w(TAG, "GPS updates unavailable", e)
            GapRepository.update { it.copy(statusMessage = getString(R.string.status_gps_off)) }
        }
    }

    private fun onOwnFix(location: Location) {
        // location.getTime() = GPS time; never System.currentTimeMillis() (device clocks drift).
        engine.onOwnFix(
            GpsFix(
                timeMs = location.time,
                latDeg = location.latitude,
                lonDeg = location.longitude,
                speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
                bearingDeg = if (location.hasBearing()) location.bearing.toDouble() else null,
            ),
        )
        GapRepository.update {
            it.copy(lastOwnFixElapsedMs = SystemClock.elapsedRealtime(), statusMessage = null)
        }
        updateAdvertisement(location)
    }

    // ------------------------------------------------------------------ advertising

    private val advertisingCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
            advertisingStartPending = false
            if (status == ADVERTISE_SUCCESS && set != null) {
                advertisingSet = set
                GapRepository.update { it.copy(advertising = true) }
            } else {
                Log.w(TAG, "Advertising failed to start: $status")
                GapRepository.update {
                    it.copy(advertising = false, statusMessage = getString(R.string.status_advertise_failed))
                }
            }
        }

        override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
            advertisingSet = null
            GapRepository.update { it.copy(advertising = false) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateAdvertisement(location: Location) {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return
        val payload = PacketCodec.encode(
            coupleTag = coupleTag,
            gpsTimeMs = location.time,
            latDeg = location.latitude,
            lonDeg = location.longitude,
            speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
            headingDeg = if (location.hasBearing()) location.bearing.toDouble() else null,
        )
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(PacketCodec.MANUFACTURER_ID, payload)
            .build()
        try {
            val set = advertisingSet
            if (set != null) {
                // In-place payload update: no advertising restart churn.
                set.setAdvertisingData(data)
            } else if (!advertisingStartPending) {
                advertisingStartPending = true
                // Legacy advertisement (fits ~24 usable payload bytes), ~250 ms interval, max TX
                // power. Own GPS fixes only change ~1x/s, so INTERVAL_LOW's ~100 ms (10 TX/s) was
                // pure redundancy; ~250 ms (4 TX/s) still gives a duty-cycled scanner several
                // chances to catch each fix while cutting advertising-side radio time ~4x.
                val params = AdvertisingSetParameters.Builder()
                    .setLegacyMode(true)
                    .setConnectable(false)
                    .setScannable(false)
                    .setInterval(AdvertisingSetParameters.INTERVAL_MEDIUM)
                    .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                    .build()
                adapter.bluetoothLeAdvertiser?.startAdvertisingSet(
                    params, data, null, null, null, advertisingCallback,
                )
            }
        } catch (e: Exception) {
            advertisingStartPending = false
            Log.w(TAG, "Advertising error", e)
            GapRepository.update {
                it.copy(advertising = false, statusMessage = getString(R.string.status_advertise_failed))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        try {
            bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(advertisingCallback)
        } catch (e: Exception) {
            Log.d(TAG, "stopAdvertisingSet: ${e.message}")
        }
        advertisingSet = null
        advertisingStartPending = false
        GapRepository.update { it.copy(advertising = false) }
    }

    // ------------------------------------------------------------------ scanning

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // Fires per-advertisement when batching is off/unsupported. Callbacks arrive on the
            // main thread; do all work on the link thread.
            handler.post { handleScanResult(result) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            // Fires instead of onScanResult when SCAN_REPORT_DELAY_MS batching is active: the
            // controller buffers matches on its own and wakes the AP once per delay window
            // instead of once per advertisement, cutting CPU/Binder wakeups without dropping any
            // packets (handleScanResult's replay guard still de-dupes retransmissions).
            handler.post { results.forEach(::handleScanResult) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "Scan failed: $errorCode")
            GapRepository.update {
                it.copy(scanning = false, statusMessage = getString(R.string.status_scan_failed))
            }
            // Mutate scan state and schedule the retry on the link thread. Several SCAN_FAILED_*
            // codes (registration failed, scanning too frequently) are transient and clear on
            // their own, so a failure must never be the end of the link.
            handler.post {
                scanning = false
                scheduleScanRetry()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScanIfAllowed() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled || scanning) return

        // Never trip Android's "5 scan starts per 30 seconds" block: if we're near the limit
        // (normal operation never is — restarts happen every ~20 min), defer the start.
        val now = SystemClock.elapsedRealtime()
        while (recentScanStarts.isNotEmpty() && now - recentScanStarts.first() > 30_000L) {
            recentScanStarts.removeFirst()
        }
        if (recentScanStarts.size >= 4) {
            // Named runnable: repeated deferrals replace the pending retry instead of stacking.
            postScanRetry(RATE_LIMIT_DEFER_MS)
            return
        }

        // Hardware filter on the manufacturer ID (empty data = any payload under that ID).
        // handleScanResult() additionally matches version/magic/tag programmatically, so if the
        // hardware filter ever proves unreliable, set USE_HARDWARE_FILTER = false as fallback.
        val filters = if (USE_HARDWARE_FILTER) {
            listOf(
                ScanFilter.Builder()
                    .setManufacturerData(PacketCodec.MANUFACTURER_ID, ByteArray(0))
                    .build(),
            )
        } else {
            emptyList()
        }
        // Duty-cycled scanning: the only mode. Trades a small, bounded chance of missing an
        // individual advertisement (retransmissions during the ~1s GPS-fix window cover for it)
        // for real receiver-radio battery savings; still lands updates roughly every 2-3 s.
        val mode = ScanSettings.SCAN_MODE_BALANCED
        // Batch scan results in the controller's own buffer and only wake the AP once per
        // SCAN_REPORT_DELAY_MS, instead of once per advertisement. Falls back to immediate
        // per-result delivery (onScanResult) on hardware without batching support.
        val reportDelayMs = if (adapter.isOffloadedScanBatchingSupported) SCAN_REPORT_DELAY_MS else 0L
        val scanSettings = ScanSettings.Builder()
            .setScanMode(mode)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(reportDelayMs)
            .build()
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            // Null between the isEnabled check above and here (adapter going down): retry rather
            // than returning into a silent, permanently non-scanning state.
            Log.w(TAG, "No BLE scanner available")
            scheduleScanRetry()
            return
        }
        try {
            scanner.startScan(filters, scanSettings, scanCallback)
            recentScanStarts.addLast(now)
            scanning = true
            // Scanning again: drop any pending retry and reset the backoff.
            handler.removeCallbacks(scanRetryRunnable)
            scanRetryDelayMs = SCAN_RETRY_BASE_MS
            GapRepository.update { it.copy(scanning = true) }
        } catch (e: Exception) {
            Log.w(TAG, "startScan error", e)
            GapRepository.update {
                it.copy(scanning = false, statusMessage = getString(R.string.status_scan_failed))
            }
            scheduleScanRetry()
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.d(TAG, "stopScan: ${e.message}")
        }
        scanning = false
        GapRepository.update { it.copy(scanning = false) }
    }

    private fun restartScan() {
        stopScan()
        startScanIfAllowed()
    }

    /**
     * Android demotes BLE scans running longer than 30 minutes to opportunistic mode. A stop +
     * immediate restart every ~20 minutes resets that timer. One start per 20 minutes stays far
     * away from the 5-starts-per-30-seconds block, and the back-to-back restart is seamless:
     * gap state lives in [GapRepository]/[GapEngine] and is untouched.
     */
    private suspend fun scanRestartLoop() {
        while (scope.isActive) {
            delay(SCAN_RESTART_INTERVAL_MS)
            // Doubles as a slow watchdog: if the link is not scanning at all (a failure the
            // backoff retries also gave up on), start it rather than skipping the cycle.
            handler.post { if (scanning) restartScan() else startScanIfAllowed() }
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val payload = result.scanRecord?.getManufacturerSpecificData(PacketCodec.MANUFACTURER_ID) ?: return
        val packet = PacketCodec.decode(payload, coupleTag)
        if (packet == null) {
            // Malformed/foreign packets are expected (shared test manufacturer ID): debug-only log.
            Log.d(TAG, "Discarded packet (${payload.size} bytes)")
            return
        }
        val now = SystemClock.elapsedRealtime()
        val gap = engine.onPartnerPacket(packet, now) ?: return
        val zone = zoneTracker.update(abs(gap.smoothedGapMeters))
        GapRepository.update {
            it.copy(
                lastPacketElapsedMs = now,
                smoothedGapMeters = gap.smoothedGapMeters,
                partnerAhead = gap.partnerAhead,
                zone = zone,
            )
        }
        maybeAlert(gap)
    }

    // ------------------------------------------------------------------ gap alert

    private fun maybeAlert(gap: GapResult) {
        val s = settings
        if (!s.alertEnabled) {
            alertArmed = true
            return
        }
        val absGap = abs(gap.smoothedGapMeters)
        if (alertArmed && absGap > s.alertThresholdMeters) {
            // Fire once per drop-off; re-arm only after the gap closes below the threshold.
            alertArmed = false
            fireGapAlert(absGap)
        } else if (!alertArmed && absGap < s.alertThresholdMeters) {
            alertArmed = true
        }
    }

    private fun fireGapAlert(absGapMeters: Double) {
        // Audio must use the native karoo-ext beep API: standard Android audio (ToneGenerator,
        // MediaPlayer) may not route to the Karoo's buzzer.
        karooSystem.dispatch(
            PlayBeepPattern(
                listOf(
                    PlayBeepPattern.Tone(900, 250),
                    PlayBeepPattern.Tone(null, 120),
                    PlayBeepPattern.Tone(900, 250),
                    PlayBeepPattern.Tone(null, 120),
                    PlayBeepPattern.Tone(1250, 450),
                ),
            ),
        )
        karooSystem.dispatch(TurnScreenOn)
        karooSystem.dispatch(
            InRideAlert(
                id = "partnerride-${SystemClock.elapsedRealtime()}",
                icon = R.drawable.ic_partnerride,
                title = getString(R.string.alert_title),
                detail = getString(R.string.alert_detail, roundGapForDisplay(absGapMeters)),
                autoDismissMs = 8_000L,
                backgroundColor = R.color.partnerride_red,
                textColor = R.color.partnerride_white,
            ),
        )
    }

    // ------------------------------------------------------------------ bluetooth state

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> {
                    GapRepository.update { it.copy(bluetoothReady = true, statusMessage = null) }
                    handler.post {
                        startScanIfAllowed()
                        // Advertising resumes with the next GPS fix (payload needs a position).
                    }
                }
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    advertisingSet = null
                    advertisingStartPending = false
                    scanning = false
                    GapRepository.update {
                        it.copy(
                            bluetoothReady = false,
                            advertising = false,
                            scanning = false,
                            statusMessage = getString(R.string.status_bluetooth_off),
                        )
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "PartnerRide"
        private const val CHANNEL_ID = "partnerride_link"
        private const val NOTIFICATION_ID = 1001
        private const val BT_RESOURCE_ID = "partnerride-link"
        private const val ACTION_STOP = "net.bbgen.karoo.partnerride.STOP"
        private const val LOCATION_INTERVAL_MS = 1_000L
        private const val SCAN_RESTART_INTERVAL_MS = 20L * 60L * 1_000L
        private const val SCAN_REPORT_DELAY_MS = 2_000L
        private const val USE_HARDWARE_FILTER = true

        /** First retry delay after a failed scan start; doubles up to [SCAN_RETRY_MAX_MS]. */
        private const val SCAN_RETRY_BASE_MS = 5_000L
        private const val SCAN_RETRY_MAX_MS = 60_000L

        /** Wait before re-attempting a start deferred by the 5-per-30 s rate limiter. */
        private const val RATE_LIMIT_DEFER_MS = 10_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PartnerLinkService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PartnerLinkService::class.java))
        }

        /** Runtime permissions the link needs on this device's API level. */
        fun requiredPermissions(): List<String> = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
        }

        fun missingPermissions(context: Context): List<String> = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }
}
