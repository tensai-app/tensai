package io.github.tensaiapp.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the user answered to an Android permission dialog (TEN-186). */
enum class PermissionResult {
    Granted,

    /** Refused, and Android will ask again next time. */
    Denied,

    /** Refused for good: Android shows no dialog any more, only the app settings can grant it. */
    PermanentlyDenied,
    ;

    companion object {
        /**
         * The result once the dialog closed. Android 11+ answers a dismissed first dialog (tap outside, back) like a
         * permanent refusal, so "no rationale after" alone proves nothing: it is permanent only when the user had
         * already refused before ([rationaleBefore], or [askedBefore] from a stored flag) and Android now shows no rationale.
         */
        fun of(granted: Boolean, rationaleBefore: Boolean, rationaleAfter: Boolean, askedBefore: Boolean): PermissionResult = when {
            granted -> Granted
            !rationaleAfter && (rationaleBefore || askedBefore) -> PermanentlyDenied
            else -> Denied
        }
    }
}

/** A permission the app is waiting on, see [PermissionGate.pending]. */
class PermissionRequest internal constructor(val id: Long, val permission: String, internal val reply: CompletableDeferred<PermissionResult>)

/**
 * Where a tool waits for an Android permission dialog (TEN-186), the sibling of [AskUserGate]: [request] publishes
 * the permission in [pending], the activity (the only one that can show the dialog) launches it and answers with
 * [complete], and the call suspends until then. One dialog at a time, requests queue.
 *
 * Stop cancels the turn, which cancels the suspended [request] and drops it from [pending].
 */
class PermissionGate {
    private val queue = Mutex()
    private val _pending = MutableStateFlow<PermissionRequest?>(null)
    private var nextId = 0L

    /** The permission to ask for now, `null` when none. */
    val pending: StateFlow<PermissionRequest?> = _pending.asStateFlow()

    suspend fun request(permission: String): PermissionResult = queue.withLock {
        val request = PermissionRequest(++nextId, permission, CompletableDeferred())
        _pending.value = request
        try {
            request.reply.await()
        } finally {
            _pending.compareAndSet(request, null)
        }
    }

    /**
     * The dialog's outcome for request [id]: ignored when that request is not the pending one any more (a call that
     * Stop cancelled must not answer the next one).
     */
    fun complete(id: Long, result: PermissionResult) {
        val request = _pending.value?.takeIf { it.id == id } ?: return
        // Cleared here, not by the awaiting call, so the activity cannot see it again when it restarts its collector.
        _pending.compareAndSet(request, null)
        request.reply.complete(result)
    }
}

/** A dialog the activity launched, see [PermissionLaunchState]. */
data class LaunchedPermission(val id: Long, val permission: String, val rationaleBefore: Boolean)

/**
 * What the activity remembers about the dialog it launched, so a request is launched exactly once: [PermissionGate.pending]
 * is a StateFlow, a collector restarted by Home or screen-off sees it again, and a second launch while the first is up is
 * answered by Android as a refusal. Kept across a recreate with [launched] and [restore].
 */
class PermissionLaunchState {
    /** The dialog that is up, `null` when none. */
    var launched: LaunchedPermission? = null
        private set

    /** Whether [request] is to be launched now: not when a dialog is already up. [rationale]: shown before the launch. */
    fun begin(request: PermissionRequest, rationale: Boolean): Boolean {
        if (launched != null) return false
        launched = LaunchedPermission(request.id, request.permission, rationale)
        return true
    }

    /** The dialog that closed, `null` when none was in flight. */
    fun finish(): LaunchedPermission? = launched.also { launched = null }

    fun restore(state: LaunchedPermission?) {
        launched = state
    }
}
