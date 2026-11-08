package nl.rogro82.pipup

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit


class PiPupService : Service(), WebServer.Handler {
    private val mHandler: Handler = Handler(Looper.getMainLooper())
    // Hand-off of HTTP requests (/notify, /cancel) to the main thread, kept apart from
    // mHandler so popup timers and queued requests never share a queue.
    private val mRequestHandler: Handler = Handler(Looper.getMainLooper())

    /// One popup on screen (0.24.0): its own overlay window, sized to its content.
    private class Shown(
        val key: String,          // popup id; "" = the slot shared by popups without an id
        val window: FrameLayout,
        var view: PopupView,
        var props: PopupProps,
        var shownAt: Long,
        var expire: Runnable? = null,
        /// Bumped on every reschedule and update-in-place (2026-10-08): an exit animation
        /// only removes the popup when nothing touched it since the animation started.
        var generation: Int = 0
    )
    /// Every popup on screen, in stack order (last = on top). Main thread only.
    private val mShown = LinkedHashMap<String, Shown>()
    /// Immutable copy of mShown for readers on other threads (/state on the NanoHTTPD
    /// worker): republished after every change, so a reader never sees a torn map.
    private data class ShownInfo(val props: PopupProps, val shownAt: Long)
    @Volatile private var mShownList: List<ShownInfo> = emptyList()
    /// Windows whose removal threw; the watchdog removes them again.
    private val mStaleWindows = mutableListOf<FrameLayout>()
    // 0.18.0: whether the system screensaver (DreamService / ambient mode) is showing. There is
    // no public getter, so it is tracked from the DREAMING_STARTED/STOPPED broadcasts; unknown
    // (false) until the first transition after this service started.
    @Volatile private var mDreaming = false
    /// Version the HA integration announced in its last request header (ha-pipup >= 1.18.0);
    /// null until one arrives. Shown in /state and on the status screen ("connected").
    @Volatile private var mHaPipupSeen: String? = null
    private val mDreamReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            mDreaming = intent?.action == Intent.ACTION_DREAMING_STARTED
            Log.d(LOG_TAG, "screensaver ${if (mDreaming) "started" else "stopped"}")
        }
    }
    /// Screen on/off pushes (0.24.0): a controller learns of standby without polling.
    private val mScreenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> { emit("screen_on"); checkPermissionsChanged() }
                Intent.ACTION_SCREEN_OFF -> emit("screen_off")
            }
        }
    }
    /// 0.29.0: volume changes (remote, POST /volume, another app) are pushed as one
    /// "volume_changed" event per burst - a held volume key fires this per step.
    /// VOLUME_CHANGED_ACTION is not public API, but every Android version since 4 sends it
    /// and a dynamically registered receiver gets it; without it a controller only sees a
    /// remote-control volume change at its next poll.
    private val mVolumeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val stream = intent?.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) ?: -1
            if (stream != -1 && stream != android.media.AudioManager.STREAM_MUSIC) return
            mHandler.removeCallbacks(mVolumePush)
            mHandler.postDelayed(mVolumePush, VOLUME_PUSH_DEBOUNCE_MS)
        }
    }
    private val mVolumePush = Runnable { emit("volume_changed") }
    /// Permissions as last pushed; a difference is pushed as a "permissions" event.
    @Volatile private var mLastPermissions: Map<String, Any?>? = null
    // Last *received* popup (survives dismiss/expiry) — shown on the status screen and
    // in /state so you can verify at the TV what HA actually sent.
    @Volatile private var mLastPopup: PopupProps? = null
    @Volatile private var mLastPopupAt: Long = 0L
    /// ms from view creation to first rendered frame of the last video/web popup; null until known
    @Volatile private var mLastFirstFrameMs: Long? = null
    /// last stream error of the newest popup (whep, 0.25.0): null while fine
    @Volatile private var mLastMediaError: String? = null
    private val mPopupsShown = java.util.concurrent.atomic.AtomicLong(0)
    private val mStartedAt: Long = SystemClock.elapsedRealtime()
    private lateinit var mWebServer: WebServer

    private var mNsdManager: NsdManager? = null
    private var mNsdListener: NsdManager.RegistrationListener? = null

    private val mWatchdogHandler = Handler(Looper.getMainLooper())
    private val mWatchdogCleanups = java.util.concurrent.atomic.AtomicLong(0)

    private var mTts: TextToSpeech? = null
    private var mTtsReady = false
    private var mTtsPending: Pair<String, String?>? = null
    private val mTtsDefaultLocale: Locale = Locale.getDefault()
    private val mTtsIdleHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()

        initNotificationChannel(
            "service_channel",
            getString(R.string.service_channel_name),
            getString(R.string.service_channel_name)
        )

        enterForeground()

        // Binding :7979 can fail when the previous process was killed and its socket is not
        // released yet (low-memory devices restart this service within seconds). An unguarded
        // start() threw straight out of onCreate, leaving a live process with a dead server -
        // which no external "is the process running?" check can tell apart from a healthy one.
        // 2026-10-08: no stopSelf() when all attempts fail. START_STICKY only restarts a
        // *killed* process, not a service that stopped itself, so that left the TV offline
        // until the next reboot. The service stays up and the watchdog retries the bind.
        mWebServer = WebServer(SERVER_PORT, this)
        if (!startWebServer()) {
            Log.e(LOG_TAG, "Port $SERVER_PORT not bound yet; the watchdog retries every ${WATCHDOG_INTERVAL_MS / 1000} s")
        }

        loadSettings()
        registerNsd()
        startWatchdog()
        startUpdateChecker()
        registerReceiver(mScreenReceiver, android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        registerReceiver(mDreamReceiver, android.content.IntentFilter().apply {
            addAction(Intent.ACTION_DREAMING_STARTED)
            addAction(Intent.ACTION_DREAMING_STOPPED)
        })
        registerReceiver(mVolumeReceiver, android.content.IntentFilter().apply {
            addAction("android.media.VOLUME_CHANGED_ACTION")
            addAction("android.media.STREAM_MUTE_CHANGED_ACTION")
        })

        // Android < 12: the installer wants an on-screen confirmation. See
        // launchInstallConfirmation() for why it is launched the way it is (0.26.1, #41).
        UpdateManager.onPendingUserAction = {
            UpdateManager.confirmTrigger = "auto"
            mHandler.post { launchInstallConfirmation() }
        }
        // A failed install used to leave the TV silent; the error already sits in
        // /state.update.error, now it is also said on screen (0.26.1).
        UpdateManager.onInstallFailed = { error ->
            mHandler.post { showInstallFailedPopup(error) }
        }

        maybeAnnounceInstalledUpdate()
        mLastPermissions = Permissions.asMap(this)
        // "started" covers reboot, power-restore and app restart: the controller knows
        // any popup it thought was up is gone and reads fresh state.
        emit("started")
        // TTS is initialised lazily: the engine is a separate ~100MB process and keeping it
        // bound for the entire service lifetime is the single biggest reason this app gets
        // picked by low-memory killers on 1GB TVs. Popups without `tts` never need it.
    }

    /// Post the ongoing notification and become a foreground service.
    ///
    /// MUST be called for *every* startForegroundService(), not just on creation:
    /// when the service is already running only onStartCommand runs, and Android
    /// kills the process with
    /// `RemoteServiceException: Context.startForegroundService() did not then call
    /// Service.startForeground()` if the call is missing. That is exactly what a
    /// keep-alive automation or the connectivity Receiver triggers — the "rescue"
    /// then crashed the app instead of keeping it alive (seen on a TCL TV that sat
    /// at 26% availability because of it). startForeground is idempotent, so
    /// calling it again on an already-foreground service is harmless.
    private fun enterForeground() {
        try {
            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE
            } else 0

            val pendingIntent = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java), pendingIntentFlags
            )

            val notification = NotificationCompat.Builder(this, "service_channel")
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.service_notification_text))
                .setContentIntent(pendingIntent)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setAutoCancel(false)
                .setOngoing(true)
                .build()

            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else 0

            ServiceCompat.startForeground(this, ONGOING_NOTIFICATION_ID, notification, serviceType)
        } catch (ex: Throwable) {
            // Never let this throw out of onCreate/onStartCommand: a failure here
            // would take the whole service down, which is worse than running
            // without the notification.
            Log.e(LOG_TAG, "startForeground failed: ${ex.message}")
        }
    }

    private fun startWebServer(attempts: Int = WEBSERVER_START_ATTEMPTS): Boolean {
        repeat(attempts) { attempt ->
            try {
                mWebServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                Log.d(LOG_TAG, "WebServer started on :$SERVER_PORT (attempt ${attempt + 1})")
                return true
            } catch (ex: Throwable) {
                Log.e(LOG_TAG, "WebServer start attempt ${attempt + 1} failed: ${ex.message}")
                try {
                    mWebServer.stop()
                } catch (_: Throwable) {
                }
                if (attempt < attempts - 1) {
                    try {
                        Thread.sleep(WEBSERVER_RETRY_DELAY_MS)
                    } catch (_: InterruptedException) {
                        return false
                    }
                }
            }
        }
        return false
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(mDreamReceiver) }
        runCatching { unregisterReceiver(mScreenReceiver) }
        runCatching { unregisterReceiver(mVolumeReceiver) }
        // 2026-10-08: overlay windows, popup timers and queued requests outlived the
        // service, and the update callback kept pointing at it. Each step guarded:
        // onDestroy must never throw.
        runCatching { removeAllPopups(reason = "service_stopped") }
        runCatching { mHandler.removeCallbacksAndMessages(null) }
        runCatching { mRequestHandler.removeCallbacksAndMessages(null) }
        runCatching { UpdateManager.onPendingUserAction = null }
        runCatching { UpdateManager.onInstallFailed = null }
        runCatching { SoundPlayer.stop(this) }
        super.onDestroy()

        mWatchdogHandler.removeCallbacksAndMessages(null)
        mTtsIdleHandler.removeCallbacksAndMessages(null)

        unregisterNsd()

        shutdownTts()

        try {
            mWebServer.stop()
        } catch (ex: Throwable) {
            // never let teardown throw: onCreate may have bailed out before the server bound
            Log.e(LOG_TAG, "WebServer stop failed: ${ex.message}")
        }
    }

    /// App preferences, kept in *device-protected* storage. The service can start
    /// during direct boot (LOCKED_BOOT_COMPLETED, before first unlock) on a TV whose
    /// mains power was restored, and credential-protected prefs are unreadable then.
    /// None of the keys here are sensitive (device id, version markers). Existing
    /// installs kept them in the default credential-protected file; move it once so
    /// the device id — and therefore the Home Assistant unique_id — stays stable.
    private fun prefs(): android.content.SharedPreferences {
        val ctx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            applicationContext.createDeviceProtectedStorageContext() else applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            !ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).contains(PREF_DEVICE_ID)) {
            // one-time migration from the old location; a no-op once moved, and safe
            // to skip while credential storage is still locked (the id is regenerated
            // only if neither file has it, which cannot happen for an existing install)
            runCatching { ctx.moveSharedPreferencesFrom(applicationContext, PREFS_NAME) }
        }
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /// stable device identifier: generated once, survives app updates (not reinstalls)
    private fun deviceId(): String {
        val prefs = prefs()
        return prefs.getString(PREF_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(PREF_DEVICE_ID, it).apply()
        }
    }

    private fun deviceName(): String = try {
        Settings.Global.getString(contentResolver, "device_name")
    } catch (_: Throwable) {
        null
    }?.takeIf { it.isNotBlank() } ?: Build.MODEL

    private fun registerNsd() {
        try {
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "PiPup ${deviceName()}".take(63)
                serviceType = NSD_SERVICE_TYPE
                port = SERVER_PORT
                setAttribute("id", deviceId())
                setAttribute("name", deviceName())
                setAttribute("version", BuildConfig.VERSION_NAME)
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    Log.d(LOG_TAG, "NSD registered as ${info.serviceName}")
                }
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.e(LOG_TAG, "NSD registration failed: $errorCode")
                }
                override fun onServiceUnregistered(info: NsdServiceInfo) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            }
            mNsdListener = listener
            mNsdManager = (getSystemService(Context.NSD_SERVICE) as NsdManager).also {
                it.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
            }
        } catch (ex: Throwable) {
            // discovery is best-effort: the HTTP API works fine without it
            Log.e(LOG_TAG, "NSD registration error: ${ex.message}")
        }
    }

    private fun unregisterNsd() {
        try {
            mNsdListener?.let { mNsdManager?.unregisterService(it) }
        } catch (_: Throwable) {
        }
        mNsdListener = null
        mNsdManager = null
    }

    /// POST the pressed button to the callback URL (HA webhook), off the main thread
    private fun sendButtonCallback(popup: PopupProps, button: PopupProps.Button) {
        val url = popup.callback
        if (url.isNullOrBlank()) {
            Log.w(LOG_TAG, "Button ${button.id} pressed but no callback configured")
            return
        }
        Thread {
            try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                val body = Json.writeValueAsString(mapOf(
                    "popup" to popup.id,
                    "button" to button.id,
                    "label" to button.label,
                    "device" to deviceId(),
                    "name" to deviceName()
                ))
                conn.outputStream.use { it.write(body.toByteArray()) }
                Log.d(LOG_TAG, "Button callback ${button.id} -> HTTP ${conn.responseCode}")
                conn.disconnect()
            } catch (ex: Throwable) {
                Log.e(LOG_TAG, "Button callback failed: ${ex.message}")
            }
        }.start()
    }

    /// idempotent: only ever creates one engine, and only when something wants to speak
    private fun initTts() {
        if (mTts != null) return
        try {
            mTts = TextToSpeech(this) { status ->
                mTtsReady = status == TextToSpeech.SUCCESS
                if (!mTtsReady) {
                    Log.e(LOG_TAG, "TTS init failed ($status)")
                }
                mTtsPending?.let { (text, language) ->
                    mTtsPending = null
                    if (mTtsReady) doSpeak(text, language)
                }
            }
        } catch (ex: Throwable) {
            Log.e(LOG_TAG, "TTS unavailable: ${ex.message}")
        }
    }

    private fun speak(text: String, language: String?) {
        mTtsIdleHandler.removeCallbacksAndMessages(null)
        if (mTtsReady) {
            doSpeak(text, language)
        } else {
            mTtsPending = text to language // engine still starting: keep the latest utterance
            initTts()
        }
        // release the engine process again once nobody has spoken for a while
        mTtsIdleHandler.postDelayed({ shutdownTts() }, TTS_IDLE_TIMEOUT_MS)
    }

    private fun shutdownTts() {
        val engine = mTts ?: return
        try {
            engine.stop()
            engine.shutdown()
            Log.d(LOG_TAG, "TTS engine released")
        } catch (ex: Throwable) {
            Log.e(LOG_TAG, "TTS shutdown failed: ${ex.message}")
        }
        mTts = null
        mTtsReady = false
        mTtsPending = null
    }

    private fun doSpeak(text: String, language: String?) {
        val tts = mTts ?: return
        try {
            val locale = language?.let { Locale.forLanguageTag(it) } ?: mTtsDefaultLocale
            tts.language = locale // best-effort: engine falls back when the language is missing
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pipup-tts")
        } catch (ex: Throwable) {
            Log.e(LOG_TAG, "TTS error: ${ex.message}")
        }
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Answer every startForegroundService() — see enterForeground(). onCreate
        // does not run for an already-running service, so doing this only there
        // made each extra start request crash the process.
        enterForeground()

        // A restart after a kill (START_STICKY, or the boot/connectivity Receiver)
        // arrives here with the web server gone; bring it back rather than sitting
        // there as a live process with a dead server.
        // isAlive() = started, socket open and the listener thread running — a
        // stricter check than wasStarted(), which stays true after a crash.
        if (!runCatching { mWebServer.isAlive }.getOrDefault(false)) {
            Log.w(LOG_TAG, "WebServer not running on start command; restarting it")
            if (!startWebServer()) {
                // no stopSelf() (2026-10-08): the watchdog keeps retrying the bind
                Log.e(LOG_TAG, "Port $SERVER_PORT not bound yet; the watchdog retries every ${WATCHDOG_INTERVAL_MS / 1000} s")
            }
        }
        return START_STICKY
    }

    private fun initNotificationChannel(id: String, name: String, description: String) {
        if (Build.VERSION.SDK_INT < 26) {
            return
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(id, name,
            NotificationManager.IMPORTANCE_DEFAULT
        )
        channel.description = description
        notificationManager.createNotificationChannel(channel)
    }

    /// Remove the popup in slot [key] ("" = the id-less slot). Pushes "popup_removed"
    /// with [reason]: expired, cancelled, button, back or watchdog. Returns false when
    /// no such popup is on screen.
    private fun removePopup(key: String, reason: String = "cancelled",
                            byButton: Boolean = false): Boolean {
        val removed = mShown.remove(key) ?: return false
        removed.expire?.let { mHandler.removeCallbacks(it) }
        removed.expire = null
        publishShown()
        emit("popup_removed", mapOf("reason" to reason, "removedId" to removed.props.id))

        // The install-confirmation popup (Android < 12) going away without its button
        // being pressed - it expired, or another popup replaced it - means the user
        // never confirmed. Release the pending state so /state stops reporting an
        // install in progress for 15 minutes and the update can be retried.
        if (!byButton && removed.props.id == CONFIRM_POPUP_ID && UpdateManager.pendingUserAction) {
            UpdateManager.abandonPending()
        }

        // every step guarded: a throwing WebView/VideoView teardown or WindowManager call
        // used to abort the removal halfway, leaving the popup visible on screen while the
        // state already said it was gone (the "popup stays on TV" reports)
        destroyView(removed.view)
        removeWindow(removed.window)
        // the popups stacked after this one close the gap
        if (removed.props.stackAxis != null) relayoutStacks()
        return true
    }

    /// `keepPendingInstall`: taking the confirm popup down must not count as "the user
    /// never confirmed" when it is the install confirmation itself that needs the screen.
    private fun removeAllPopups(reason: String = "cancelled", keepPendingInstall: Boolean = false) {
        mShown.keys.toList().forEach { removePopup(it, reason, byButton = keepPendingInstall) }
    }

    private fun destroyView(view: PopupView) {
        try {
            view.destroy()
        } catch (ex: Throwable) {
            Log.e(LOG_TAG, "Popup destroy failed: ${ex.message}")
        }
    }

    private fun removeWindow(window: FrameLayout) {
        try {
            window.removeAllViews()
        } catch (ex: Throwable) {
            Log.e(LOG_TAG, "Window removeAllViews failed: ${ex.message}")
        }
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeViewImmediate(window)
        } catch (ex: IllegalArgumentException) {
            // already detached
        } catch (ex: Throwable) {
            // the watchdog retries the removal
            Log.e(LOG_TAG, "Window removal failed (watchdog will retry): ${ex.message}")
            mStaleWindows.add(window)
        }
    }

    /// Republish the popup list for other threads (see mShownList).
    private fun publishShown() {
        mShownList = mShown.values.map { ShownInfo(it.props, it.shownAt) }
    }

    /// consistency watchdog: a window whose removal threw must not stay on screen.
    /// Runs on its own handler, apart from the popup timers on mHandler.
    private fun startWatchdog() {
        mWatchdogHandler.postDelayed(object : Runnable {
            override fun run() {
                try {
                    if (mStaleWindows.isNotEmpty()) {
                        Log.w(LOG_TAG, "Watchdog: ${mStaleWindows.size} stale window(s), removing again")
                        val stale = mStaleWindows.toList()
                        mStaleWindows.clear()
                        stale.forEach { removeWindow(it) }
                        mWatchdogCleanups.addAndGet((stale.size - mStaleWindows.size).toLong())
                    }
                } catch (ex: Throwable) {
                    Log.e(LOG_TAG, "Watchdog error: ${ex.message}")
                }
                // A server that never bound (port still held by a killed process) or
                // died later is started again here (2026-10-08). One attempt per tick:
                // this runs on the main thread, and the next tick is 30 s away anyway.
                runCatching {
                    if (!mWebServer.isAlive) {
                        Log.w(LOG_TAG, "Watchdog: WebServer not running, retrying bind on :$SERVER_PORT")
                        startWebServer(attempts = 1)
                    }
                }
                // local check only (no network): a permission lost by a reinstall or
                // revoked by the system is pushed as soon as it is seen
                runCatching { checkPermissionsChanged() }
                mWatchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }, WATCHDOG_INTERVAL_MS)
    }

    /// Periodic self-update check against the fork's GitHub releases. Runs on the
    /// watchdog handler and announces a
    /// new version once, on screen, with an Install button — sideloaded TVs have no
    /// store, and not every user has adb.
    private fun startUpdateChecker() {
        mWatchdogHandler.postDelayed(object : Runnable {
            override fun run() {
                // LAN-only TVs (no route to GitHub) switch this off via /settings (0.24.0).
                if (updateChecksEnabled()) Thread {
                    if (UpdateManager.check() && UpdateManager.updateAvailable) {
                        maybeAnnounceUpdate()
                    }
                }.start()
                mWatchdogHandler.postDelayed(this, UPDATE_CHECK_INTERVAL_MS)
            }
        }, UPDATE_CHECK_FIRST_DELAY_MS)
    }

    private fun updateChecksEnabled(): Boolean = prefs().getBoolean(PREF_UPDATE_CHECKS, true)

    /// 0.30.1 (#76): remember the X-HA-PiPup-Auto-Update header. `on` stores the time
    /// (rewritten at most hourly), `off` clears it; see [HaManagedUpdates].
    private fun noteHaAutoUpdate(value: String) {
        val on = HaManagedUpdates.parse(value) ?: return
        val prefs = prefs()
        val stored = prefs.getLong(PREF_HA_AUTO_UPDATE_AT, 0L).takeIf { it > 0 }
        if (on) {
            val now = System.currentTimeMillis()
            if (HaManagedUpdates.needsRefresh(stored, now)) {
                prefs.edit().putLong(PREF_HA_AUTO_UPDATE_AT, now).apply()
            }
        } else if (stored != null) {
            prefs.edit().remove(PREF_HA_AUTO_UPDATE_AT).apply()
        }
    }

    private fun haManagesUpdates(): Boolean = HaManagedUpdates.active(
        prefs().getLong(PREF_HA_AUTO_UPDATE_AT, 0L).takeIf { it > 0 },
        System.currentTimeMillis()
    )

    /// Push target and update source from prefs into the objects that use them.
    private fun loadSettings() {
        val p = prefs()
        Pusher.url = p.getString(PREF_WEBHOOK, null)
        UpdateManager.source = p.getString(PREF_UPDATE_SOURCE, null)
            ?.takeIf { UpdateManager.isValidSource(it) } ?: UpdateManager.DEFAULT_SOURCE
    }

    /// GET/POST /settings: persistent device settings, POSTed as query parameters,
    /// e.g. `POST /settings?updateChecks=false`; both methods answer the current values.
    /// `updateChecks` (0.24.0, true/false): the twice-daily release check.
    /// `webhook` (0.24.0): URL that receives every state change (see [Pusher]).
    /// `updateSource` (0.24.0): where releases come from (see [UpdateManager.source]).
    private fun settingsResponse(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (session.method == NanoHTTPD.Method.POST) {
            session.parameters["updateChecks"]?.firstOrNull()?.let { v ->
                val on = when (v.lowercase()) {
                    "true", "1", "on" -> true
                    "false", "0", "off" -> false
                    else -> return InvalidRequest("updateChecks must be true or false")
                }
                prefs().edit().putBoolean(PREF_UPDATE_CHECKS, on).apply()
            }
            // webhook (0.24.0): http(s) URL to push state changes to; empty = push off
            session.parameters["webhook"]?.firstOrNull()?.let { v ->
                if (v.isNotEmpty() && !(v.startsWith("http://") || v.startsWith("https://"))) {
                    return InvalidRequest("webhook must be an http(s) URL or empty")
                }
                prefs().edit().putString(PREF_WEBHOOK, v.ifEmpty { null }).apply()
            }
            // updateSource (0.24.0): github:<owner>/<repo>, or an http(s) folder URL
            // holding releases.json and the APKs; empty = back to the default
            session.parameters["updateSource"]?.firstOrNull()?.let { v ->
                if (v.isNotEmpty() && !UpdateManager.isValidSource(v)) {
                    return InvalidRequest("updateSource must be github:<owner>/<repo> or an http(s) URL")
                }
                prefs().edit().putString(PREF_UPDATE_SOURCE, v.ifEmpty { null }).apply()
            }
            loadSettings()
            // a controller that just set its webhook gets the current state at once
            if (session.parameters.containsKey("webhook")) emit("settings")
        }
        return newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            APPLICATION_JSON,
            Json.writeValueAsString(mapOf(
                "updateChecks" to updateChecksEnabled(),
                "updateSource" to UpdateManager.source,
                // the URL itself is the controller's secret: say whether one is set, not what
                "webhook" to !Pusher.url.isNullOrBlank()
            ))
        )
    }

    /// "Update to vX is being installed" - the visible feedback that pressing Install
    /// used to lack: on Android 12+ the whole install is silent and over in seconds,
    /// so nothing on the TV acknowledged the request at all.
    private fun showInstallingPopup() {
        val version = UpdateManager.latestVersion ?: return
        createPopup(
            PopupProps(
                duration = 30,
                id = UPDATE_POPUP_ID,
                position = PopupProps.Position.BottomRight,
                title = getString(R.string.update_installing_title, version),
                message = getString(R.string.update_installing_message),
                showProgress = true
            )
        )
    }

    /// Launches the system's install confirmation (Android < 12) the way the installer
    /// expects it (0.26.1, #41). Two things are different from the old blind launch:
    ///
    /// 1. Every PiPup popup is taken off the screen first. MIUI's installer refuses an
    ///    install while a floating window is up (its INSTALL_CANCELED_BY_USER on a Xiaomi
    ///    projector, Android 6), and stock Android's installer ignores taps under an overlay
    ///    (filterTouchesWhenObscured) - the likely reason the dialog "flashed and vanished"
    ///    on a TCL (Android 11) as well.
    /// 2. The dialog is started from MainActivity with startActivityForResult, so it has a
    ///    visible foreground caller, and the Activity result lands in
    ///    /state.update.confirmLaunch next to the installer's own verdict. The direct start
    ///    from this service remains as fallback when the Activity cannot be started; if even
    ///    that fails the old confirm popup with a button is offered.
    private fun launchInstallConfirmation() {
        val trigger = UpdateManager.confirmTrigger ?: "unknown"
        val confirm = UpdateManager.pendingConfirm ?: run {
            Log.w(LOG_TAG, "Install confirmation ($trigger): nothing pending")
            return
        }
        PowerController.wake(this)
        removeAllPopups(reason = "install_confirm", keepPendingInstall = true)
        val viaActivity = runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(ACTION_CONFIRM_INSTALL)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
            )
        }.onFailure {
            Log.e(LOG_TAG, "Cannot start MainActivity for the install confirmation", it)
        }.isSuccess
        if (viaActivity) {
            Log.i(LOG_TAG, "Install confirmation ($trigger): handed to MainActivity")
            return
        }
        runCatching { startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onSuccess { UpdateManager.noteConfirmLaunched("service") }
            .onFailure {
                Log.e(LOG_TAG, "Cannot show install prompt", it)
                UpdateManager.noteConfirmLaunchFailed("service", it.message)
                showConfirmInstallPopup()
            }
    }

    /// "PiPup update failed: <reason>" for 20 s, so a refused install is seen on the TV
    /// and not only in /state (0.26.1).
    private fun showInstallFailedPopup(error: String) {
        createPopup(
            PopupProps(
                duration = 20,
                id = UPDATE_POPUP_ID,
                position = PopupProps.Position.BottomRight,
                title = getString(R.string.update_failed_title),
                message = error
            )
        )
    }

    /// Popup with a button that (re)launches the system's install confirmation
    /// (Android < 12). Since 0.26.1 only the fallback when the dialog could not be
    /// started at all; the button goes through launchInstallConfirmation().
    private fun showConfirmInstallPopup() {
        val version = UpdateManager.latestVersion ?: return
        // Wake the screen: the confirmation is worthless on a sleeping TV (the system
        // dialog and this popup would sit on a black screen), and a remote-initiated
        // update from Home Assistant otherwise stalls invisibly. Field-reported.
        PowerController.wake(this)
        createPopup(
            PopupProps(
                duration = 120,
                id = CONFIRM_POPUP_ID,
                position = PopupProps.Position.BottomRight,
                title = getString(R.string.update_confirm_title, version),
                message = getString(R.string.update_confirm_message),
                buttons = listOf(
                    PopupProps.Button("confirm_install", getString(R.string.update_install))
                )
            )
        )
    }

    /// One-time "updated to vX" popup after the app replaced itself, so an update that
    /// happened silently (Android 12+) is still visibly acknowledged. Only when the
    /// screen is on; the version marker is bumped regardless, so it never shows stale.
    private fun maybeAnnounceInstalledUpdate() {
        val prefs = prefs()
        val previous = prefs.getString(PREF_LAST_RUN_VERSION, null)
        prefs.edit().putString(PREF_LAST_RUN_VERSION, BuildConfig.VERSION_NAME).apply()
        if (previous == null || previous == BuildConfig.VERSION_NAME) return

        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!power.isInteractive) return
        // small delay: right after MY_PACKAGE_REPLACED the window manager is not always
        // ready for our overlay yet
        mHandler.postDelayed({
            if (mShown.isEmpty()) {
                createPopup(
                    PopupProps(
                        duration = 10,
                        id = UPDATE_POPUP_ID,
                        position = PopupProps.Position.BottomRight,
                        title = getString(R.string.update_done_title, BuildConfig.VERSION_NAME)
                    )
                )
            }
        }, 5_000)
    }

    /// Show the "update available" popup at most once per version, and never over a
    /// popup that is already on screen or while the screen is off.
    private fun maybeAnnounceUpdate() {
        val version = UpdateManager.latestVersion ?: return
        val prefs = prefs()
        if (prefs.getString(PREF_UPDATE_ANNOUNCED, null) == version) return
        // 0.30.1 (#76): Home Assistant installs it by itself at night. The popup's Install
        // button would only take the remote's focus from whatever is being watched. Not
        // marked as announced, so the popup still comes if Home Assistant stops doing it.
        if (haManagesUpdates()) {
            Log.d(LOG_TAG, "Update $version not announced: Home Assistant installs it")
            return
        }

        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!power.isInteractive || mShownList.isNotEmpty()) return

        mHandler.post {
            val shown = createPopup(
                PopupProps(
                    duration = 60,
                    id = UPDATE_POPUP_ID,
                    position = PopupProps.Position.BottomRight,
                    title = getString(R.string.update_available_title, version),
                    message = getString(R.string.update_available_message),
                    buttons = listOf(PopupProps.Button("install", getString(R.string.update_install)))
                )
            )
            // Marked as announced only once it is actually on screen (2026-10-08): a popup
            // that failed to build used to count as shown, so the version was never offered.
            if (shown) prefs.edit().putString(PREF_UPDATE_ANNOUNCED, version).apply()
        }
    }

    private fun scheduleRemoval(entry: Shown) {
        entry.expire?.let { mHandler.removeCallbacks(it) }
        entry.expire = null
        // duration <= 0 means: show until /cancel or until replaced
        entry.generation++
        if (entry.props.indefinite) return
        val view = entry.view
        val generation = entry.generation
        val expire = Runnable {
            // Natural expiry is the one removal nobody is waiting on, so it may
            // animate (0.19.0); every other path (replace, /cancel, buttons) tears
            // down at once. The identity checks make the animation's end a no-op
            // when the popup was cancelled or redrawn in the meantime; the generation
            // check does the same for a same-content re-send during the 180 ms exit.
            if (mShown[entry.key] === entry && entry.view === view) {
                view.animateOut {
                    if (mShown[entry.key] === entry && entry.view === view &&
                        entry.generation == generation) {
                        removePopup(entry.key, reason = "expired")
                    }
                }
            }
        }
        entry.expire = expire
        mHandler.postDelayed(expire, entry.props.duration * 1000L) // 1000L: an Int*Int product
        // overflows past ~24.8 days and a negative delay removes the popup instantly
    }

    /// [PopupProps] for logcat and the /notify echo: the callback URL (a single-use
    /// token) replaced by "<set>"; everything else as received (2026-10-08).
    private fun PopupProps.redacted(): PopupProps = copy(callback = callback?.let { "<set>" })

    /// Slot of a popup: its id, or "" for every popup without one (they replace each other).
    private fun keyOf(id: String?): String = id?.takeIf { it.isNotEmpty() } ?: ""

    /// Window of one popup (0.24.0): sized to its content and placed by gravity, so
    /// several popups sit side by side. Only a popup with buttons takes input.
    @Suppress("DEPRECATION")
    private fun windowParams(popup: PopupProps, key: String): WindowManager.LayoutParams {
        val layoutFlags: Int = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            // Android 6/7 (< O): TYPE_SYSTEM_ALERT draws over other apps with the
            // SYSTEM_ALERT_WINDOW permission (which PiPup is granted) and can take
            // input focus, so buttons still work. TYPE_TOAST would show but never
            // focus, and was restricted from 7.1 on.
            else -> WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val windowFlags = if (popup.buttons.isNotEmpty())
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        else {
            // A button-less popup must never take input. On Android < 8 the overlay is a
            // TYPE_SYSTEM_ALERT window, which was reported to swallow the remote's D-pad/Back/Home
            // on Android 6 until the popup expired; add FLAG_NOT_TOUCHABLE there so the window is
            // fully input-transparent and keys reach the launcher behind it. On 8+ the
            // TYPE_APPLICATION_OVERLAY + FLAG_NOT_FOCUSABLE already passes input through, so leave
            // that path unchanged.
            var f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
                f = f or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            f
        }

        if (popup.coversScreen) return fullscreenParams(layoutFlags, windowFlags, key)

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlags,
            windowFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = when (popup.position) {
                PopupProps.Position.TopRight -> Gravity.TOP or Gravity.END
                PopupProps.Position.TopLeft -> Gravity.TOP or Gravity.START
                PopupProps.Position.BottomRight -> Gravity.BOTTOM or Gravity.END
                PopupProps.Position.BottomLeft -> Gravity.BOTTOM or Gravity.START
                PopupProps.Position.Center -> Gravity.CENTER
                PopupProps.Position.TopCenter -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                PopupProps.Position.BottomCenter -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                PopupProps.Position.CenterLeft -> Gravity.CENTER_VERTICAL or Gravity.START
                PopupProps.Position.CenterRight -> Gravity.CENTER_VERTICAL or Gravity.END
            }
            // the classic 20 px margin from the screen edge (was the full-screen overlay's padding);
            // with a centred axis x/y are an offset from the centre, so that axis stays 0
            val margin = if (popup.position == PopupProps.Position.Center) 0 else SCREEN_MARGIN_PX
            val centredX = popup.position == PopupProps.Position.TopCenter ||
                popup.position == PopupProps.Position.BottomCenter
            val centredY = popup.position == PopupProps.Position.CenterLeft ||
                popup.position == PopupProps.Position.CenterRight
            x = if (centredX) 0 else margin
            y = if (centredY) 0 else margin
            title = "pipup-${key.ifEmpty { "popup" }}"
        }
    }

    /// Whether [position] is centred on the stack's own axis (offset measured from the centre).
    private fun centredOn(position: PopupProps.Position, axis: PopupProps.StackAxis): Boolean =
        when (axis) {
            PopupProps.StackAxis.Vertical -> position == PopupProps.Position.CenterLeft ||
                position == PopupProps.Position.CenterRight
            PopupProps.StackAxis.Horizontal -> position == PopupProps.Position.TopCenter ||
                position == PopupProps.Position.BottomCenter
        }

    private fun Shown.sizeAlong(axis: PopupProps.StackAxis): Int =
        if (axis == PopupProps.StackAxis.Vertical) window.height else window.width

    /// Stacks (0.27.0, #64) of the popups on screen: per position and axis, in stack order.
    private fun stackGroups(): Map<Pair<PopupProps.Position, PopupProps.StackAxis>, List<Shown>> =
        mShown.values.filter { it.props.stackAxis != null }
            .groupBy { it.props.position to it.props.stackAxis!! }

    /// Puts the offset for [popup] in slot [key] into [params] before its window is added or
    /// redrawn, so a new popup opens in its place in the stack instead of on top of the
    /// first one. A size that is not known yet counts as 0; the layout listener corrects it.
    private fun applyStackOffset(params: WindowManager.LayoutParams, popup: PopupProps, key: String) {
        val axis = popup.stackAxis ?: return
        fun sameStack(e: Shown) = e.key != key && e.props.position == popup.position && e.props.stackAxis == axis
        val others = mShown.values.filter(::sameStack)
        // a redraw in place keeps its place in the stack; a new window (or bringToFront) goes last
        val inPlace = mShown[key]?.takeIf { !popup.bringToFront }
        val index = if (inPlace != null) mShown.values.takeWhile { it.key != key }.count(::sameStack)
                    else others.size
        val sizes = others.map { it.sizeAlong(axis) }.toMutableList()
        sizes.add(index, inPlace?.sizeAlong(axis) ?: 0)
        val offset = StackLayout.offsets(sizes, centredOn(popup.position, axis), SCREEN_MARGIN_PX, STACK_GAP_PX)[index]
        if (axis == PopupProps.StackAxis.Vertical) params.y = offset else params.x = offset
    }

    /// Moves every stacking popup to its place: after a popup is added, redrawn or removed,
    /// and whenever one changes size (a video popup grows when its first frame arrives).
    private fun relayoutStacks() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        for ((group, entries) in stackGroups()) {
            val (position, axis) = group
            val offsets = StackLayout.offsets(entries.map { it.sizeAlong(axis) },
                centredOn(position, axis), SCREEN_MARGIN_PX, STACK_GAP_PX)
            entries.forEachIndexed { i, entry ->
                val lp = entry.window.layoutParams as? WindowManager.LayoutParams ?: return@forEachIndexed
                val vertical = axis == PopupProps.StackAxis.Vertical
                if ((if (vertical) lp.y else lp.x) == offsets[i]) return@forEachIndexed
                if (vertical) lp.y = offsets[i] else lp.x = offsets[i]
                try {
                    wm.updateViewLayout(entry.window, lp)
                } catch (ex: Throwable) {
                    Log.e(LOG_TAG, "Stack relayout of ${entry.key.ifEmpty { "(no id)" }} failed: ${ex.message}")
                }
            }
        }
    }

    /// Build the view of [popup] and wire its callbacks to slot [key].
    private fun buildView(popup: PopupProps, key: String): PopupView {
        val view = PopupView.build(this, popup)
        view.onFirstFrame = { ms -> mLastFirstFrameMs = ms }
        view.onMediaError = { msg -> mLastMediaError = msg }
        view.onButton = { btn ->
            // Use the props shown now, not this closure's `popup`: an update-in-place
            // reuses the view but can carry a new callback URL.
            val shown = mShown[key]?.props ?: popup
            // The app's own update popups are handled locally; they have no
            // callback URL and must not be mistaken for user buttons.
            if (shown.id == UPDATE_POPUP_ID) {
                // No removal here (2026-10-08): showInstallingPopup() uses the same id and
                // redraws this popup in place. The generic removal posted after it took the
                // "Installing..." popup down in the same frame (regression from 0.24.0).
                mHandler.post { showInstallingPopup() }
                Thread {
                    UpdateManager.installLatest(this)?.let { err ->
                        // already in /state as update.lastError
                        Log.w(LOG_TAG, "Update from the popup button not started: $err")
                    }
                }.start()
            } else if (shown.id == CONFIRM_POPUP_ID) {
                UpdateManager.confirmTrigger = "button"
                mHandler.post { launchInstallConfirmation() }
            } else {
                sendButtonCallback(shown, btn)
            }
            // byButton: a confirm-popup dismissed by its own button must keep the
            // pending install alive (the system dialog was just launched).
            if (shown.id != UPDATE_POPUP_ID) {
                mHandler.post { removePopup(key, reason = "button", byButton = true) }
            }
        }
        return view
    }

    /// 0.29.1 (#73): a window over the whole display. An overlay is otherwise laid out inside
    /// the system-bar insets, so on a device with a status or navigation bar (an intercom panel,
    /// a tablet) a "full-screen" popup stopped short of the edges and the app underneath showed
    /// as strips. MATCH_PARENT plus LAYOUT_IN_SCREEN/NO_LIMITS lets it cover the bars; Android
    /// 11+ also needs the window to stop fitting the insets, and 9+ to extend into a cutout.
    /// TV screens have no bars, so there it looks exactly like a centred popup of screen size.
    private fun fullscreenParams(type: Int, flags: Int, key: String): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setFitInsetsTypes(0)
            }
            title = "pipup-${key.ifEmpty { "popup" }}"
        }

    /// The popup inside its window: wrapped, and centred when the window covers the screen.
    private fun contentParams(popup: PopupProps) = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { if (popup.coversScreen) gravity = Gravity.CENTER }

    /// Returns true when the popup is on screen (or updated in place) and /state reflects
    /// it; false when building it threw. /notify waits for this result before answering.
    ///
    /// 0.24.0: each id is its own window, so a popup with a new id opens beside the
    /// others; popups without an id share one slot. Same id and content: only the timer
    /// restarts. Same id, new content: the view is swapped inside its window, which keeps
    /// its place in the stack, unless `bringToFront` asks for a new window on top.
    private fun createPopup(popup: PopupProps): Boolean {
        // bringToFront drops the old window before the new one is added; if adding then
        // throws, the catch has to report that popup as gone (2026-10-08)
        var dropped: Shown? = null
        try {

            Log.d(LOG_TAG, "Create popup: ${popup.redacted()}")

            mLastPopup = popup
            mLastPopupAt = SystemClock.elapsedRealtime()
            mLastFirstFrameMs = null
            mLastMediaError = null

            val key = keyOf(popup.id)
            val current = mShown[key]

            // update-in-place: same id and same content -> keep the view (and its
            // video/web stream) alive and only reschedule the removal timer
            if (current != null && key.isNotEmpty() && current.props.sameContent(popup)) {

                Log.d(LOG_TAG, "Popup ${popup.id} unchanged: rescheduling removal only")

                // still speak when the tts text changed (content comparison ignores tts)
                if (!popup.tts.isNullOrBlank() && popup.tts != current.props.tts) {
                    speak(popup.tts, popup.ttsLanguage)
                }
                current.props = popup
                // A re-send can land inside the exit animation of natural expiry: stop it
                // and put the view back at rest, and bump the generation so its end action
                // (should it still fire) no longer removes the popup we just answered 200 for.
                current.generation++
                current.view.cancelExit()
                publishShown()
                scheduleRemoval(current)
                return true
            }

            // 0.18.0: an active screensaver may sit above app overlays (or hide them, Android
            // 12+); end it first so the popup is actually seen. Same wake path as POST /power.
            if (popup.dismissScreensaver && mDreaming) {
                Log.d(LOG_TAG, "screensaver active: waking before showing ${popup.id}")
                PowerController.wake(this)
            }

            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val params = windowParams(popup, key)
            applyStackOffset(params, popup, key)
            val view = buildView(popup, key)
            val now = SystemClock.elapsedRealtime()

            val entry: Shown
            if (current != null && !popup.bringToFront) {
                // redraw in place: new view in the same window, same place in the stack
                current.expire?.let { mHandler.removeCallbacks(it) }
                current.expire = null
                destroyView(current.view)
                current.window.removeAllViews()
                current.window.addView(view, contentParams(popup))
                try {
                    wm.updateViewLayout(current.window, params)
                } catch (ex: Throwable) {
                    Log.e(LOG_TAG, "updateViewLayout failed: ${ex.message}")
                }
                current.view = view
                current.props = popup
                current.shownAt = now
                entry = current
            } else {
                if (current != null) {
                    // bringToFront: drop the old window without a "removed" push; the
                    // "replaced" push below says what happened
                    mShown.remove(key)
                    dropped = current
                    current.expire?.let { mHandler.removeCallbacks(it) }
                    destroyView(current.view)
                    removeWindow(current.window)
                }
                val window = object : FrameLayout(this@PiPupService) {
                    // BACK dismisses a focusable (button) popup without firing a callback
                    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                            mHandler.post { removePopup(key, reason = "back") }
                            return true
                        }
                        return super.dispatchKeyEvent(event)
                    }
                }
                window.addView(view, contentParams(popup))
                // a stacked popup that changes size moves the ones after it
                window.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
                    if (r - l != oldR - oldL || b - t != oldB - oldT) mHandler.post { relayoutStacks() }
                }
                wm.addView(window, params)
                entry = Shown(key, window, view, popup, now)
                mShown[key] = entry
            }
            publishShown()
            relayoutStacks()
            mPopupsShown.incrementAndGet()

            if (popup.buttons.isNotEmpty()) {
                // focus the first button so a single OK press activates it
                view.requestFocus()
            }

            view.animateIn()

            if (!popup.tts.isNullOrBlank()) {
                speak(popup.tts, popup.ttsLanguage)
            }
            if (!popup.sound.isNullOrBlank()) {
                SoundPlayer.play(this, popup.sound, popup.soundVolume)
            }

            scheduleRemoval(entry)
            if (current != null) emit("popup_replaced", mapOf("shownId" to popup.id, "replacedId" to current.props.id))
            else emit("popup_shown", mapOf("shownId" to popup.id))
            Log.d(LOG_TAG, "Popup ${popup.id ?: "(no id)"} up, ${mShown.size} on screen")
            return true

        } catch (ex: Throwable) {
            Log.e(LOG_TAG, "Create popup failed: ${ex.message}", ex)
            dropped?.let { old ->
                // the old window is already gone; without this /state kept listing it
                if (mShown[keyOf(old.props.id)] == null) {
                    runCatching {
                        publishShown()
                        emit("popup_removed", mapOf("reason" to "replace_failed", "removedId" to old.props.id))
                    }
                }
            }
            return false
        }
    }

    /// Runs [block] on the main thread and waits for it to finish (at most
    /// MAIN_SYNC_TIMEOUT_MS). Returns the block's result, or null when the main thread did
    /// not get to it in time — the request stays queued and still runs, the caller just
    /// cannot confirm it. Used so that /notify and /cancel answer only once /state
    /// reflects the change: the HA integration refreshes its popup sensor right after the
    /// call, and measured on a Nokia 8010 the popup showed up in /state 20–75 ms *after*
    /// the old fire-and-forget reply — a refresh in that window saw the previous state.
    private fun runOnMainSync(block: () -> Boolean): Boolean? {
        val latch = CountDownLatch(1)
        var result: Boolean? = null
        mRequestHandler.post {
            try {
                result = block()
            } catch (ex: Throwable) {
                Log.e(LOG_TAG, "Main-thread request failed: ${ex.message}", ex)
                result = false
            } finally {
                latch.countDown()
            }
        }
        return if (latch.await(MAIN_SYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS)) result else null
    }

    private fun stateResponse(): NanoHTTPD.Response = newFixedLengthResponse(
        NanoHTTPD.Response.Status.OK,
        APPLICATION_JSON,
        Json.writeValueAsString(buildState())
    )

    /// Push one event (0.24.0): the /state JSON plus `event` and [extra]. Built here,
    /// on the caller's thread, so the body is the state at the moment of the event.
    private fun emit(event: String, extra: Map<String, Any?> = emptyMap()) {
        if (Pusher.url.isNullOrBlank()) return
        try {
            val body = buildState().apply {
                put("event", event)
                putAll(extra)
            }
            Pusher.push(event, Json.writeValueAsString(body))
        } catch (ex: Throwable) {
            Log.e(LOG_TAG, "push $event: building state failed: ${ex.message}")
        }
    }

    private fun checkPermissionsChanged() {
        val now = Permissions.asMap(this)
        if (now != mLastPermissions) {
            mLastPermissions = now
            emit("permissions")
        }
    }

    private fun buildState(): MutableMap<String, Any?> {
        val shown = mShownList
        val top = shown.lastOrNull()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val state = mutableMapOf<String, Any?>(
            "app" to "PiPup",
            "version" to BuildConfig.VERSION_NAME,
            "id" to deviceId(),
            "name" to deviceName(),
            "visible" to shown.isNotEmpty(),
            "screenOn" to powerManager.isInteractive,
            "dreaming" to mDreaming,
            "popupsShown" to mPopupsShown.get(),
            "watchdogCleanups" to mWatchdogCleanups.get(),
            "uptime" to (SystemClock.elapsedRealtime() - mStartedAt) / 1000,
            "device" to mapOf(
                "model" to Build.MODEL,
                "manufacturer" to Build.MANUFACTURER,
                "android" to Build.VERSION.RELEASE
            )
        )
        val now = SystemClock.elapsedRealtime()
        // the popup on top of the stack: what a caller from before 0.24.0 knows as "the" popup
        if (top != null) {
            state["popup"] = mapOf(
                "id" to top.props.id,
                "duration" to top.props.duration,
                "indefinite" to top.props.indefinite,
                "elapsed" to ((now - top.shownAt) / 1000)
            )
        }
        // 0.24.0: every popup on screen, in stack order (last = on top)
        state["popups"] = shown.map { info ->
            mapOf(
                "id" to info.props.id,
                "position" to info.props.position.name,
                "duration" to info.props.duration,
                "indefinite" to info.props.indefinite,
                "elapsed" to ((now - info.shownAt) / 1000),
                "media" to (mediaInfo(info.props)?.get("type"))
            )
        }
        state["power"] = mapOf(
            "canWake" to true,
            "canSleep" to PowerController.canSleep(this),
            "sleepMethod" to PowerController.sleepMethod(this)
        )
        // 0.29.0: TV control - what POST /launch, /key and /volume can do on this device
        state["control"] = mapOf(
            "launch" to true,
            "keys" to TvControl.availableKeys(),
            "lastLaunched" to TvControl.lastLaunched,
            "lastLaunchedSecondsAgo" to TvControl.lastLaunchedSecondsAgo()
        )
        state["volume"] = TvControl.volumeState(this)
        state["permissions"] = Permissions.asMap(this)
        state["update"] = mapOf(
            "checksEnabled" to updateChecksEnabled(),
            "source" to UpdateManager.source,
            "available" to UpdateManager.updateAvailable,
            "latest" to UpdateManager.latestVersion,
            "installing" to UpdateManager.isInstalling,
            // 0.23.0: downloading / installing / awaiting_confirmation, null when idle
            "phase" to UpdateManager.phase,
            // 0.23.0: 0-100 while downloading, null otherwise (or without Content-Length)
            "progress" to UpdateManager.downloadProgress,
            "downloadedBytes" to UpdateManager.downloadedBytes,
            "totalBytes" to UpdateManager.downloadTotalBytes.takeIf { it > 0 },
            // waiting for the on-screen confirmation Android < 12 always demands
            "pendingUserAction" to UpdateManager.pendingUserAction,
            // false on Android < 12: an install cannot complete without a remote press
            "silent" to UpdateManager.silentInstall,
            "checkedSecondsAgo" to UpdateManager.lastCheckedAt.takeIf { it > 0 }
                ?.let { (System.currentTimeMillis() - it) / 1000 },
            "error" to UpdateManager.lastError,
            // which trust store updater connections use; "unbuilt" until the first check
            "tlsFactory" to UpdateManager.tlsFactoryKind,
            // 0.26.1 (#41): how the last install confirmation was launched and how it ended
            // (trigger, path, activityResult, result, resultMs); null until one was launched
            "confirmLaunch" to UpdateManager.confirmLaunchState(),
            // 0.30.1 (#76): Home Assistant installs the updates, so no Install popup
            "haManaged" to haManagesUpdates()
        )
        state["haPipup"] = mapOf(
            // recommended = the latest ha-pipup release on GitHub, fetched with the
            // hourly update check — it is never maintained by hand
            "recommended" to UpdateManager.haPipupLatest,
            // oldest integration that can drive this app's full API (build-time constant)
            "minimum" to UpdateManager.MIN_HA_PIPUP,
            // what is actually talking to us, from the request header; null = not seen
            "connected" to mHaPipupSeen
        )
        val last = mLastPopup
        if (last != null) {
            state["lastPopup"] = mapOf(
                "id" to last.id,
                "position" to last.position.name,
                "duration" to last.duration,
                "indefinite" to last.indefinite,
                "muted" to mediaMuted(last),
                "media" to mediaInfo(last),
                "tts" to !last.tts.isNullOrBlank(),
                "sound" to !last.sound.isNullOrBlank(),
                "animation" to last.animation,
                "stack" to last.stackAxis?.name?.lowercase(),
                "fullscreen" to last.coversScreen,
                "buttons" to last.buttons.size,
                // time to first rendered frame (video/web); null while not yet painted or n/a
                "firstFrameMs" to mLastFirstFrameMs,
                // whep (0.25.0): why the stream did not (or no longer) play; null when fine
                "mediaError" to mLastMediaError,
                "secondsAgo" to ((SystemClock.elapsedRealtime() - mLastPopupAt) / 1000)
            )
        }
        state["push"] = mapOf(
            // a controller that sees this can stop polling and set a webhook instead
            "supported" to true,
            "webhook" to !Pusher.url.isNullOrBlank(),
            "lastEvent" to Pusher.lastEvent,
            "lastStatus" to Pusher.lastStatus,
            "lastError" to Pusher.lastError,
            "lastSecondsAgo" to Pusher.lastAt.takeIf { it > 0 }
                ?.let { (System.currentTimeMillis() - it) / 1000 }
        )
        return state
    }

    /// POST /power?state=on|off|toggle - screen on/off for this TV.
    ///
    /// Runs straight on the web-server thread: waking (startActivity/wake lock) and
    /// sleeping (lockNow/global action) are binder calls that touch no views, so the
    /// caller gets the real result instead of a fire-and-forget "accepted".
    ///
    /// 501 (not 400) when the device has no way to turn the screen off - that tells a
    /// controller "this device cannot", as opposed to "your request was malformed".
    private fun powerResponse(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val requested = session.parameters["state"]?.firstOrNull()?.lowercase()
        val target = when (requested) {
            "on", "wake", "true", "1" -> true
            "off", "sleep", "false", "0" -> false
            "toggle" -> !PowerController.screenOn(this)
            else -> return InvalidRequest("state must be on, off or toggle")
        }

        val method = if (target) "activity" else PowerController.sleepMethod(this)
        val ok = if (target) PowerController.wake(this) else PowerController.sleep(this)

        Log.d(LOG_TAG, "Power ${if (target) "on" else "off"} via ${method ?: "-"}: ok=$ok")

        val body = Json.writeValueAsString(mapOf(
            "state" to if (target) "on" else "off",
            "ok" to ok,
            "method" to method,
            // the wake activity is still starting up, so this can lag one poll behind
            "screenOn" to PowerController.screenOn(this)
        ))
        return newFixedLengthResponse(
            if (ok) NanoHTTPD.Response.Status.OK else NanoHTTPD.Response.Status.NOT_IMPLEMENTED,
            APPLICATION_JSON,
            body
        )
    }

    private fun param(session: NanoHTTPD.IHTTPSession, name: String): String? =
        session.parameters[name]?.firstOrNull()?.takeIf { it.isNotBlank() }

    private fun jsonResponse(status: NanoHTTPD.Response.Status, body: Any?): NanoHTTPD.Response =
        newFixedLengthResponse(status, APPLICATION_JSON, Json.writeValueAsString(body))

    /// GET/POST /apps (0.29.0) - launchable apps, for a source list in a controller.
    private fun appsResponse(): NanoHTTPD.Response =
        jsonResponse(NanoHTTPD.Response.Status.OK, mapOf("apps" to TvControl.apps(this)))

    /// POST /launch, /key, /volume (0.29.0). Runs on the web-server thread like /power:
    /// these are binder calls that touch no views, so the caller gets the real outcome.
    /// 400 = malformed request, 404 = no such app, 501 = this device cannot (fixed volume,
    /// home/back without the accessibility service).
    private fun controlResponse(result: TvControl.Result): NanoHTTPD.Response {
        Log.d(LOG_TAG, "control: ${result.status} ${result.message}")
        val status = when (result.status) {
            200 -> NanoHTTPD.Response.Status.OK
            400 -> NanoHTTPD.Response.Status.BAD_REQUEST
            403 -> NanoHTTPD.Response.Status.FORBIDDEN
            404 -> NanoHTTPD.Response.Status.NOT_FOUND
            501 -> NanoHTTPD.Response.Status.NOT_IMPLEMENTED
            else -> NanoHTTPD.Response.Status.INTERNAL_ERROR
        }
        if (result.ok) emit("control")
        return jsonResponse(status, mapOf("ok" to result.ok, "message" to result.message) + result.extra)
    }

    /// GET /permissions/diagnose - why a fix button did or did not appear.
    ///
    /// Exists so a bug report can carry facts instead of "it does not work": per
    /// permission the intent action, which activity resolves it (or nothing), whether that
    /// activity is a vendor placeholder, and the outcome of the last attempt. Reachable
    /// without adb, which is the point - the people who hit this are holding a remote.
    private fun diagnoseResponse(): NanoHTTPD.Response = newFixedLengthResponse(
        NanoHTTPD.Response.Status.OK,
        APPLICATION_JSON,
        Json.writeValueAsString(
            Permissions.diagnose(this) + mapOf("version" to BuildConfig.VERSION_NAME)
        )
    )

    /// POST /permissions/fix[?what=overlay|install|admin|accessibility|next]
    ///
    /// Puts the screen that grants a permission in front of the user. An app can never
    /// grant these itself, but it can walk someone to the exact spot - which beats
    /// telling a person with a remote in hand to go find an adb prompt.
    ///
    /// Without `what` it opens PiPup's own status screen, which lists every permission
    /// with its own button; `what=next` jumps straight to the first missing one.
    ///
    /// The TV is woken first: a settings screen on a dark panel helps nobody.
    /// 501 when this device has no such screen (Fire OS resolves these actions to
    /// do-nothing CTS placeholders) - the response then carries the adb command.
    private fun permissionFixResponse(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val requested = session.parameters["what"]?.firstOrNull()?.lowercase()

        val key = when (requested) {
            null, "", "app", "status" -> null
            "next" -> Permissions.firstMissing(this)
                ?: return newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    APPLICATION_JSON,
                    Json.writeValueAsString(
                        mapOf("what" to "next", "ok" to true, "nothingMissing" to true)
                    )
                )
            in Permissions.FIXABLE_KEYS -> requested
            else -> return InvalidRequest(
                "what must be one of ${Permissions.FIXABLE_KEYS}, 'next' or omitted"
            )
        }

        // Check before waking. Switching someone's TV on and then answering "this
        // device cannot show that screen" is the worst of both worlds - seen for real
        // when a failing request from Home Assistant still lit up a TV in another room.
        val refusal: String? = when {
            // Device-blocked app-op (errored/ignored): the settings screen exists but the
            // toggle will not stick (seen on a TCL Smart TV Pro), so adb is the only way.
            key != null && Permissions.opBlocked(this, key) ->
                "this device blocks granting this permission from its settings screen; " +
                    "grant it over adb instead"
            key != null && Permissions.fixIntent(this, key) == null ->
                "no screen for this permission on this device"
            // Silently dropped by the platform otherwise - see Permissions.canLaunchActivity
            !Permissions.canLaunchActivity(this) -> Permissions.BLOCKED_ERROR
            else -> null
        }
        if (refusal != null) {
            Log.d(LOG_TAG, "Permission fix ${key ?: "app"} refused: $refusal")
            return newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_IMPLEMENTED,
                APPLICATION_JSON,
                Json.writeValueAsString(
                    mapOf(
                        "what" to (key ?: "app"),
                        "ok" to false,
                        "reason" to refusal,
                        "granted" to key?.let { Permissions.granted(this, it) },
                        "adb" to key?.let { Permissions.adbCommand(it) }
                    )
                )
            )
        }

        PowerController.wake(this)

        val ok = if (key == null) Permissions.launchApp(this)
        else Permissions.launchFix(this, key)

        val body = Json.writeValueAsString(
            mapOf(
                "what" to (key ?: "app"),
                "ok" to ok,
                "granted" to key?.let { Permissions.granted(this, it) },
                // so a controller can show what to do where no screen exists
                "adb" to key?.takeIf { !ok }?.let { Permissions.adbCommand(it) }
            )
        )
        Log.d(LOG_TAG, "Permission fix ${key ?: "app"}: ok=$ok")
        return newFixedLengthResponse(
            if (ok) NanoHTTPD.Response.Status.OK else NanoHTTPD.Response.Status.NOT_IMPLEMENTED,
            APPLICATION_JSON,
            body
        )
    }

    /// Media summary for /state's lastPopup: {type, width, height?} or null.
    private fun mediaInfo(p: PopupProps): Map<String, Any?>? = when (val m = p.media) {
        is PopupProps.Media.Web -> mapOf("type" to "web", "width" to m.width, "height" to m.height, "poster" to (m.poster != null))
        is PopupProps.Media.Whep -> mapOf("type" to "whep", "width" to m.width, "height" to m.height, "poster" to (m.poster != null))
        is PopupProps.Media.Grid -> mapOf("type" to "grid", "tiles" to m.items.size, "columns" to m.columnCount, "fullscreen" to m.fullscreen)
        is PopupProps.Media.Video -> mapOf("type" to "video", "width" to m.width, "poster" to (m.poster != null), "softwareDecoder" to m.softwareDecoder, "bufferMs" to m.bufferMs)
        is PopupProps.Media.Image -> mapOf("type" to "image", "width" to m.width)
        is PopupProps.Media.Bitmap -> mapOf("type" to "bitmap", "width" to m.width)
        null -> null
    }

    /// Muted flag of the last popup's media; null when the media type has no audio.
    private fun mediaMuted(p: PopupProps): Boolean? = when (val m = p.media) {
        is PopupProps.Media.Web -> m.muted
        is PopupProps.Media.Whep -> m.muted
        is PopupProps.Media.Grid -> m.muted
        is PopupProps.Media.Video -> m.muted
        else -> null
    }

    override fun handleHttpRequest(session: NanoHTTPD.IHTTPSession?): NanoHTTPD.Response {
        return session?.let {
            // The HA integration announces itself on every request (ha-pipup >= 1.18.0) —
            // including the 15s /state poll, so this fills in without any popup being sent.
            session.headers["x-ha-pipup-version"]?.let { v -> mHaPipupSeen = v }
            // 0.30.1 (#76): whether Home Assistant installs the updates (ha-pipup >= 1.30.2)
            session.headers["x-ha-pipup-auto-update"]?.let { v -> noteHaAutoUpdate(v) }
            when(session.method) {
                NanoHTTPD.Method.GET -> {
                    when(session.uri) {
                        "/state" -> stateResponse()
                        "/permissions/diagnose" -> diagnoseResponse()
                        "/settings" -> settingsResponse(session)
                        "/apps" -> appsResponse()
                        "/volume" -> jsonResponse(NanoHTTPD.Response.Status.OK, TvControl.volumeState(this))
                        else -> InvalidRequest("unknown uri: ${session.uri}")
                    }
                }
                NanoHTTPD.Method.POST -> {

                    when(session.uri) {
                        "/state" -> stateResponse()
                        "/update" -> {
                            // Self-update on request (own popup button, or the Home
                            // Assistant integration's update entity). Runs off the
                            // web-server thread; progress is visible in /state.
                            if (UpdateManager.pendingConfirm != null) {
                                // A previous attempt is waiting for the on-screen
                                // confirmation (Android < 12): launch it again instead of
                                // answering "already running".
                                UpdateManager.confirmTrigger = "post-update"
                                mHandler.post { launchInstallConfirmation() }
                                OK("waiting for on-screen confirmation; launched again")
                            } else {
                                Thread {
                                    // Always re-check first. `updateAvailable` compares the
                                    // CACHED tag with the running build, so a cache from before
                                    // a newer release still reads as "an update is available" --
                                    // and installLatest() would then install that older APK from
                                    // its cached downloadUrl. Skipping the check is only safe
                                    // while the cache is fresh, which it is precisely not on a TV
                                    // that has been behind for a while. Seen in the field: 0.13.0
                                    // with 0.14.0 cached installed 0.14.0 while 0.14.2 was out.
                                    // A failed check (offline, GitHub rate limit) leaves the
                                    // previous cache untouched, so this still falls back to a
                                    // known older release instead of doing nothing.
                                    // The reply below goes out before any of this runs, so
                                    // the outcome is logged and (for a refusal or failure) left
                                    // in /state's update.lastError (2026-10-08).
                                    val checked = UpdateManager.check()
                                    if (UpdateManager.updateAvailable) {
                                        mHandler.post { showInstallingPopup() }
                                        UpdateManager.installLatest(this@PiPupService)?.let { err ->
                                            Log.w(LOG_TAG, "POST /update: install not started: $err")
                                        }
                                    } else {
                                        Log.i(LOG_TAG, "POST /update: no newer release than " +
                                            "${BuildConfig.VERSION_NAME} (check ${if (checked) "ok" else "failed"})")
                                    }
                                }.start()
                                OK("update started")
                            }
                        }
                        "/settings" -> settingsResponse(session)
                        "/power" -> powerResponse(session)
                        "/apps" -> appsResponse()
                        "/launch" -> controlResponse(TvControl.launch(this,
                            param(session, "package"), param(session, "uri"),
                            wake = param(session, "wake")?.lowercase() !in setOf("false", "0", "off")))
                        "/key" -> controlResponse(TvControl.key(this, param(session, "key")))
                        "/volume" -> controlResponse(TvControl.volume(this,
                            param(session, "level"), param(session, "step"), param(session, "mute"),
                            showUi = param(session, "ui")?.lowercase() !in setOf("false", "0", "off")))
                        "/permissions/fix" -> permissionFixResponse(session)
                        "/permissions/diagnose" -> diagnoseResponse()
                        "/cancel" -> {
                            // 0.24.0: ?id=<popup id> removes that popup; no id removes the
                            // popup without an id; ?all=true removes every popup.
                            // Answers only once the popup is gone from /state (0.17.1).
                            val all = session.parameters["all"]?.firstOrNull()?.lowercase() in setOf("true", "1", "on")
                            val id = session.parameters["id"]?.firstOrNull()
                            when (runOnMainSync {
                                if (all) { removeAllPopups(); true } else removePopup(keyOf(id))
                            }) {
                                null -> OK("accepted; main thread busy, removal still queued")
                                true -> OK()
                                false -> OK(if (id.isNullOrEmpty()) "no popup without an id" else "no popup with id $id")
                            }
                        }
                        "/notify" -> {
                            try {
                                val contentType = session.headers["content-type"] ?: APPLICATION_JSON
                                val popup = when {
                                    contentType.startsWith(APPLICATION_JSON) -> {

                                        // read the body by Content-Length when present, else
                                        // fall back to draining the stream (chunked transfer /
                                        // clients that omit the header would otherwise parse as empty)

                                        // Cap the body BEFORE allocating: this port is open to
                                        // the whole LAN, and `ByteArray(contentLength)` with an
                                        // attacker-chosen length is an OOM crash in one request.
                                        // 256 KB fits any real popup many times over.
                                        val declaredLength = session.headers["content-length"]?.toIntOrNull()
                                        if (declaredLength != null && declaredLength > MAX_JSON_BODY_BYTES) {
                                            throw Exception("body too large ($declaredLength bytes, max $MAX_JSON_BODY_BYTES)")
                                        }
                                        val content = if (declaredLength != null && declaredLength >= 0) {
                                            val buf = ByteArray(declaredLength)
                                            var read = 0
                                            while (read < declaredLength) {
                                                val res = session.inputStream.read(buf, read, declaredLength - read)
                                                if (res < 0) break
                                                read += res
                                            }
                                            buf
                                        } else {
                                            // chunked/no header: drain with the same ceiling
                                            val out = java.io.ByteArrayOutputStream()
                                            val buf = ByteArray(8192)
                                            while (true) {
                                                val res = session.inputStream.read(buf)
                                                if (res < 0) break
                                                out.write(buf, 0, res)
                                                if (out.size() > MAX_JSON_BODY_BYTES) {
                                                    throw Exception("body too large (max $MAX_JSON_BODY_BYTES)")
                                                }
                                            }
                                            out.toByteArray()
                                        }

                                        Json.readValue(content, PopupProps::class.java)
                                            ?: throw Exception("failed to parse input")

                                    }
                                    contentType.startsWith(MULTIPART_FORM_DATA) -> {

                                        // Cap before parsing (2026-10-08): parseBody spools the
                                        // whole body to disk, and this port is open to the LAN.
                                        val declaredLength = session.headers["content-length"]?.toLongOrNull()
                                        if (declaredLength != null && declaredLength > MAX_MULTIPART_BODY_BYTES) {
                                            throw Exception("body too large ($declaredLength bytes, max $MAX_MULTIPART_BODY_BYTES)")
                                        }

                                        val files = mutableMapOf<String, String>()
                                        session.parseBody(files)

                                        // flatten parameters

                                        val params = session.parameters.mapValues { it.value.firstOrNull() }

                                        val duration = params["duration"]?.toIntOrNull()
                                            ?: PopupProps.DEFAULT_DURATION

                                        val position = PopupProps.Position.values()[params["position"]?.toIntOrNull() ?: 0]

                                        val backgroundColor = params["backgroundColor"]
                                            ?: PopupProps.DEFAULT_BACKGROUND_COLOR

                                        val title = params["title"]

                                        val titleSize = params["titleSize"]?.toFloatOrNull()
                                            ?: PopupProps.DEFAULT_TITLE_SIZE

                                        val titleColor = params["titleColor"]
                                            ?: PopupProps.DEFAULT_TITLE_COLOR

                                        val message = params["message"]

                                        val messageSize = params["messageSize"]?.toFloatOrNull()
                                            ?: PopupProps.DEFAULT_MESSAGE_SIZE

                                        val messageColor = params["messageColor"]
                                            ?: PopupProps.DEFAULT_MESSAGE_COLOR

                                        val media = when(val image = files["image"]) {
                                            is String -> {
                                                val imageWidth = params["imageWidth"]?.toIntOrNull() ?: PopupProps.DEFAULT_MEDIA_WIDTH
                                                // use{}: decodeStream does not close its stream, so
                                                // every snapshot popup leaked one file descriptor
                                                val file = File(image).absoluteFile
                                                // 2026-10-08: read the dimensions first. A full decode of
                                                // a huge-dimension image (a few KB of PNG can declare
                                                // 50000x50000) ran the whole process out of memory.
                                                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                                file.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
                                                val w = bounds.outWidth
                                                val h = bounds.outHeight
                                                if (w <= 0 || h <= 0) throw Exception("could not decode the uploaded image")
                                                if (w.toLong() * h > MAX_IMAGE_PIXELS) {
                                                    throw Exception("image too large (${w}x${h}, max ${MAX_IMAGE_PIXELS / 1_000_000} MP)")
                                                }
                                                // downsample by powers of two until the longer side fits;
                                                // anything up to 4096 px decodes at full size, as before
                                                val maxSide = maxOf(MAX_IMAGE_SIDE_PX.toLong(), 2L * imageWidth)
                                                var sample = 1
                                                while (maxOf(w, h).toLong() / sample > maxSide) sample *= 2
                                                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                                                val bitmap = file.inputStream()
                                                    .use { BitmapFactory.decodeStream(it, null, opts) }
                                                    ?: throw Exception("could not decode the uploaded image")
                                                PopupProps.Media.Bitmap(image = bitmap, width = imageWidth)
                                            }
                                            else -> null
                                        }

                                        PopupProps(
                                            duration = duration,
                                            id = params["id"],
                                            position = position,
                                            backgroundColor =  backgroundColor,
                                            title = title,
                                            titleSize = titleSize,
                                            titleColor = titleColor,
                                            message = message,
                                            messageSize = messageSize,
                                            messageColor = messageColor,
                                            media = media,
                                            icon = params["icon"],
                                            iconPosition = params["iconPosition"],
                                            iconWidth = params["iconWidth"]?.toIntOrNull()
                                                ?: PopupProps.DEFAULT_ICON_WIDTH,
                                            tts = params["tts"],
                                            ttsLanguage = params["ttsLanguage"],
                                            // styling also applies to uploaded snapshots
                                            urgency = params["urgency"],
                                            borderColor = params["borderColor"],
                                            borderWidth = params["borderWidth"]?.toIntOrNull(),
                                            cornerRadius = params["cornerRadius"]?.toFloatOrNull(),
                                            showProgress = params["showProgress"]?.toBoolean() ?: false,
                                            dismissScreensaver = params["dismissScreensaver"]?.toBoolean() ?: true,
                                            animation = params["animation"],
                                            padding = params["padding"]?.toIntOrNull(),
                                            sound = params["sound"],
                                            soundVolume = params["soundVolume"]?.toFloatOrNull(),
                                            bringToFront = params["bringToFront"]?.toBoolean() ?: false,
                                            stack = params["stack"]
                                        )
                                    }
                                    else -> throw Exception("invalid content-type")
                                }

                                // The callback URL carries a single-use token (2026-10-08):
                                // keep it out of logcat and the echoed reply.
                                val redacted = popup.redacted()
                                Log.d(LOG_TAG, "received popup: $redacted")

                                // Answer only once the popup exists and /state reflects it
                                // (0.17.1). Waits for the view to be built, not for its media
                                // to load — an RTSP handshake must not hold the reply.
                                when (runOnMainSync { createPopup(popup) }) {
                                    true -> OK("$redacted")
                                    false -> ServerError("popup could not be created (see logcat)")
                                    null -> OK("accepted; main thread busy, popup still queued: $redacted")
                                }


                            } catch (ex: Throwable) {
                                Log.e(LOG_TAG, ex.message ?: "unknown error")
                                InvalidRequest(ex.message)
                            }
                        }
                        else -> InvalidRequest("unknown uri: ${session.uri}")
                    }
                }
                else -> InvalidRequest("invalid method")
            }
        } ?: InvalidRequest()
    }

    companion object {
        const val LOG_TAG = "PiPupService"
        const val SERVER_PORT = 7979
        const val NSD_SERVICE_TYPE = "_pipup._tcp."
        const val PREFS_NAME = "pipup"
        const val PREF_DEVICE_ID = "device_id"
        const val PREF_UPDATE_ANNOUNCED = "update_announced"
        const val PREF_UPDATE_CHECKS = "update_checks"
        const val PREF_WEBHOOK = "webhook"
        const val PREF_UPDATE_SOURCE = "update_source"
        /// 0.30.1 (#76): wall-clock time of the last X-HA-PiPup-Auto-Update: on
        const val PREF_HA_AUTO_UPDATE_AT = "ha_auto_update_at"
        const val UPDATE_POPUP_ID = "pipup-update"
        const val CONFIRM_POPUP_ID = "pipup-update-confirm"
        /// MainActivity intent action: launch the pending install confirmation (0.26.1).
        const val ACTION_CONFIRM_INSTALL = "nl.rogro82.pipup.action.CONFIRM_INSTALL"
        const val PREF_LAST_RUN_VERSION = "last_run_version"
        // First check shortly after boot (network is usually up by then), then twice a day.
        // GitHub's anonymous API allows 60 requests/hour per IP; this is nowhere near it.
        const val UPDATE_CHECK_FIRST_DELAY_MS = 120_000L
        const val UPDATE_CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L
        const val WATCHDOG_INTERVAL_MS = 30_000L
        /// 0.29.0: one volume_changed push per burst of volume steps
        const val VOLUME_PUSH_DEBOUNCE_MS = 400L
        const val ONGOING_NOTIFICATION_ID = 123
        const val WEBSERVER_START_ATTEMPTS = 3
        const val WEBSERVER_RETRY_DELAY_MS = 500L
        const val TTS_IDLE_TIMEOUT_MS = 60_000L
        const val MAX_JSON_BODY_BYTES = 256 * 1024
        // multipart /notify (2026-10-08): body cap, and the image limits for the upload
        const val MAX_MULTIPART_BODY_BYTES = 32L * 1024 * 1024
        const val MAX_IMAGE_PIXELS = 32_000_000L
        const val MAX_IMAGE_SIDE_PX = 4096
        const val MULTIPART_FORM_DATA = "multipart/form-data"
        const val APPLICATION_JSON = "application/json"
        // How long /notify and /cancel wait for the main thread before answering anyway.
        // Building a popup view takes tens of ms; 2 s only elapses when the UI thread is
        // stuck, and then a held-open HTTP connection would help nobody.
        const val MAIN_SYNC_TIMEOUT_MS = 2_000L
        // gap between a popup and the screen edge
        const val SCREEN_MARGIN_PX = 20
        /// space between two stacked popups (0.27.0)
        const val STACK_GAP_PX = 12

        fun OK(message: String? = null): NanoHTTPD.Response = newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", message)
        fun InvalidRequest(message: String? = null): NanoHTTPD.Response = newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "text/plain", "invalid request: $message")
        fun ServerError(message: String? = null): NanoHTTPD.Response = newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "text/plain", "error: $message")
    }
}
