package nl.rogro82.pipup

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.support.v4.app.NotificationCompat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import java.io.File


class PiPupService : Service(), WebServer.Handler {
    private var mStarted: Boolean = false
    private val mHandler: Handler = Handler()
    private var mOverlay: FrameLayout? = null
    private var mNotification: NotificationHandler? = null
    private lateinit var mWebServer: WebServer


    override fun onCreate() {
        super.onCreate()

        initNotificationChannel("service_channel", "Service channel", "Service channel")
        val mBuilder = NotificationCompat.Builder(this, "service_channel")
            .setContentTitle("PiPup")
            .setContentText("Service running")
            .setSmallIcon(R.drawable.app_icon_your_company)
            .setAutoCancel(false)
            .setOngoing(true)

        startForeground(ONGOING_NOTIFICATION_ID, mBuilder.build())

        mWebServer = WebServer(SERVER_PORT, this).apply {
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        mWebServer.stop()
    }

    override fun onBind(intent: Intent): IBinder {
        TODO("Return the communication channel to the service.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(!mStarted) {

            createNotification(
                Notification(
                    duration = 5,
                    title = "PiPup service",
                    titleSize = 20f,
                    message = "Service started successfully",
                    backgroundColor = "#33000000"
                )
            )

            mStarted = true
        }

        return START_STICKY
    }

    private fun removeNotification(removeOverlay: Boolean = false) {

        mHandler.removeCallbacksAndMessages(null)

        mNotification = mNotification?.let {
            it.destroy()
            null
        }

        mOverlay?.apply {

            removeAllViews()
            if (removeOverlay) {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeViewImmediate(mOverlay)

                mOverlay = null
            }
        }
    }

    private fun createNotification(notification: Notification) {
        try {

            Log.d(LOG_TAG, "Create notification: $notification")

            // remove current notification

            removeNotification()

            // create or reuse the current overlay

            mOverlay = when (val overlay = mOverlay) {
                is FrameLayout -> overlay
                else -> FrameLayout(this).apply {

                    setPadding(20, 20, 20, 20)

                    val layoutFlags: Int = when {
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        else -> WindowManager.LayoutParams.TYPE_TOAST
                    }

                    val params = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        layoutFlags,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                        PixelFormat.TRANSLUCENT
                    )

                    val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    wm.addView(this, params)
                }
            }.also {

                // inflate the notification layout

                val notificationView = View.inflate(this, R.layout.notification, null) as ViewGroup

                // create the notification and add it to the overlay

                mNotification = NotificationHandler.create(this, notificationView, notification)

                it.addView(notificationView, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ). apply {

                    // position the notification

                    gravity = when(notification.position) {
                        Notification.Position.TopRight -> Gravity.TOP or Gravity.END
                        Notification.Position.TopLeft -> Gravity.TOP or Gravity.START
                        Notification.Position.BottomRight -> Gravity.BOTTOM or Gravity.END
                        Notification.Position.BottomLeft -> Gravity.BOTTOM or Gravity.START
                        Notification.Position.Center -> Gravity.CENTER
                    }
                })
            }

            // schedule removal

            mHandler.postDelayed({
                removeNotification(true)
            }, (notification.duration * 1000).toLong())

        } catch (ex: Throwable) {
            ex.printStackTrace()
        }
    }

    override fun handleHttpRequest(session: NanoHTTPD.IHTTPSession?): NanoHTTPD.Response {
        return session?.let {
            when(session.method) {
                NanoHTTPD.Method.POST -> {

                    when(session.uri) {
                        "/cancel" -> {
                            mHandler.post {
                                removeNotification(true)
                            }
                            OK()
                        }
                        "/notify" -> {
                            try {
                                val contentType = session.headers["content-type"] ?: "application/json"
                                val notification = when {
                                    contentType.startsWith(APPLICATION_JSON) -> {

                                        // try to handle it as json

                                        val contentLength = session.headers["content-length"]?.toInt() ?: 0
                                        val content = ByteArray(contentLength)

                                        session.inputStream.read(content, 0, contentLength)

                                        Json.readValue(content, Notification::class.java)
                                            ?: throw Exception("failed to parse input")

                                    }
                                    contentType.startsWith(MULTIPART_FORM_DATA) -> {

                                        val files = mutableMapOf<String, String>()
                                        session.parseBody(files)

                                        // flatten parameters

                                        val params = session.parameters.mapValues { it.value.firstOrNull() }

                                        val duration = params["duration"]?.toIntOrNull()
                                            ?: Notification.DEFAULT_DURATION

                                        val position = Notification.Position.values()[params["position"]?.toIntOrNull() ?: 0]

                                        val backgroundColor = params["backgroundColor"]
                                            ?: Notification.DEFAULT_BACKGROUND_COLOR

                                        val title = params["title"]

                                        val titleSize = params["titleSize"]?.toFloatOrNull()
                                            ?: Notification.DEFAULT_TITLE_SIZE

                                        val titleColor = params["titleColor"]
                                            ?: Notification.DEFAULT_TITLE_COLOR

                                        val message = params["message"]

                                        val messageSize = params["messageSize"]?.toFloatOrNull()
                                            ?: Notification.DEFAULT_TITLE_SIZE

                                        val messageColor = params["messageColor"]
                                            ?: Notification.DEFAULT_TITLE_COLOR

                                        val media = when(val image = files["image"]) {
                                            is String -> {
                                                File(image).absoluteFile.let {
                                                    val bitmap = BitmapFactory.decodeStream(it.inputStream())
                                                    val imageWidth = params["imageWidth"]?.toIntOrNull() ?: Notification.DEFAULT_MEDIA_WIDTH

                                                    Notification.Media.Bitmap(image = bitmap, width = imageWidth)
                                                }
                                            }
                                            else -> null
                                        }

                                        Notification(
                                            duration = duration,
                                            position = position,
                                            backgroundColor =  backgroundColor,
                                            title = title,
                                            titleSize = titleSize,
                                            titleColor = titleColor,
                                            message = message,
                                            messageSize = messageSize,
                                            messageColor = messageColor,
                                            media = media
                                        )
                                    }
                                    else -> throw Exception("invalid content-type")
                                }

                                Log.d(LOG_TAG, "received notification: $notification")

                                mHandler.post {
                                    createNotification(notification)
                                }

                                OK("$notification")


                            } catch (ex: Throwable) {
                                Log.e(LOG_TAG, ex.message)
                                InvalidRequest(ex.message)
                            }
                        }
                        else -> InvalidRequest("unkown uri: ${session.uri}")
                    }
                }
                else -> InvalidRequest("invalid method")
            }
        } ?: InvalidRequest()
    }

    companion object {
        const val LOG_TAG = "PiPupService"
        const val SERVER_PORT = 7979
        const val ONGOING_NOTIFICATION_ID = 123
        const val MULTIPART_FORM_DATA = "multipart/form-data"
        const val APPLICATION_JSON = "application/json"

        fun OK(message: String? = null): NanoHTTPD.Response = newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", message)
        fun InvalidRequest(message: String? = null): NanoHTTPD.Response = newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "text/plain", "invalid request: $message")
    }
}
