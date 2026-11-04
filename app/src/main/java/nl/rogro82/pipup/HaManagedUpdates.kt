package nl.rogro82.pipup

/// 0.30.1 (#76): whether Home Assistant installs this app's updates by itself
/// (ha-pipup >= 1.30.2 with automatic updates on). ha-pipup says so on every request in
/// the `X-HA-PiPup-Auto-Update` header (`on` / `off`), and its /state heartbeat comes
/// at least once a minute while the TV is reachable.
///
/// While it is on, the app does not announce a new release with its own Install popup:
/// that popup takes the remote's focus, so an OK meant for the app being watched
/// started an install halfway through a film. The time of the last `on` is kept in the
/// prefs, so it survives a restart, and it runs out after a week without a word from
/// Home Assistant: an integration that is removed or stops running brings the popup
/// back by itself.
object HaManagedUpdates {
    /// How long one `on` counts without a new one.
    const val TTL_MS = 7L * 24 * 60 * 60 * 1000

    /// The stored time is rewritten at most this often, as the header comes every minute.
    const val REFRESH_MS = 60L * 60 * 1000

    /// Header value: true for `on`, false for `off`, null for anything else (no change).
    fun parse(value: String?): Boolean? = when (value?.trim()?.lowercase()) {
        "on" -> true
        "off" -> false
        else -> null
    }

    /// Whether the time stored with the last `on` should be rewritten now. A clock that
    /// went backwards counts as stale, so a time in the future cannot linger.
    fun needsRefresh(lastOnMs: Long?, nowMs: Long): Boolean =
        lastOnMs == null || nowMs < lastOnMs || nowMs - lastOnMs >= REFRESH_MS

    /// Whether Home Assistant still installs the updates.
    fun active(lastOnMs: Long?, nowMs: Long): Boolean =
        lastOnMs != null && nowMs - lastOnMs < TTL_MS
}
