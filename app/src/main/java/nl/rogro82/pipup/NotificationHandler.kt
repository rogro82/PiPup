package nl.rogro82.pipup

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.VideoView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy


sealed class NotificationHandler {
    abstract val context: Context
    abstract val root: ViewGroup
    abstract val notification: Notification

    open fun create() {
        val title = root.findViewById<TextView>(R.id.notification_title)
        val message = root.findViewById<TextView>(R.id.notification_message)
        val frame = root.findViewById<FrameLayout>(R.id.notification_frame)

        if(notification.media == null) {
            root.removeView(frame)
        }

        if(notification.title == null) {
            root.removeView(title)
        } else {
            title.text = notification.title
            title.textSize = notification.titleSize
            title.setTextColor(Color.parseColor(notification.titleColor))
        }

        if(notification.message == null) {
            root.removeView(message)
        } else {
            message.text = notification.message
            message.textSize = notification.messageSize
            message.setTextColor(Color.parseColor(notification.messageColor))
        }

        root.setBackgroundColor(Color.parseColor(notification.backgroundColor))
    }

    open fun destroy() {}

    class Default(
        override val context: Context,
        override val root: ViewGroup,
        override val notification: Notification
    ) : NotificationHandler()

    private class Video(override val context: Context, override val root: ViewGroup, override val notification: Notification, val video: Notification.Media.Video): NotificationHandler() {
        private lateinit var mVideoView: VideoView

        override fun create() {
            super.create()

            val frame = root.findViewById<FrameLayout>(R.id.notification_frame)

            mVideoView = VideoView(context).apply {
                setVideoURI(Uri.parse(video.uri))
                setOnPreparedListener {
                    it.setOnVideoSizeChangedListener { mp, width, height ->

                        // resize video and show notification view

                        val videoSize = Math.min(width, video.width)

                        layoutParams = FrameLayout.LayoutParams(videoSize, WindowManager.LayoutParams.WRAP_CONTENT).apply {
                            gravity = Gravity.CENTER
                        }

                        root.visibility = View.VISIBLE

                        requestLayout()
                    }
                }

                start()
            }

            frame.addView(mVideoView)

            // hide until the video is loaded

            root.visibility = View.INVISIBLE
        }

        override fun destroy() {
            try {
                if(mVideoView.isPlaying) {
                    mVideoView.stopPlayback()
                }
            } catch(e: Throwable) {}
        }
    }

    private class Image(override val context: Context, override val root: ViewGroup, override val notification: Notification, val image: Notification.Media.Image): NotificationHandler() {
        override fun create() {
            super.create()

            val frame = root.findViewById<FrameLayout>(R.id.notification_frame)

            try {
                val imageView = ImageView(context)

                val layoutParams =
                    FrameLayout.LayoutParams(image.width, WindowManager.LayoutParams.WRAP_CONTENT).apply {
                        gravity = Gravity.CENTER
                    }

                frame.addView(imageView, layoutParams)

                Glide.with(context)
                    .load(Uri.parse(image.uri))
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .skipMemoryCache(true)
                        .into(imageView)

            } catch(e: Throwable) {
                root.removeView(frame)
            }
        }
    }

    private class Bitmap(override val context: Context, override val root: ViewGroup, override val notification: Notification, val bitmap: Notification.Media.Bitmap): NotificationHandler() {
        var mImageView: ImageView? = null

        override fun create() {
            super.create()

            val frame = root.findViewById<FrameLayout>(R.id.notification_frame)
            mImageView = ImageView(context).apply {
                setImageBitmap(bitmap.image)
            }

            val layoutParams =
                FrameLayout.LayoutParams(bitmap.width, WindowManager.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER
                }

            frame.addView(mImageView, layoutParams)
        }

        override fun destroy() {
            try {
                mImageView?.setImageDrawable(null)
                bitmap.image.recycle()
            } catch(e: Throwable) {}
        }
    }

    private class Web(override val context: Context, override val root: ViewGroup, override val notification: Notification, val web: Notification.Media.Web): NotificationHandler() {
        override fun create() {
            super.create()

            val frame = root.findViewById<FrameLayout>(R.id.notification_frame)
            val webView = WebView(context).apply {
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                loadUrl(web.uri)
            }

            val layoutParams = FrameLayout.LayoutParams(
                web.width,
                web.height
            )

            frame.addView(webView, layoutParams)
        }
    }

    companion object {
        fun create(context: Context, root: ViewGroup, notification: Notification): NotificationHandler
        {
            return when (notification.media) {
                is Notification.Media.Web -> Web(context, root, notification, notification.media)
                is Notification.Media.Video -> Video(context, root, notification, notification.media)
                is Notification.Media.Image -> Image(context, root, notification, notification.media)
                is Notification.Media.Bitmap -> Bitmap(context, root, notification, notification.media)
                else -> Default(context, root, notification)
            }.apply {
                create()
            }
        }
    }
}