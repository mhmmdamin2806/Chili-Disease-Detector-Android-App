package com.example.chilidisease

import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.example.chilidisease.databinding.ActivitySettingsBinding

/**
 * SettingsActivity
 *
 * Layar pengaturan menggunakan AndroidX Preference library.
 * Pengaturan yang tersedia:
 *  - Jenis model TFLite (Teachable Machine / SSD / YOLOv8)
 *  - Confidence threshold (30–90%)
 *  - Aktifkan GPU Delegate
 *  - Interval deteksi (FPS inferensi)
 *  - Aktifkan notifikasi penyakit
 *  - Reset riwayat
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbarSettings)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.settings_title)
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ================================================================
    // PREFERENCE FRAGMENT
    // ================================================================

    class SettingsFragment : PreferenceFragmentCompat(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            updateSummaries()
        }

        override fun onResume() {
            super.onResume()
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onPause() {
            super.onPause()
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        }

        override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
            updateSummaries()
        }

        private fun updateSummaries() {
            // Update summary ListPreference agar tampilkan nilai yang dipilih
            findPreference<ListPreference>(PrefsKey.MODEL_TYPE)?.let { pref ->
                pref.summary = pref.entry ?: "SSD MobileNet (320×320)"
            }
            findPreference<SeekBarPreference>(PrefsKey.CONFIDENCE_THRESHOLD)?.let { pref ->
                pref.summary = "Minimum akurasi: ${pref.value}%"
            }
            findPreference<SeekBarPreference>(PrefsKey.INFERENCE_INTERVAL)?.let { pref ->
                val fps = 1000 / pref.value.coerceAtLeast(1)
                pref.summary = "Interval: ${pref.value}ms (~$fps FPS)"
            }
        }
    }

    // ================================================================
    // PREFERENCE KEYS (companion object agar bisa diakses dari mana saja)
    // ================================================================

    companion object PrefsKey {
        const val MODEL_TYPE           = "model_type"
        const val CONFIDENCE_THRESHOLD = "confidence_threshold"
        const val USE_GPU              = "use_gpu"
        const val INFERENCE_INTERVAL   = "inference_interval"
        const val ENABLE_NOTIFICATIONS = "enable_notifications"
        const val SAVE_HISTORY         = "save_history"

        // Helper untuk baca preference dari SharedPreferences
        fun getConfidenceThreshold(prefs: SharedPreferences): Float =
            prefs.getInt(CONFIDENCE_THRESHOLD, 45) / 100f

        fun getInferenceIntervalMs(prefs: SharedPreferences): Long =
            prefs.getInt(INFERENCE_INTERVAL, 80).toLong()

        fun isGpuEnabled(prefs: SharedPreferences): Boolean =
            prefs.getBoolean(USE_GPU, false)

        fun isNotificationsEnabled(prefs: SharedPreferences): Boolean =
            prefs.getBoolean(ENABLE_NOTIFICATIONS, true)

        fun isSaveHistoryEnabled(prefs: SharedPreferences): Boolean =
            prefs.getBoolean(SAVE_HISTORY, true)

        fun getModelType(prefs: SharedPreferences): String =
            prefs.getString(MODEL_TYPE, "ssd") ?: "ssd"
    }
}
