package nl.rogro82.pipup

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.TextureView
import android.widget.*
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import android.os.Build
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition

// TODO: convert dimensions from px to dp

@SuppressLint("ViewConstructor")
sealed class PopupView(context: Context, val popup: PopupProps) : LinearLayout(context) {

    /// set by the service after build(); invoked when the user presses a popup button
    var onButton: ((PopupProps.Button) -> Unit)? = null

    /// set by the service after build(); invoked once, with the milliseconds between this
    /// view's creation and the first rendered frame of its stream (video/web). Reported in
    /// /state.lastPopup.firstFrameMs so the start-up cost of a live popup is measurable.
    var onFirstFrame: ((Long) -> Unit)? = null
    /// set by the service after build(); invoked with a short reason when the stream fails
    /// (whep). Reported in /state.lastPopup.mediaError.
    var onMediaError: ((String) -> Unit)? = null
    private val mCreatedAt = android.os.SystemClock.elapsedRealtime()
    private var mFirstFrameReported = false

    /// Poster: a still image (e.g. a camera snapshot) laid over the stream area the moment
    /// the popup appears, so a live popup never opens as an empty box while the stream
    /// connects (RTSP handshake, WebView start-up, waiting for a keyframe). Faded out on
    /// the first rendered frame. If the poster fails to load nothing happens (Glide fails
    /// silently); if the stream never paints the poster simply stays — a snapshot from
    /// seconds ago beats an empty frame, so there is deliberately no timeout.
    protected var mPoster: ImageView? = null

    /// "Live" pill over the stream area, hidden until the first frame renders.
    protected var mLiveBadge: TextView? = null

