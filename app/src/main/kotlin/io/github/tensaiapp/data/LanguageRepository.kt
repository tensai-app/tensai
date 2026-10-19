package io.github.tensaiapp.data

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.languageDataStore by preferencesDataStore(name = "tensai_language")

/**
 * A language the app can be displayed in, independently of the system's own (TEN-38's Settings >
 * Appearance).
 *
 * The entries are the app's shipped `res/values-*` locales plus [System], and they have to stay
 * in sync with them by hand: there is no way to enumerate an APK's own translations below API 33
 * without pulling in `androidx.appcompat` for its `LocaleConfig`, and one enum entry per locale
 * folder is a cheaper contract than a dependency. [French] resolves to `res/values-fr` (TEN-41);
 * `StringsParityTest` keeps its keys and placeholders in step with `values/`.
 *
 * @param localeTag BCP 47 tag to force, or `null` for [System] — meaning "don't override anything",
 *   not "English": the device's own locale list keeps applying, including its fallback order.
 */
enum class AppLanguage(val localeTag: String?) {
    System(null),
    English("en"),
    French("fr"),
}

/**
 * Persists the user's [AppLanguage]. Defaults to [AppLanguage.System].
 *
 * Same shape as [ThemeRepository]/[BackendRepository], [DataStore] injectable for the same reason:
 * the round trip is provable in a JVM unit test against a temp file, with no device.
 */
class LanguageRepository(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.languageDataStore)

    private val languageKey = stringPreferencesKey("app_language")

    val language: Flow<AppLanguage> = dataStore.data.map { preferences ->
        preferences[languageKey]
            ?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() }
            ?: AppLanguage.System
    }

    suspend fun setLanguage(language: AppLanguage) {
        dataStore.edit { preferences ->
            preferences[languageKey] = language.name
        }
    }
}

/**
 * This context, with its [Resources] resolved against [language] — what `MainActivity` provides as
 * `LocalContext` so a language change re-reads every `stringResource` without an `Activity`
 * restart, the same way a theme change re-colours without one.
 *
 * A [ContextWrapper] around the receiver rather than the [Context.createConfigurationContext]
 * result directly: that call returns a context whose base is *not* this one, which would break
 * anything walking the wrapper chain back to the `Activity` (Compose's own
 * `LocalContext`-to-`Activity` lookups among them). Wrapping keeps the chain and swaps only what
 * has to change.
 *
 * [AppLanguage.System] returns the receiver untouched — no wrapper, no configuration copy.
 */
fun Context.withAppLanguage(language: AppLanguage): Context {
    val tag = language.localeTag ?: return this
    val configuration = Configuration(resources.configuration).apply {
        setLocale(Locale.forLanguageTag(tag))
    }
    return LocalizedContext(this, createConfigurationContext(configuration).resources)
}

private class LocalizedContext(base: Context, private val localizedResources: Resources) : ContextWrapper(base) {
    override fun getResources(): Resources = localizedResources
}
