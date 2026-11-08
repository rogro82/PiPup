package nl.rogro82.pipup

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

@JsonIgnoreProperties(ignoreUnknown = true)
data class PopupProps(
    val duration: Int = DEFAULT_DURATION, // seconds; 0 or negative = show until /cancel or replaced
    val id: String? = null,               // optional identifier: each id is its own popup window (0.24.0); popups without an id share one. Same id and content only reschedules the timer, /cancel?id= removes that popup
    val position: Position = DEFAULT_POSITION,
    val backgroundColor: String = DEFAULT_BACKGROUND_COLOR,
    val title: String? = null,
    val titleSize: Float = DEFAULT_TITLE_SIZE,
    val titleColor: String = DEFAULT_TITLE_COLOR,
    val message: String? = null,
    val messageSize: Float = DEFAULT_MESSAGE_SIZE,
    val messageColor: String = DEFAULT_MESSAGE_COLOR,
    val media: Media? = null,
    // Optional icon shown beside the title/message block (image URL, loaded like media).
    val icon: String? = null,
    val iconPosition: String? = null,     // "left" (default) or "right"
    val iconWidth: Int = DEFAULT_ICON_WIDTH, // pixels
    val tts: String? = null,              // optional text spoken on the device when the popup is (re)shown
    val ttsLanguage: String? = null,      // optional BCP-47 tag (e.g. "nl-NL"); device default when omitted
    val urgency: String? = null,          // info | warning | critical: colored border preset
    // Explicit border styling; each field overrides the urgency preset on its own, so
    // `urgency` keeps working as a shorthand. Sizes are in pixels, like every other
    // dimension in this API (media width, padding) - see the px/dp TODO in PopupView.
    val borderColor: String? = null,      // #RRGGBB or #AARRGGBB
    val borderWidth: Int? = null,         // 0 = no border, also to switch an urgency border off
    val cornerRadius: Float? = null,      // 0 = square corners
    val showProgress: Boolean = false,    // countdown bar for popups with a finite duration
    val buttons: List<Button> = emptyList(), // DPAD-focusable buttons; pressing one POSTs to callback and dismisses
    val callback: String? = null,         // URL that receives {"popup","button","device"} on a button press
    // 0.18.0: end an active screensaver (DreamService / ambient mode) before showing the popup,
    // so it is seen on every Android version - on some builds the dream layer sits above app
    // overlays, and Android 12+ lets the dream hide them altogether. false keeps the screensaver.
    val dismissScreensaver: Boolean = true,
    // 0.18.0: optional notification sound when a popup is (newly) shown: "default" = built-in
    // chime, or a URL to an audio clip. Not replayed on an update-in-place of the same popup.
    val sound: String? = null,
    val soundVolume: Float? = null,       // 0..1; device notification volume when omitted
    // 0.19.0: button text size in sp (padding scales along); null = the classic look
    val buttonSize: Float? = null,
    // Button style, each null = the classic look. Colors are #RRGGBB or #AARRGGBB.
    val buttonColor: String? = null,        // fill of an unfocused button
    val buttonFocusColor: String? = null,   // fill of the focused (or pressed) button
    val buttonTextColor: String? = null,
    val buttonBorderColor: String? = null,  // outline, both states
    val buttonCornerRadius: Float? = null,  // px; 0 = square corners
    // 0.19.1: outer padding of the popup in px (the black margin around content);
    // null = the classic 20 px. 0 gives a near-borderless look.
    val padding: Int? = null,
    // 0.19.0: entrance animation (and exit on natural expiry): fade, slide_left,
    // slide_right, slide_top, slide_bottom; null/none = appear instantly (classic).
    val animation: String? = null,
    // 0.24.0: whole-popup opacity 0..1 (View alpha), so live TV shows through the
    // popup and its media. null = fully opaque (classic).
    val opacity: Float? = null,
    // 0.24.0: a redraw of a popup already on screen (same id, new content) normally keeps
    // its place in the stack; true opens it on top of the other popups instead.
    val bringToFront: Boolean = false,
    // Live badge: a "Live" pill over a stream popup's video, shown once the first frame
    // renders (never over the poster). liveBadgeColor is #RRGGBB/#AARRGGBB (default amber);
    // liveBadgePosition is top_left, top_right, bottom_left or bottom_right (default).
    val liveBadge: Boolean = false,
    val liveBadgeColor: String? = null,
    val liveBadgePosition: String? = null,
    // Full-bleed media: the video/image runs edge to edge across the top of the popup,
    // its top corners rounded with the popup's; title, message and buttons keep the padding.
    val mediaFullBleed: Boolean = false,
    // Inline buttons: the buttons sit at the end of the title/message row instead of on a
    // row of their own below it, e.g. a single compact "✕".
    val buttonsInline: Boolean = false,
    // 0.27.0 (#64): line up with the other stacking popups at the same position instead of
    // overlapping them: "vertical" (away from the top/bottom edge; down from the centre for
    // center_left/right) or "horizontal" (away from the left/right edge; rightwards from the
    // centre for top/bottom_center). null/"none" = the fixed position (classic). Center never stacks.
    val stack: String? = null,
    // 0.29.1 (#73): the popup window covers the whole display, status and navigation bars
    // included, instead of being laid out inside them; its content is centred. For a frameless
    // full-screen page give `padding: 0`, `borderWidth: 0` and media sized to the screen.
    // Touch keeps working for button-less popups. A grid with `fullscreen` implies this.
    val fullscreen: Boolean = false
) {
    /// Whole-display window: asked for directly, or implied by a full-screen camera wall.
    val coversScreen: Boolean
        get() = fullscreen || (media as? Media.Grid)?.fullscreen == true

    val indefinite: Boolean
        get() = duration <= 0

    /// Stack axis, or null when this popup keeps its fixed position.
    val stackAxis: StackAxis?
        get() = when (stack?.lowercase()) {
            "vertical" -> StackAxis.Vertical
            "horizontal" -> StackAxis.Horizontal
            else -> null
        }?.takeIf { position != Position.Center && !coversScreen }

    enum class StackAxis { Vertical, Horizontal }

    /// equal except for duration, tts, sound, stacking and callback: safe to keep the existing
    /// view and only reschedule removal. `callback` is left out (2026-10-08) because the HA
    /// integration issues a fresh single-use token URL on every show, so comparing it made
    /// every re-send rebuild the view; the button handler reads the callback from the props
    /// on screen, which the update-in-place path replaces. Buttons themselves still count.
    fun sameContent(other: PopupProps): Boolean =
        copy(duration = 0, tts = null, ttsLanguage = null, sound = null, soundVolume = null, bringToFront = false, callback = null) ==
                other.copy(duration = 0, tts = null, ttsLanguage = null, sound = null, soundVolume = null, bringToFront = false, callback = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Button(val id: String, val label: String)

    @JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.WRAPPER_OBJECT)
    @JsonSubTypes(
        JsonSubTypes.Type(Media.Video::class, name = "video"),
        JsonSubTypes.Type(Media.Image::class, name = "image"),
        JsonSubTypes.Type(Media.Web::class, name = "web"),
        JsonSubTypes.Type(Media.Whep::class, name = "whep"),
        JsonSubTypes.Type(Media.Grid::class, name = "grid")
    )
    sealed class Media {
        @JsonIgnoreProperties(ignoreUnknown = true)
        // `poster` (optional, video + web): URL of a still image shown immediately over the
        // stream area and faded out on the first rendered frame, so a live popup never opens
        // as an empty box while the stream connects (RTSP handshake, WebView start-up).
        // `softwareDecoder` (optional, video): true = decode in software (bypass the vendor
        // hardware decoder), false = always hardware, null/absent = automatic (software on
        // Android < 8 and on Amlogic SoCs, whose hardware decoder shares the video layer with
        // the HDMI input and freezes it when released - reported on a Xiaomi projector).
        // height: optional explicit box height. When set, the stream area (and its poster)
        // uses it until the first frame reports the stream's display aspect; without it the
        // box takes the poster's aspect, which is wrong for anamorphic NVR snapshots.
        // `bufferMs` (optional, video, 0.31.0, #79): low-latency playback of a live stream.
        // ExoPlayer's default load control waits for 2.5 s of media before it starts, which
        // puts a live RTSP stream (intercom, doorbell talk) 3-4 s behind real time; a live
        // source can't run ahead, so that buffer stays for the whole popup. With bufferMs set,
        // playback starts once that much is buffered and the buffer stays near it.
        // null/absent = ExoPlayer defaults. Meant for RTSP; with HLS the playlist sets the delay.
        data class Video(val uri: String, val width: Int = DEFAULT_MEDIA_WIDTH, val muted: Boolean = false,
                         val poster: String? = null, val softwareDecoder: Boolean? = null,
                         val height: Int? = null, val bufferMs: Int? = null): Media()
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Image(val uri: String, val width: Int = DEFAULT_MEDIA_WIDTH): Media()
        @JsonIgnoreProperties(ignoreUnknown = true)
        // transparent (0.24.0): the WebView paints no background of its own, so a page
        // whose html/body background is transparent shows the TV picture behind it.
        data class Web(val uri: String, val width: Int = 640, val height: Int = 480, val muted: Boolean = false,
                       val poster: String? = null, val transparent: Boolean = false): Media()
        @JsonIgnoreProperties(ignoreUnknown = true)
        // whep (0.25.0): a WebRTC stream played straight from a WHEP endpoint (e.g. go2rtc's
        // http://host:1984/api/webrtc?src=<camera>), without loading the server's player page.
        // `poster` as for web; `transparent` lets letterbox bars show the TV behind.
        data class Whep(val uri: String, val width: Int = 640, val height: Int = 360, val muted: Boolean = true,
                        val poster: String? = null, val transparent: Boolean = false): Media()
        @JsonIgnoreProperties(ignoreUnknown = true)
        // grid (0.29.0): a camera wall - up to MAX_GRID_TILES WHEP streams in one popup, one
        // WebView with a peer connection per tile. `width`/`height` are the whole grid;
        // `fullscreen` makes it fill the screen (minus the popup padding: give `padding: 0`
        // for edge to edge). `columns` null = automatic (1, 2, 2, 2, 3, 3, 3, 3, 3 for 1..9
        // tiles). All tiles must come from the same server as the first one: the page is
        // loaded with that origin so the SDP POSTs are same-origin (go2rtc sends no CORS
        // headers by default).
        data class Grid(val items: List<GridItem> = emptyList(), val columns: Int? = null,
                        val width: Int = 1280, val height: Int = 720, val gap: Int = 4,
                        val muted: Boolean = true, val labels: Boolean = true,
                        val fullscreen: Boolean = false): Media() {
            /// at most MAX_GRID_TILES, empty URIs dropped
            val tiles: List<GridItem> get() = items.filter { it.uri.isNotBlank() }.take(MAX_GRID_TILES)
            val columnCount: Int get() = gridColumns(tiles.size, columns)
        }
        @JsonIgnoreProperties(ignoreUnknown = true)
        // `poster`: still shown in the tile until its stream plays; `label`: caption in the tile
        data class GridItem(val uri: String = "", val label: String? = null, val poster: String? = null)
        data class Bitmap(val image: android.graphics.Bitmap, val width: Int = DEFAULT_MEDIA_WIDTH): Media()
    }

    // NB: the multipart parser and the ha-pipup integration both map an integer
    // position onto this enum by ORDINAL (values()[n]) — the declaration order is
    // therefore a contract. Do not reorder; append new positions at the end only.
    enum class Position {
        TopRight,   // 0
        TopLeft,    // 1
        BottomRight, // 2
        BottomLeft, // 3
        Center,     // 4
        TopCenter,  // 5
        BottomCenter, // 6
        CenterLeft,  // 7 (since 0.26.0, #62)
        CenterRight  // 8 (since 0.26.0, #62)
    }

    companion object {
        const val DEFAULT_DURATION: Int = 30
        const val DEFAULT_BACKGROUND_COLOR = "#CC000000"
        const val DEFAULT_TITLE_SIZE = 16f
        const val DEFAULT_TITLE_COLOR = "#ffffff"
        const val DEFAULT_MESSAGE_SIZE = 12f
        const val DEFAULT_MESSAGE_COLOR = "#ffffff"
        const val DEFAULT_MEDIA_WIDTH = 480
        const val DEFAULT_ICON_WIDTH = 96           // px; icon beside the title/message
        const val DEFAULT_BORDER_WIDTH = 4          // a borderColor without a borderWidth
        const val DEFAULT_BORDER_COLOR = "#ffffff"  // a borderWidth without a borderColor
        const val DEFAULT_CORNER_RADIUS = 8f        // whenever a border is drawn
        const val MAX_GRID_TILES = 9                // grid media: a 3x3 wall at most

        val DEFAULT_POSITION: Position = Position.TopRight

        /// urgency shorthand -> border width (px) and color
        val URGENCY_PRESETS: Map<String, Pair<Int, String>> = mapOf(
            "info" to (4 to "#2196F3"),
            "warning" to (6 to "#FF9800"),
            "critical" to (8 to "#F44336")
        )
    }
}

/// Grid columns for [tiles] tiles: the requested number (clamped to 1..tiles), or the
/// squarest layout that fills a 16:9 screen - 1, 2, 2, 2, 3, 3, 3, 3, 3 for 1..9 tiles.
internal fun gridColumns(tiles: Int, requested: Int?): Int {
    if (tiles <= 1) return 1
    requested?.let { return it.coerceIn(1, tiles) }
    return if (tiles <= 4) 2 else 3
}