    protected fun attachLiveBadge(frame: FrameLayout) {
        if (!popup.liveBadge || mLiveBadge != null) return
        val badge = TextView(context).apply {
            text = "Live"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(18, 6, 18, 6)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f
                setColor(parseColorOrDefault(popup.liveBadgeColor ?: DEFAULT_LIVE_BADGE_COLOR, DEFAULT_LIVE_BADGE_COLOR))
            }
            elevation = 4f
            alpha = 0f
            visibility = View.GONE
        }
        frame.addView(badge, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            liveBadgeGravity(popup.liveBadgePosition)
        ).apply { setMargins(16, 16, 16, 16) })
        mLiveBadge = badge
    }

    private fun revealLiveBadge() {
        val badge = mLiveBadge ?: return
        if (badge.visibility == View.VISIBLE) return
        badge.visibility = View.VISIBLE
        badge.bringToFront()
        badge.animate().alpha(1f).setDuration(150).start()
    }

    /// Full-bleed layout: the popup keeps only its border inset, so the media frame spans the
    /// whole width with its top corners clipped to the popup's; the text and button rows carry
    /// the padding instead (the buttons row sets its own when it is built).
    private fun applyFullBleed(frame: FrameLayout, header: LinearLayout, pad: Int, border: Int, radius: Float) {
        // compact text block under the media, like a TV camera notification: a smaller gap
        // above and below, and no extra font padding between title and message
        val gap = (pad * 2) / 3
        setPadding(border, border, border, gap)
        frame.setPadding(0, 0, 0, 0)
        header.setPadding(pad, gap, pad, 0)
        header.findViewById<TextView>(R.id.popup_title)?.includeFontPadding = false
        header.findViewById<TextView>(R.id.popup_message)?.includeFontPadding = false
        val clip = (radius - border).coerceAtLeast(0f)
        frame.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                // extend below the frame so only the top corners are rounded
                outline.setRoundRect(0, 0, view.width, view.height + clip.toInt(), clip)
            }
        }
        frame.clipToOutline = clip > 0f
    }

    /// `onSized` receives the poster's intrinsic width/height once decoded, so the caller can
    /// give the stream area the poster's aspect ratio right away. A camera snapshot has the same
    /// aspect as the camera's stream, so still and live then line up exactly and the hand-over
    /// shows no size jump (a provisional 16:9 box letterboxed a 4:3 snapshot, then jumped).
    protected fun attachPoster(frame: FrameLayout, url: String?, params: FrameLayout.LayoutParams,
                               onSized: ((Int, Int) -> Unit)? = null) {
        attachLiveBadge(frame)   // every live stream area gets its poster here, so its badge too
        if (url.isNullOrEmpty()) return
        val iv = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_XY   // box already has the image's aspect
            setBackgroundColor(Color.TRANSPARENT)
        }
        frame.addView(iv, params)   // added after the stream view => drawn on top
        mPoster = iv
        try {
            Glide.with(context).asBitmap().load(url)
                .diskCacheStrategy(DiskCacheStrategy.NONE).skipMemoryCache(true)
                .into(object : CustomTarget<android.graphics.Bitmap>() {
                    override fun onResourceReady(resource: android.graphics.Bitmap, transition: Transition<in android.graphics.Bitmap>?) {
                        if (mPoster !== iv) return   // already handed over / destroyed
                        iv.setImageBitmap(resource)
                        if (resource.width > 0 && resource.height > 0) onSized?.invoke(resource.width, resource.height)
                    }
                    override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) { iv.setImageDrawable(null) }
                    override fun onLoadFailed(errorDrawable: android.graphics.drawable.Drawable?) {
                        Log.w(LOG_TAG, "poster failed to load: $url")
                    }
                })
        } catch (_: Throwable) {}
    }

    /// Called by the subclass when its stream rendered its first frame: fades the poster out
    /// (150 ms, then removed) and reports firstFrameMs once.
    protected fun onStreamFirstFrame() {
        if (!mFirstFrameReported) {
            mFirstFrameReported = true
            val ms = android.os.SystemClock.elapsedRealtime() - mCreatedAt
            Log.d(LOG_TAG, "first frame after ${ms} ms" + (if (mPoster != null) " (poster shown)" else ""))
            try { onFirstFrame?.invoke(ms) } catch (_: Throwable) {}
        }
        revealLiveBadge()
        val iv = mPoster ?: return
        mPoster = null
        iv.animate().alpha(0f).setDuration(150).withEndAction {
            try { Glide.with(context).clear(iv) } catch (_: Throwable) {}
            (iv.parent as? ViewGroup)?.removeView(iv)
        }.start()
    }

    protected fun destroyPoster() {
        mPoster?.let { iv ->
            try { Glide.with(context).clear(iv) } catch (_: Throwable) {}
            iv.setImageDrawable(null)
        }
        mPoster = null
    }

    private var mProgressAnimator: ObjectAnimator? = null

    open fun create() {
        inflate(context, R.layout.popup,this)

        layoutParams = LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            orientation = VERTICAL
            minimumWidth = 240
        }

        // 0.19.1: configurable outer padding; the focus scale-up of buttons and the
        // slide animations may paint outside their box, so never clip children.
        val pad = popup.padding ?: 20
        val fullBleed = popup.mediaFullBleed && popup.media != null
        if (!fullBleed) setPadding(pad, pad, pad, pad)
        clipChildren = false
        clipToPadding = false

        val title = findViewById<TextView>(R.id.popup_title)
        val message = findViewById<TextView>(R.id.popup_message)
        val frame = findViewById<FrameLayout>(R.id.popup_frame)
        val header = findViewById<LinearLayout>(R.id.popup_header)
        val textcol = findViewById<LinearLayout>(R.id.popup_textcol)

        // 0.29.2 (#73): the frame's 3dp top/bottom padding (popup.xml) left a strip of the
        // popup background above and below a full-screen page and clipped it by as much
        if (popup.coversScreen) frame.setPadding(0, 0, 0, 0)

        if(popup.media == null) {
            removeView(frame)
        }

        if(popup.title.isNullOrEmpty()) {
            textcol.removeView(title)
        } else {
            title.text = popup.title
            title.textSize = popup.titleSize
            title.setTextColor(parseColorOrDefault(popup.titleColor, PopupProps.DEFAULT_TITLE_COLOR))
        }

        if(popup.message.isNullOrEmpty()) {
            textcol.removeView(message)
        } else {
            message.text = popup.message
            message.textSize = popup.messageSize
            message.setTextColor(parseColorOrDefault(popup.messageColor, PopupProps.DEFAULT_MESSAGE_COLOR))
        }

        // Optional icon beside the text block, left (default) or right. Loaded like
        // any other image; adjustViewBounds keeps its aspect ratio at the given width.
        if (!popup.icon.isNullOrEmpty()) {
            val onRight = popup.iconPosition.equals("right", ignoreCase = true)
            val iconView = findViewById<ImageView>(
                if (onRight) R.id.popup_icon_right else R.id.popup_icon_left
            )
            iconView.layoutParams = (iconView.layoutParams as LinearLayout.LayoutParams).apply {
                width = popup.iconWidth
                height = LayoutParams.WRAP_CONTENT
            }
            iconView.visibility = View.VISIBLE
            // override(): the view's height is WRAP_CONTENT, which Glide would otherwise
            // decode at screen size; bound it to the icon width (adjustViewBounds keeps
            // the aspect ratio on display).
            Glide.with(context).load(popup.icon).override(popup.iconWidth).into(iconView)
        }

        // Nothing left in the header (media-only popup) -> drop the empty row.
        if (textcol.childCount == 0 && popup.icon.isNullOrEmpty()) {
            removeView(header)
        }

        // background with an optional border: `urgency` is a shorthand for a
        // (width, color) pair, and borderColor/borderWidth/cornerRadius each
        // override their part of it independently.
        background = GradientDrawable().apply {
            setColor(parseColorOrDefault(popup.backgroundColor, PopupProps.DEFAULT_BACKGROUND_COLOR))

            val preset = PopupProps.URGENCY_PRESETS[popup.urgency?.lowercase()]
            val width = popup.borderWidth ?: preset?.first
                ?: popup.borderColor?.let { PopupProps.DEFAULT_BORDER_WIDTH }
            val color = popup.borderColor ?: preset?.second
                ?: PopupProps.DEFAULT_BORDER_COLOR

            if (width != null && width > 0) {
                setStroke(width, parseColorOrDefault(color, PopupProps.DEFAULT_BORDER_COLOR))
            }
            // rounded corners keep working without a border, and an explicit 0 squares them off
            val radius = popup.cornerRadius
                ?: if (width != null && width > 0) PopupProps.DEFAULT_CORNER_RADIUS else 0f
            cornerRadius = radius
            if (fullBleed) applyFullBleed(frame, header, pad, width?.takeIf { it > 0 } ?: 0, radius)
        }

        // DPAD-focusable buttons (the service makes the overlay window focusable).
        // In an overlay window the platform button background carries no visible
        // focus state, so D-pad navigation moved focus invisibly - the user pressed
        // the arrows, saw nothing change and concluded the popup was not interactive
        // (reported for a 3-button popup on a Shield). Each button gets an explicit
        // focused/unfocused background plus a small scale bump on focus, and the
        // first one takes focus so there is an indicator from the start.
        if (popup.buttons.isNotEmpty()) {
            // 0.19.2: with an explicit buttonSize the margins around the buttons scale
            // with the same factor as their padding, so a compact popup is compact all
            // the way to the row above (field report: the gap between the media frame
            // and the buttons stayed at the classic size). Classic look unchanged.
            val marginScale = popup.buttonSize?.takeIf { it > 0 }?.div(14f) ?: 1f
            fun m(base: Int) = (base * marginScale).toInt().coerceAtLeast(2)
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
                clipChildren = false
                clipToPadding = false
                if (fullBleed) setPadding(pad, 0, pad, 0)
            }
            var firstButton: Button? = null
            popup.buttons.forEach { btn ->
                val button = Button(context).apply {
                    text = btn.label
                    isFocusable = true
                    isAllCaps = false
                    setTextColor(parseColorOrDefault(popup.buttonTextColor ?: "#FFFFFF", "#FFFFFF"))
                    // Compact buttons (0.19.0): an explicit buttonSize scales the text and
                    // the padding with it; without it the classic look stays byte-identical.
                    // 14 sp is the platform Button default the classic padding was tuned for.
                    val size = popup.buttonSize
                    if (size != null && size > 0) {
                        textSize = size
                        val h = (size / 14f * 36).toInt().coerceAtLeast(8)
                        val v = (size / 14f * 16).toInt().coerceAtLeast(4)
                        setPadding(h, v, h, v)
                        minWidth = 0; minimumWidth = 0
                        minHeight = 0; minimumHeight = 0
                    } else {
                        setPadding(36, 16, 36, 16)
                    }
                    stateListAnimator = null   // the platform one would fight our scale
                    background = buttonBackground(ButtonStyle.from(popup))
                    setOnClickListener { onButton?.invoke(btn) }
                    setOnFocusChangeListener { v, hasFocus ->
                        val s = if (hasFocus) 1.12f else 1f
                        v.animate().scaleX(s).scaleY(s).setDuration(120).start()
                    }
                }
                if (firstButton == null) firstButton = button
                row.addView(button, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    // 0.19.1: bottom margin was 0, which clipped the buttons' rounded
                    // corners against the popup border (field report with a screenshot).
                    setMargins(m(10), m(10), m(10), m(6))
                })
            }
            // A media-only popup has no header left (removed above), so inline buttons
            // fall back to their own row instead of landing in a detached view.
            if (popup.buttonsInline && header.parent != null) {
                row.setPadding(0, 0, 0, 0)
                header.addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            } else {
                addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
            // Give the first button focus once the view is attached, so there is a
            // visible highlight before the user touches the remote.
            firstButton?.post { firstButton?.requestFocus() }
        }

        // countdown bar for finite durations
        if (popup.showProgress && !popup.indefinite) {
            val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progress = 1000
            }
            addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, 8).apply {
                setMargins(0, 12, 0, 0)
            })
            mProgressAnimator = ObjectAnimator.ofInt(bar, "progress", 1000, 0).apply {
                duration = popup.duration * 1000L
                interpolator = LinearInterpolator()
                start()
            }
        }
    }

    open fun destroy() {
        try {
            mProgressAnimator?.cancel()
        } catch (_: Throwable) {}
        mProgressAnimator = null
    }

    /// Entrance animation (0.19.0). Runs once per *build*: an update-in-place reuses
    /// the view and never comes back here, so a re-notified popup does not keep
    /// sliding in. Unknown names fall back to appearing instantly.
    /// Resting alpha of the popup: `opacity` (0.24.0), else fully opaque.
    private val targetAlpha: Float get() = (popup.opacity ?: 1f).coerceIn(0f, 1f)

    fun animateIn() {
        alpha = targetAlpha
        when (popup.animation?.lowercase()) {
            null, "", "none" -> return
            "fade" -> { alpha = 0f; animate().alpha(targetAlpha).setDuration(220).start() }
            "slide_left" -> slideIn(-1f, 0f)
            "slide_right" -> slideIn(1f, 0f)
            "slide_top" -> slideIn(0f, -1f)
            "slide_bottom" -> slideIn(0f, 1f)
            else -> Log.w(LOG_TAG, "unknown animation '${popup.animation}' — showing instantly")
        }
    }

    private fun slideIn(dx: Float, dy: Float) {
        // Distance based on the popup's own size once measured; a fixed 300 px
        // fallback covers the first frame before layout.
        post {
            translationX = dx * (if (width > 0) width.toFloat() else 300f)
            translationY = dy * (if (height > 0) height.toFloat() else 300f)
            alpha = 0.6f * targetAlpha
            animate().translationX(0f).translationY(0f).alpha(targetAlpha).setDuration(220).start()
        }
        // hide the pre-layout frame at its final position
        translationX = dx * 3000f; translationY = dy * 3000f
    }

    /// Whether [animateOut] is running (2026-10-08). Main thread only.
    private var mExiting = false

    /// Stop a running exit animation and put the view at rest: in place, at its resting
    /// alpha (2026-10-08). Used when an update-in-place keeps a popup whose exit animation
    /// had already started; a no-op otherwise, so an entrance animation runs on. NB a
    /// cancelled ViewPropertyAnimator still runs its end action (onAnimationEnd follows
    /// onAnimationCancel), so [animateOut]'s onEnd DOES fire: the caller's generation
    /// check is what keeps it from removing the popup.
    fun cancelExit() {
        if (!mExiting) return
        mExiting = false
        animate().cancel()
        translationX = 0f
        translationY = 0f
        alpha = targetAlpha
    }

    /// Exit animation for a popup that expires naturally; [onEnd] performs the real
    /// removal. Callers that replace or cancel a popup skip this and tear down at
    /// once — /cancel's "200 = gone from /state" contract (0.17.1) stays intact.
    fun animateOut(onEnd: () -> Unit) {
        when (popup.animation?.lowercase()) {
            null, "", "none" -> { onEnd(); return }
        }
        val dx: Float; val dy: Float
        when (popup.animation?.lowercase()) {
            "slide_left" -> { dx = -1f; dy = 0f }
            "slide_right" -> { dx = 1f; dy = 0f }
            "slide_top" -> { dx = 0f; dy = -1f }
            "slide_bottom" -> { dx = 0f; dy = 1f }
            else -> { dx = 0f; dy = 0f } // fade
        }
        mExiting = true
        animate()
            .translationX(dx * width)
            .translationY(dy * height)
            .alpha(0f)
            .setDuration(180)
            .withEndAction { mExiting = false; runCatching(onEnd) }
            .start()
    }

    private class Default(context: Context, popup: PopupProps) : PopupView(context, popup) {
        init { create() }
    }

    /// All video_url playback goes through ExoPlayer rendering into a TextureView.
    ///
    /// Why not the stock VideoView (used up to 0.15.x)? It is a SurfaceView: a separate
    /// composition layer that SurfaceFlinger puts on the hardware video overlay plane. Measured
    /// on a Fire TV while another app was playing a film (which owns that plane): the decoder
    /// rendered frames, but nothing was composited — the popup was a transparent hole with the
    /// film through it; the same popup rendered fine with no video playing. A TextureView is
    /// composited into this window by the GPU (AOSP: "TextureView is always composited using
    /// GL"), needs no overlay plane, and shows over other video — verified on the same TV with
    /// the film running. VideoView also popped a system AlertDialog on error, which crashed a
    /// Service-hosted overlay (BadTokenException); ExoPlayer just reports the error.
    ///
    /// Sources: rtsp:// via RtspMediaSource with RTP-over-TCP forced (UDP is unreliable on
    /// Wi-Fi); everything else (HLS .m3u8 as used by camera_mode: stream, progressive http)
    /// via DefaultMediaSourceFactory. Audio plays only when muted=false — an AudioTrack
    /// renegotiates HDMI audio and interrupted playback on the TV, and the HA integration
    /// defaults to muted.
    private class Video(context: Context, popup: PopupProps, val media: PopupProps.Media.Video): PopupView(context, popup) {
        private var mExoPlayer: ExoPlayer? = null
        private var mTextureView: TextureView? = null

        init { create() }

        @UnstableApi
        override fun create() {
            super.create()

            visibility = View.INVISIBLE

            val frame = findViewById<FrameLayout>(R.id.popup_frame)
            val uri = media.uri

            val textureView = TextureView(context)
            mTextureView = textureView
            // Size the TextureView (the child), never this popup view: the popup's own
            // layoutParams carry its screen position, and popup_frame is the first child of
            // the vertical layout — a fixed popup height would push the title/message out and
            // re-centre it. Provisional 16:9 and visible right away so the SurfaceTexture exists
            // before the first frame (a 0-height view never gets one); corrected to the true
            // aspect on onVideoSizeChanged.
            fun sizeTo(w: Int, h: Int) {
                textureView.layoutParams = FrameLayout.LayoutParams(w, h).apply { gravity = Gravity.CENTER }
                mPoster?.layoutParams = FrameLayout.LayoutParams(w, h).apply { gravity = Gravity.CENTER }
                this@Video.visibility = View.VISIBLE
            }
            frame.addView(textureView)
            val provisionalHeight = provisionalVideoHeight(media.width, media.height)
            attachPoster(frame, media.poster,
                FrameLayout.LayoutParams(media.width, provisionalHeight).apply { gravity = Gravity.CENTER }
            ) { pw, ph ->
                posterVideoHeight(media.width, media.height, pw, ph)?.let { sizeTo(media.width, it) }
            }
            sizeTo(media.width, provisionalHeight)

            val isRtsp = uri.startsWith("rtsp://", ignoreCase = true) || uri.startsWith("rtsps://", ignoreCase = true)
            val item = MediaItem.fromUri(uri)
            val source = if (isRtsp)
                RtspMediaSource.Factory().setForceUseRtpTcp(true).createMediaSource(item)
            else
                DefaultMediaSourceFactory(context).createMediaSource(item)

            // Decoder choice (0.17.2). The vendor hardware decoder is the default everywhere,
            // except where it is known to break the TV's own video: on Amlogic SoCs the
            // MediaCodec decoder and the HDMI input share one video layer, and releasing our
            // decoder when the popup closes froze the HDMI picture (Xiaomi projector, Android
            // 6.0.1, OMX.amlogic.avc.decoder.awesome; the TCL on Android 8 was fine). There
            // we prefer the software decoders (OMX.google.* / c2.android.*), which never
            // touch that layer. `softwareDecoder` in the payload overrides the automatic rule.
            val software = media.softwareDecoder ?: autoSoftwareDecoder()
            Log.i(LOG_TAG, "video decoder: ${if (software) "software (preferred)" else "hardware (default)"}" +
                " [auto=${media.softwareDecoder == null}, sdk=${Build.VERSION.SDK_INT}]")
            val renderers = DefaultRenderersFactory(context)
                .setEnableDecoderFallback(true)
                .setMediaCodecSelector(if (software) SOFTWARE_FIRST_SELECTOR else MediaCodecSelector.DEFAULT)

            val builder = ExoPlayer.Builder(context, renderers)
            lowLatencyBufferDurations(media.bufferMs)?.let { (minMs, maxMs, startMs, rebufferMs) ->
                builder.setLoadControl(
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(minMs, maxMs, startMs, rebufferMs)
                        .setPrioritizeTimeOverSizeThresholds(true)
                        .build()
                )
                Log.i(LOG_TAG, "video: low-latency buffer, start ${startMs} ms")
            }
            mExoPlayer = builder.build().apply {
                setVideoTextureView(textureView)
                if (media.muted) {
                    // no audio track at all: avoid grabbing the audio output from whatever plays
                    trackSelectionParameters = trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                        .build()
                }
                addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (videoSize.width > 0 && videoSize.height > 0) {
                            sizeTo(media.width, heightForDisplayAspect(
                                media.width, videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio))
                        }
                    }
                    override fun onRenderedFirstFrame() {
                        onStreamFirstFrame()
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        Log.w(LOG_TAG, "ExoPlayer error for $uri: ${error.errorCodeName}", error)
                    }
                })
                setMediaSource(source)
                playWhenReady = true
                prepare()
            }
        }

        override fun destroy() {
            super.destroy()
            destroyPoster()
            // Orderly teardown: stop decoding, detach the output surface, then release. A bare
            // release() lets the decoder die while still bound to the surface, which is the
            // moment a vendor pipeline (Amlogic) lost sync with the HDMI input.
            try {
                mExoPlayer?.let { player ->
                    runCatching { player.stop() }
                    mTextureView?.let { tv -> runCatching { player.clearVideoTextureView(tv) } }
                    player.release()
                }
            } catch(e: Throwable) {
            } finally {
                mExoPlayer = null
                mTextureView = null
            }
        }

        companion object {
            /// Software decoders first, then the rest as ExoPlayer would order them; only for
            /// video mime types (audio is disabled anyway). With decoder fallback enabled a
            /// failing software decoder still falls through to the hardware one.
            @UnstableApi
            val SOFTWARE_FIRST_SELECTOR = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                val infos = MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                if (MimeTypes.isVideo(mimeType)) infos.sortedBy { if (it.softwareOnly) 0 else 1 } else infos
            }

            /// Automatic rule: software on Android < 8 (old vendor decoders, the projector
            /// class of devices) and wherever an Amlogic decoder is present, hardware elsewhere.
            @UnstableApi
            fun autoSoftwareDecoder(): Boolean {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
                return try {
                    MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_H264, false, false)
                        .any { it.name.contains("amlogic", ignoreCase = true) }
                } catch (e: Throwable) {
                    false
                }
            }
        }
    }

    private class Image(context: Context, popup: PopupProps, val media: PopupProps.Media.Image): PopupView(context, popup) {
        init { create() }

        override fun create() {
            super.create()

            val frame = findViewById<FrameLayout>(R.id.popup_frame)

            try {
                val imageView = ImageView(context)

                val layoutParams =
                    FrameLayout.LayoutParams(media.width, WindowManager.LayoutParams.WRAP_CONTENT).apply {
                        gravity = Gravity.CENTER
                    }

                frame.addView(imageView, layoutParams)

                Glide.with(context)
                    .load(Uri.parse(media.uri))
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .skipMemoryCache(true)
                    .into(imageView)

            } catch(e: Throwable) {
                removeView(frame)
            }
        }
    }

    private class Bitmap(context: Context, popup: PopupProps, val media: PopupProps.Media.Bitmap): PopupView(context, popup) {
        var mImageView: ImageView? = null

        init { create() }

        override fun create() {
            super.create()

            val frame = findViewById<FrameLayout>(R.id.popup_frame)
            mImageView = ImageView(context).apply {
                setImageBitmap(media.image)
            }

            val scaledHeight = ((media.width.toFloat() / media.image.width) * media.image.height).toInt()
            val layoutParams =
                FrameLayout.LayoutParams(media.width, scaledHeight).apply {
                    gravity = Gravity.CENTER
                }

            frame.addView(mImageView, layoutParams)
        }

        override fun destroy() {
            super.destroy()
            try {
                mImageView?.setImageDrawable(null)
                media.image.recycle()
            } catch(e: Throwable) {}
        }
    }

    private class Web(context: Context, popup: PopupProps, val media: PopupProps.Media.Web): PopupView(context, popup) {
        private var mWebView: WebView? = null
        /// The watcher saw a <video> element on the page: "playing" will follow, so the
        /// fallback cap must NOT fade the poster (a still beats a black player shell).
        @Volatile private var mVideoElementFound = false

        init { create() }

        @SuppressLint("SetJavaScriptEnabled")
        override fun create() {
            super.create()

            val frame = findViewById<FrameLayout>(R.id.popup_frame)
            val webView = WebView(context).apply {
                with(settings) {
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    // camera/stream pages (go2rtc, HA) need JS and unattended playback
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    // 2026-10-08: a remote page has no business reading local files or
                    // content providers; both default to true before Android 11.
                    allowFileAccess = false
                    allowContentAccess = false
                }
                webViewClient = object : WebViewClient() {
                    // First visible paint of the page (deliberately NOT onPageFinished:
                    // that never fires for an MJPEG stream). For a plain page (MJPEG,
                    // snapshot) this IS the first image — but a page that hosts a <video>
                    // (go2rtc's WebRTC player) paints its shell seconds before the video
                    // flows, and fading the poster here left a black hole until the
                    // stream connected (0.19.x). So: inject a watcher; it reports
                    // "playing" once a video actually renders frames, or "novideo" when
                    // the page has no video element — and only then the poster fades.
                    override fun onPageCommitVisible(view: WebView?, url: String?) {
                        view?.evaluateJavascript(VIDEO_WATCH_JS, null)
                        // Fallback cap for a page whose watcher never reports anything
                        // (broken/blocked JS). When a <video> element WAS found, the cap
                        // stands down: "playing" will follow, and until then the poster
                        // is strictly better than the player's black shell. Measured on a
                        // TCL: page commit 5.7 s + the old blind 8 s cap fired at 13.7 s,
                        // sometimes just before the video - visible as a black flash.
                        view?.postDelayed({
                            if (!mVideoElementFound) onStreamFirstFrame()
                        }, POSTER_FADE_CAP_MS)
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        // mute every (also dynamically added) media element, so the
                        // page never claims audio focus (audio can stall video on
                        // some Android TV / Fire TV devices)
                        if (media.muted) view?.evaluateJavascript(MUTE_JS, null)
                    }
                }
                // The watcher signals through the document title — no JS bridge needed.
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        when (title) {
                            "pipup:videofound" -> mVideoElementFound = true
                            "pipup:playing", "pipup:novideo" -> onStreamFirstFrame()
                        }
                    }
                }
                // WebView paints white by default; without this a transparent page is white.
                if (media.transparent) setBackgroundColor(Color.TRANSPARENT)
                loadUrl(media.uri)
            }
            mWebView = webView

            val layoutParams = FrameLayout.LayoutParams(
                media.width,
                media.height
            ).apply {
                gravity = Gravity.CENTER
            }

            frame.addView(webView, layoutParams)
            attachPoster(frame, media.poster,
                FrameLayout.LayoutParams(media.width, media.height).apply { gravity = Gravity.CENTER }
            ) { pw, ph ->
                // Give the web view the poster's aspect at the requested width, so still and
                // live stream match (an MJPEG page scales the image to the view width anyway).
                val h = (media.width.toLong() * ph / pw).toInt()
                webView.layoutParams = FrameLayout.LayoutParams(media.width, h).apply { gravity = Gravity.CENTER }
                mPoster?.layoutParams = FrameLayout.LayoutParams(media.width, h).apply { gravity = Gravity.CENTER }
            }
        }

        override fun destroy() {
            super.destroy()
            destroyPoster()
            try {
                mWebView?.apply {
                    loadUrl("about:blank")
                    destroy()
                }
                mWebView = null
            } catch(e: Throwable) {}
        }
    }


    /// WebRTC via WHEP (0.25.0): a small page of our own, loaded with the stream's origin as
    /// base URL (so the SDP POST is same-origin: no CORS), negotiates one recvonly
    /// RTCPeerConnection with the endpoint and plays it in a <video>. Compared with loading
    /// go2rtc's stream.html this skips the page download, its player bundle and the
    /// websocket signalling: one HTTP round trip, and "playing" comes from our own element
    /// instead of a watcher that has to find a <video> in someone else's shadow DOM.
    private class Whep(context: Context, popup: PopupProps, val media: PopupProps.Media.Whep): PopupView(context, popup) {
        private var mWebView: WebView? = null

        init { create() }

        @SuppressLint("SetJavaScriptEnabled")
        override fun create() {
            super.create()

            val frame = findViewById<FrameLayout>(R.id.popup_frame)
            val webView = WebView(context).apply {
                with(settings) {
                    javaScriptEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    // 2026-10-08: as for web media - no local file or content access
                    allowFileAccess = false
                    allowContentAccess = false
                }
                // The page reports through document.title, like the web watcher.
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                        msg?.message()?.let { if (it.startsWith("whep")) Log.i(LOG_TAG, it) }
                        return true
                    }
                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        if (title == null || !title.startsWith("pipup:")) return
                        when {
                            title == "pipup:playing" -> onStreamFirstFrame()
                            title.startsWith("pipup:error:") -> {
                                val msg = title.removePrefix("pipup:error:")
                                Log.w(LOG_TAG, "whep: $msg")
                                try { onMediaError?.invoke(msg) } catch (_: Throwable) {}
                            }
                        }
                    }
                }
                setBackgroundColor(if (media.transparent) Color.TRANSPARENT else Color.BLACK)
                val origin = try {
                    val u = android.net.Uri.parse(splitUrlCredentials(media.uri).first)
                    "${u.scheme}://${u.encodedAuthority}/"
                } catch (_: Throwable) { null }
                loadDataWithBaseURL(origin, whepPage(media), "text/html", "utf-8", null)
            }
            mWebView = webView

            frame.addView(webView, FrameLayout.LayoutParams(media.width, media.height).apply {
                gravity = Gravity.CENTER
            })
            attachPoster(frame, media.poster,
                FrameLayout.LayoutParams(media.width, media.height).apply { gravity = Gravity.CENTER }
            ) { pw, ph ->
                // the camera's aspect, from its snapshot: video and still line up exactly
                val h = (media.width.toLong() * ph / pw).toInt()
                webView.layoutParams = FrameLayout.LayoutParams(media.width, h).apply { gravity = Gravity.CENTER }
                mPoster?.layoutParams = FrameLayout.LayoutParams(media.width, h).apply { gravity = Gravity.CENTER }
            }
        }

        override fun destroy() {
            super.destroy()
            destroyPoster()
            try {
                mWebView?.apply {
                    // closes the peer connection, so go2rtc drops the consumer at once
                    evaluateJavascript("window.__pipupStop && window.__pipupStop()", null)
                    loadUrl("about:blank")
                    destroy()
                }
                mWebView = null
            } catch (_: Throwable) {}
        }
    }

    /// Camera wall (0.29.0): up to [PopupProps.MAX_GRID_TILES] WHEP streams in one popup.
    /// One WebView holds every tile, each with its own recvonly peer connection, the same
    /// negotiation and back-off as [Whep]; a tile's poster is the native `<video poster>`, so
    /// it disappears on that tile's own first frame. The popup reports "playing" (first-frame
    /// time, live badge) on the first tile that plays, and an error with the tile's label.
    private class Grid(context: Context, popup: PopupProps, val media: PopupProps.Media.Grid): PopupView(context, popup) {
        private var mWebView: WebView? = null

        init { create() }

        @SuppressLint("SetJavaScriptEnabled")
        override fun create() {
            super.create()

            val (w, h) = boxSize()
            val frame = findViewById<FrameLayout>(R.id.popup_frame)
            val webView = WebView(context).apply {
                with(settings) {
                    javaScriptEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    allowFileAccess = false
                    allowContentAccess = false
                }
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                        msg?.message()?.let { if (it.startsWith("whep")) Log.i(LOG_TAG, it) }
                        return true
                    }
                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        if (title == null || !title.startsWith("pipup:")) return
                        when {
                            title == "pipup:playing" -> onStreamFirstFrame()
                            title.startsWith("pipup:error:") -> {
                                val msg = title.removePrefix("pipup:error:")
                                Log.w(LOG_TAG, "grid: $msg")
                                try { onMediaError?.invoke(msg) } catch (_: Throwable) {}
                            }
                        }
                    }
                }
                setBackgroundColor(Color.BLACK)
                val origin = try {
                    val u = android.net.Uri.parse(splitUrlCredentials(media.tiles.first().uri).first)
                    "${u.scheme}://${u.encodedAuthority}/"
                } catch (_: Throwable) { null }
                loadDataWithBaseURL(origin, gridPage(media), "text/html", "utf-8", null)
            }
            mWebView = webView
            frame.addView(webView, FrameLayout.LayoutParams(w, h).apply { gravity = Gravity.CENTER })
        }

        /// The grid's pixel size: as given, or the whole screen minus the popup padding.
        private fun boxSize(): Pair<Int, Int> {
            if (!media.fullscreen) return media.width to media.height
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
            val pad = if (popup.mediaFullBleed) 0 else (popup.padding ?: 20)
            return (dm.widthPixels - 2 * pad).coerceAtLeast(1) to (dm.heightPixels - 2 * pad).coerceAtLeast(1)
        }

        override fun destroy() {
            super.destroy()
            try {
                mWebView?.apply {
                    // closes every peer connection, so go2rtc drops the consumers at once
                    evaluateJavascript("window.__pipupStop && window.__pipupStop()", null)
                    loadUrl("about:blank")
                    destroy()
                }
                mWebView = null
            } catch (_: Throwable) {}
        }
    }

    companion object {
        const val LOG_TAG = "PopupView"

        /// The WHEP player page. The endpoint URL goes in as a JSON string literal
        /// (JSONObject.quote), never spliced raw into the script. Credentials in the URL
        /// (http://user:pass@host:1984/...) are split off and sent as a Basic Authorization
        /// header, because fetch() refuses a URL that carries them.
        fun whepPage(media: PopupProps.Media.Whep): String {
            val (url, credentials) = splitUrlCredentials(media.uri)
            return WHEP_PAGE
                .replace("__URL__", org.json.JSONObject.quote(url))
                .replace("__CREDENTIALS__", credentials?.let { org.json.JSONObject.quote(it) } ?: "null")
                .replace("__MUTED__", if (media.muted) "true" else "false")
                .replace("__BG__", if (media.transparent) "transparent" else "#000")
        }

        /// The camera-wall page (0.29.0). The tiles go in as one JSON array (JSONArray
        /// quoting), never spliced raw into the script. Credentials in a tile's URL are sent
        /// as a Basic Authorization header, as in [whepPage].
        fun gridPage(media: PopupProps.Media.Grid): String {
            val tiles = org.json.JSONArray()
            media.tiles.forEach { t ->
                val (url, credentials) = splitUrlCredentials(t.uri)
                tiles.put(org.json.JSONObject().apply {
                    put("uri", url)
                    put("credentials", credentials ?: org.json.JSONObject.NULL)
                    put("label", if (media.labels) (t.label ?: "") else "")
                    put("poster", t.poster ?: "")
                })
            }
            val cols = media.columnCount
            val rows = (media.tiles.size + cols - 1) / cols
            return GRID_PAGE
                .replace("__TILES__", tiles.toString())
                .replace("__MUTED__", if (media.muted) "true" else "false")
                .replace("__COLS__", cols.toString())
                .replace("__ROWS__", rows.coerceAtLeast(1).toString())
                .replace("__GAP__", media.gap.coerceIn(0, 64).toString())
        }

        /// One recvonly peer connection per tile, each with the [WHEP_PAGE] negotiation and
        /// its own back-off, so one camera that is down does not hold up the rest.
        const val GRID_PAGE = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;padding:0;width:100%;height:100%;overflow:hidden;background:#000}
#g{display:grid;width:100%;height:100%;box-sizing:border-box;gap:__GAP__px;
grid-template-columns:repeat(__COLS__,1fr);grid-template-rows:repeat(__ROWS__,1fr)}
.t{position:relative;overflow:hidden;background:#111;min-width:0;min-height:0}
.t video{width:100%;height:100%;object-fit:contain;background:#111}
.t span{position:absolute;left:8px;bottom:6px;padding:2px 8px;border-radius:4px;
font:500 15px sans-serif;color:#fff;background:rgba(0,0,0,.55)}
.t span:empty{display:none}</style></head>
<body><div id="g"></div><script>
(function(){
  var TILES = __TILES__, MUTED = __MUTED__, g = document.getElementById('g');
  var stopped = false, anyPlaying = false, stops = [];
  var T0 = Date.now(); function lg(m){ console.log('whep grid +' + (Date.now() - T0) + 'ms ' + m); }
  function title(t){ document.title = 'pipup:' + t; }
  function basic(c){ var b = new TextEncoder().encode(c), s = ''; for (var i = 0; i < b.length; i++) s += String.fromCharCode(b[i]); return btoa(s); }
  window.__pipupStop = function(){ stopped = true; stops.forEach(function(f){ try { f(); } catch(e) {} }); };
  TILES.forEach(function(t, i){
    var box = document.createElement('div'); box.className = 't';
    var v = document.createElement('video'); v.autoplay = true; v.playsInline = true; v.muted = MUTED;
    if (t.poster) v.poster = t.poster;
    var cap = document.createElement('span'); cap.textContent = t.label || '';
    box.appendChild(v); box.appendChild(cap); g.appendChild(box);
    var name = t.label || ('tile ' + (i + 1));
    var pc = null, attempt = 0, timer = null;
    function err(m){ title('error:' + (name + ': ' + String(m)).slice(0, 120)); }
    function close(){ try { if (pc) pc.close(); } catch(e) {} pc = null; }
    function retry(){
      if (stopped || timer) return;
      var wait = Math.min(8000, 1000 * Math.pow(2, attempt++));
      timer = setTimeout(function(){ timer = null; connect(); }, wait);
    }
    stops.push(function(){ if (timer) clearTimeout(timer); close(); });
    v.addEventListener('playing', function(){
      attempt = 0; lg(name + ' playing');
      if (!anyPlaying){ anyPlaying = true; title('playing'); }
    });
    function gathered(p){
      return new Promise(function(res){
        if (p.iceGatheringState === 'complete') return res();
        var to = setTimeout(res, 1000);
        p.addEventListener('icegatheringstatechange', function(){
          if (p.iceGatheringState === 'complete'){ clearTimeout(to); res(); }
        });
      });
    }
    function connect(){
      if (stopped) return;
      if (!window.RTCPeerConnection){ err('no WebRTC in this WebView'); return; }
      close();
      var p = pc = new RTCPeerConnection({ iceServers: [] });
      p.addTransceiver('video', { direction: 'recvonly' });
      if (!MUTED) p.addTransceiver('audio', { direction: 'recvonly' });
      p.ontrack = function(e){
        if (v.srcObject !== e.streams[0]) v.srcObject = e.streams[0] || new MediaStream([e.track]);
        var pr = v.play(); if (pr && pr.catch) pr.catch(function(){});
      };
      p.onconnectionstatechange = function(){
        if (p !== pc) return;
        var s = p.connectionState;
        if (s === 'failed' || s === 'closed' || s === 'disconnected'){ err('connection ' + s); retry(); }
      };
      p.createOffer().then(function(o){ return p.setLocalDescription(o); })
        .then(function(){ return gathered(p); })
        .then(function(){
          var headers = { 'Content-Type': 'application/sdp' };
          if (t.credentials) headers['Authorization'] = 'Basic ' + basic(t.credentials);
          return fetch(t.uri, { method: 'POST', headers: headers, body: p.localDescription.sdp });
        })
        .then(function(r){ if (!r.ok) throw new Error('HTTP ' + r.status); return r.text(); })
        .then(function(sdp){ if (p !== pc) return; return p.setRemoteDescription({ type: 'answer', sdp: sdp }); })
        .catch(function(e){ if (p === pc){ err(e && e.message ? e.message : e); retry(); } });
    }
    connect();
  });
})();
</script></body></html>"""

        /// Recvonly WebRTC against a WHEP endpoint. No STUN: on the LAN the endpoint's host
        /// candidates are enough, and gathering waits at most 1 s anyway. Retries with backoff
        /// (1, 2, 4, then every 8 s) on a failed POST or a connection that drops, so a camera
        /// that is still waking up, or a go2rtc restart, heals by itself.
        const val WHEP_PAGE = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;padding:0;width:100%;height:100%;overflow:hidden;background:__BG__}
video{width:100%;height:100%;object-fit:contain;background:__BG__}</style></head>
<body><video id="v" autoplay playsinline></video><script>
(function(){
  var URL_ = __URL__, CREDENTIALS = __CREDENTIALS__, MUTED = __MUTED__;
  var v = document.getElementById('v'); v.muted = MUTED;
  var pc = null, stopped = false, attempt = 0, playing = false, timer = null;
  var T0 = Date.now(); function lg(m){ console.log('whep +' + (Date.now() - T0) + 'ms ' + m); }
  function title(t){ document.title = 'pipup:' + t; }
  function basic(c){ var b = new TextEncoder().encode(c), s = ''; for (var i = 0; i < b.length; i++) s += String.fromCharCode(b[i]); return btoa(s); }
  function err(m){ title('error:' + String(m).slice(0, 120)); }
  function retry(){
    if (stopped || timer) return;
    var wait = Math.min(8000, 1000 * Math.pow(2, attempt++));
    timer = setTimeout(function(){ timer = null; connect(); }, wait);
  }
  function close(){ try { if (pc) pc.close(); } catch(e) {} pc = null; }
  window.__pipupStop = function(){ stopped = true; if (timer) clearTimeout(timer); close(); };
  v.addEventListener('playing', function(){ if (!playing){ playing = true; attempt = 0; title('playing'); } });
  function gathered(p){
    return new Promise(function(res){
      if (p.iceGatheringState === 'complete') return res();
      var t = setTimeout(res, 1000);
      p.addEventListener('icegatheringstatechange', function(){
        if (p.iceGatheringState === 'complete'){ clearTimeout(t); res(); }
      });
    });
  }
  function connect(){
    if (stopped) return;
    if (!window.RTCPeerConnection){ err('no WebRTC in this WebView'); return; }
    close();
    var p = pc = new RTCPeerConnection({ iceServers: [] });
    p.addTransceiver('video', { direction: 'recvonly' });
    if (!MUTED) p.addTransceiver('audio', { direction: 'recvonly' });
    p.ontrack = function(e){
      lg('track ' + e.track.kind);
      if (v.srcObject !== e.streams[0]) v.srcObject = e.streams[0] || new MediaStream([e.track]);
      var pr = v.play(); if (pr && pr.catch) pr.catch(function(){});
    };
    p.onconnectionstatechange = function(){
      if (p !== pc) return;
      var s = p.connectionState;
      if (s === 'failed' || s === 'closed' || s === 'disconnected'){
        err('connection ' + s); playing = false; retry();
      }
    };
    lg('connect');
    p.oniceconnectionstatechange = function(){ lg('ice ' + p.iceConnectionState); };
    v.addEventListener('loadedmetadata', function(){ lg('loadedmetadata'); });
    v.addEventListener('playing', function(){ lg('playing'); });
    p.createOffer().then(function(o){ lg('offer'); return p.setLocalDescription(o); })
      .then(function(){ return gathered(p); })
      .then(function(){ lg('gathered ' + p.iceGatheringState); })
      .then(function(){
        var headers = { 'Content-Type': 'application/sdp' };
        if (CREDENTIALS) headers['Authorization'] = 'Basic ' + basic(CREDENTIALS);
        return fetch(URL_, { method: 'POST', headers: headers,
                             body: p.localDescription.sdp });
      })
      .then(function(r){
        lg('answer HTTP ' + r.status);
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.text();
      })
      .then(function(sdp){
        if (p !== pc) return;
        return p.setRemoteDescription({ type: 'answer', sdp: sdp });
      })
      .catch(function(e){ if (p === pc){ err(e && e.message ? e.message : e); retry(); } });
  }
  connect();
})();
</script></body></html>"""

        /// Bright fill shown on the focused popup button. White text stays readable on it.
        val DEFAULT_BUTTON_FOCUS_COLOR = Color.parseColor("#2979FF")

        /// Live badge fill: a warm amber, readable under white text.
        const val DEFAULT_LIVE_BADGE_COLOR = "#FFA500"

        /// Focus-aware button background: a bright fill when focused, a subtle
        /// translucent one otherwise — so D-pad navigation is visible in an overlay
        /// window, where the platform button carries no focus state of its own.
        /// Every part can be overridden per popup; the defaults are the classic look.
        fun buttonBackground(style: ButtonStyle = ButtonStyle()): StateListDrawable {
            fun pill(fill: Int, stroke: Int) = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = style.cornerRadius
                setColor(fill)
                setStroke(2, stroke)
            }
            val focused = pill(style.focusColor, style.focusBorderColor)
            val normal = pill(style.color, style.borderColor)
            return StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focused)
                addState(intArrayOf(android.R.attr.state_pressed), focused)
                addState(intArrayOf(), normal)
            }
        }

        /// A malformed color must never take the whole popup down: an unparseable
        /// value falls back instead of throwing out of create().
        fun parseColorOrDefault(value: String?, fallback: String): Int = try {
            Color.parseColor(value)
        } catch (_: Throwable) {
            Log.w(LOG_TAG, "Unparseable color '$value', using $fallback")
            Color.parseColor(fallback)
        }

        /// Fallback fade for a page whose watcher never reports anything at all
        /// (broken or blocked JS). A page with a discovered <video> is exempt: its
        /// poster stays until the video actually plays, however long that takes.
        const val POSTER_FADE_CAP_MS = 20_000L

        /// Reports through document.title: "pipup:playing" as soon as any <video>
        /// renders frames, or "pipup:novideo" when no video element shows up shortly
        /// after the first paint (an MJPEG/snapshot page - its paint IS the image).
        const val VIDEO_WATCH_JS = """
            (function() {
                if (window.__pipupWatch) return;
                window.__pipupWatch = true;
                var done = false;
                function signal(what) {
                    if (done) return;
                    done = true;
                    document.title = 'pipup:' + what;
                }
                function hook(v) {
                    if (v.__pipupHooked) return;
                    v.__pipupHooked = true;
                    if (!done) document.title = 'pipup:videofound';
                    if (!v.paused && v.currentTime > 0 && v.readyState >= 2) { signal('playing'); return; }
                    ['playing', 'timeupdate', 'loadeddata'].forEach(function(ev) {
                        v.addEventListener(ev, function() {
                            if (v.currentTime > 0 || ev === 'playing') signal('playing');
                        });
                    });
                }
                function scan(root) {
                    (root.querySelectorAll ? root.querySelectorAll('video') : []).forEach(hook);
                    // go2rtc's video-rtc element hides its <video> in a shadow root
                    (root.querySelectorAll ? root.querySelectorAll('*') : []).forEach(function(el) {
                        if (el.shadowRoot) scan(el.shadowRoot);
                    });
                }
                scan(document);
                new MutationObserver(function() { scan(document); }).observe(
                    document.documentElement, { childList: true, subtree: true });
                setTimeout(function() {
                    if (!done && document.querySelectorAll('video').length === 0) {
                        var shadowVideo = false;
                        try {
                            document.querySelectorAll('*').forEach(function(el) {
                                if (el.shadowRoot && el.shadowRoot.querySelector('video')) shadowVideo = true;
                            });
                        } catch (e) {}
                        if (!shadowVideo) signal('novideo');
                    }
                }, 400);
            })();
        """

        const val MUTE_JS = """
            (function() {
                function muteAll() {
                    document.querySelectorAll('video,audio').forEach(function(m) {
                        m.muted = true;
                        m.volume = 0;
                    });
                }
                muteAll();
                new MutationObserver(muteAll).observe(document.documentElement, { childList: true, subtree: true });
            })();
        """

        fun build(context: Context, popup: PopupProps): PopupView
        {
            return when (popup.media) {
                is PopupProps.Media.Web -> Web(context, popup, popup.media)
                is PopupProps.Media.Whep -> Whep(context, popup, popup.media)
                is PopupProps.Media.Grid ->
                    if (popup.media.tiles.isEmpty()) Default(context, popup) else Grid(context, popup, popup.media)
                is PopupProps.Media.Video -> Video(context, popup, popup.media)
                is PopupProps.Media.Image -> Image(context, popup, popup.media)
                is PopupProps.Media.Bitmap -> Bitmap(context, popup, popup.media)
                else -> Default(context, popup)
            }
        }
    }
}
/// Splits credentials off a URL: `http://user:pass@host:1984/api/webrtc?src=x` gives
/// (`http://host:1984/api/webrtc?src=x`, `user:pass`), percent-decoded so a password can
/// carry reserved characters. A URL without credentials comes back unchanged, with null.
internal fun splitUrlCredentials(url: String): Pair<String, String?> {
    val start = url.indexOf("://").let { if (it < 0) return url to null else it + 3 }
    val end = url.indexOfAny(charArrayOf('/', '?', '#'), start).let { if (it < 0) url.length else it }
    val at = url.lastIndexOf('@', end - 1)
    if (at < start) return url to null
    val credentials = java.net.URLDecoder.decode(url.substring(start, at).replace("+", "%2B"), "UTF-8")
    return url.substring(0, start) + url.substring(at + 1) to credentials
}

