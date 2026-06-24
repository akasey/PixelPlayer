package com.theveloper.pixelplay.data.navidrome.tunnel

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypted storage for the raw WireGuard `.conf` text.
 *
 * Kept separate from [com.theveloper.pixelplay.data.navidrome.NavidromeRepository] (which also
 * uses EncryptedSharedPreferences) so [WireGuardTunnelManager] can read the config without
 * depending on the Navidrome API graph — that would create a Hilt dependency cycle through the
 * tunnel-aware OkHttp client.
 */
@Singleton
class WireGuardConfigStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        Timber.e(e, "WireGuardConfigStore: EncryptedSharedPreferences failed, using plain fallback")
        context.getSharedPreferences("${PREFS_NAME}_plain", Context.MODE_PRIVATE)
    }

    /** Raw `.conf` text, or null if none stored. */
    var rawConfig: String?
        get() = prefs.getString(KEY_CONF, null)
        set(value) = prefs.edit {
            if (value.isNullOrBlank()) remove(KEY_CONF) else putString(KEY_CONF, value)
        }

    fun hasConfig(): Boolean = !prefs.getString(KEY_CONF, null).isNullOrBlank()

    /** Parsed config, or null if absent/invalid. */
    fun parsedConfig(): WireGuardConfig? =
        rawConfig?.let {
            runCatching { WireGuardConfigParser.parse(it) }
                .onFailure { e -> Timber.w(e, "Stored WireGuard config is invalid") }
                .getOrNull()
        }

    fun clear() = prefs.edit { remove(KEY_CONF) }

    private companion object {
        const val PREFS_NAME = "navidrome_wg_prefs"
        const val KEY_CONF = "wg_conf"
    }
}
