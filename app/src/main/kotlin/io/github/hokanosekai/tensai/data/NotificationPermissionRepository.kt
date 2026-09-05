package io.github.hokanosekai.tensai.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.notificationPermissionDataStore by preferencesDataStore(name = "tensai_notification_permission")

/**
 * Whether this install has already put the `POST_NOTIFICATIONS` system dialog in front of the user
 * (TEN-75). One flag, one direction: it goes true the first time and never back.
 *
 * **Persisted rather than held in memory, because Android counts denials per install, not per
 * process.** The platform allows exactly two dialogs; the second denial is permanent. A
 * once-per-process latch would still hand out a fresh dialog on every cold start — and this
 * process dies often (the whole point of [io.github.hokanosekai.tensai.runtime.InferenceService]
 * is that it would die even sooner without a foreground anchor) — so it would burn that second ask
 * on the next launch instead of the next screen entry. Only a persisted flag actually keeps the
 * ask unspent.
 *
 * Same shape as [OnboardingRepository]/[ThemeRepository], its own DataStore file for the same
 * reason, [DataStore] injectable for the same reason. It is deliberately *not* a key on
 * [OnboardingRepository]: this flag outlives first-run — it is read on every model load, forever.
 */
class NotificationPermissionRepository(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.notificationPermissionDataStore)

    private val requestedKey = booleanPreferencesKey("requested")

    val requested: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[requestedKey] ?: false
    }

    /** Suspending read of [requested]'s current value, for the one-shot call in the load path. */
    suspend fun wasRequested(): Boolean = requested.first()

    suspend fun markRequested() {
        dataStore.edit { preferences ->
            preferences[requestedKey] = true
        }
    }
}