/// Popup video height for [targetWidth] that keeps the stream's *display* aspect.
/// Stored width/height alone is the wrong shape for anamorphic streams: NVR substreams
/// commonly send 640x360 or 704x480 with a non-square sample aspect (e.g. 3:4 for a 4:3
/// picture), which media3 reports as [pixelWidthHeightRatio]. Ignoring it squashes the image.
internal fun heightForDisplayAspect(targetWidth: Int, width: Int, height: Int, pixelWidthHeightRatio: Float): Int {
    val ratio = if (pixelWidthHeightRatio > 0f) pixelWidthHeightRatio else 1f
    val displayWidth = width * ratio
    return Math.round(targetWidth * height / displayWidth)
}

/// ExoPlayer buffer durations for `bufferMs` (#79), as min, max, start, start-after-rebuffer
/// in ms, or null for ExoPlayer's defaults. Playback starts once [bufferMs] is buffered and
/// resumes after a stall at twice that; a live source can't run ahead of real time, so the
/// buffer - and the delay behind the camera - stays near [bufferMs]. Clamped to 100..10000 ms.
/// The order satisfies DefaultLoadControl's checks: max >= min >= start and >= rebuffer.
internal fun lowLatencyBufferDurations(bufferMs: Int?): List<Int>? {
    val start = bufferMs?.coerceIn(100, 10_000) ?: return null
    val rebuffer = start * 2
    val min = maxOf(rebuffer, 1_000)
    return listOf(min, maxOf(min * 2, 5_000), start, rebuffer)
}

/// Stream-area height before the first frame: an explicit [requestedHeight], else 16:9.
internal fun provisionalVideoHeight(width: Int, requestedHeight: Int?): Int =
    requestedHeight?.takeIf { it > 0 } ?: (width * 9 / 16)

/// Height to resize the stream area to once the poster has decoded, or null to keep the box.
/// With an explicit [requestedHeight] the box stays put: a camera snapshot can be anamorphic
/// (an NVR's 704x480 still of a 4:3 picture), so its pixel size is not the stream's shape.
internal fun posterVideoHeight(width: Int, requestedHeight: Int?, posterWidth: Int, posterHeight: Int): Int? =
    if (requestedHeight != null && requestedHeight > 0) null
    else (width.toLong() * posterHeight / posterWidth).toInt()

/// Resolved popup-button colors and shape; the defaults reproduce the classic look.
/// A configured border color applies to both states; otherwise the focused button keeps
/// its classic white outline and the unfocused one its translucent white.
data class ButtonStyle(
    val color: Int = Color.parseColor("#33FFFFFF"),
    val focusColor: Int = PopupView.DEFAULT_BUTTON_FOCUS_COLOR,
    val borderColor: Int = Color.parseColor("#66FFFFFF"),
    val focusBorderColor: Int = Color.WHITE,
    val cornerRadius: Float = 12f,
) {
    companion object {
        fun from(popup: PopupProps): ButtonStyle {
            val classic = ButtonStyle()
            val border = popup.buttonBorderColor?.let { PopupView.parseColorOrDefault(it, "#66FFFFFF") }
            return ButtonStyle(
                color = popup.buttonColor?.let { PopupView.parseColorOrDefault(it, "#33FFFFFF") } ?: classic.color,
                focusColor = popup.buttonFocusColor?.let { PopupView.parseColorOrDefault(it, "#2979FF") } ?: classic.focusColor,
                borderColor = border ?: classic.borderColor,
                focusBorderColor = border ?: classic.focusBorderColor,
                cornerRadius = popup.buttonCornerRadius?.takeIf { it >= 0f } ?: classic.cornerRadius,
            )
        }
    }
}

/// Corner of the stream area for the live badge; unknown or missing values mean bottom right.
internal fun liveBadgeGravity(position: String?): Int = when (position) {
    "top_left" -> Gravity.TOP or Gravity.START
    "top_right" -> Gravity.TOP or Gravity.END
    "bottom_left" -> Gravity.BOTTOM or Gravity.START
    else -> Gravity.BOTTOM or Gravity.END
}
