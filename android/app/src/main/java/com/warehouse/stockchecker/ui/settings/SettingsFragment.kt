package com.warehouse.stockchecker.ui.settings

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.preference.PreferenceFragmentCompat
import com.warehouse.stockchecker.R
import com.warehouse.stockchecker.databinding.FragmentSettingsBinding
import com.warehouse.stockchecker.ml.PartDetector

/**
 * Hosts the standard PreferenceFragmentCompat screen. Settings are read live from
 * [android.content.SharedPreferences] (default location) and a few entries are populated
 * dynamically (model name, class count, app version) in [InnerFragment].
 */
class SettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val binding = FragmentSettingsBinding.inflate(inflater, container, false)
        if (savedInstanceState == null) {
            childFragmentManager.commit {
                replace(R.id.settingsContainer, InnerFragment())
            }
        }
        return binding.root
    }

    /**
     * The actual preference tree. Lives inside its own fragment so the standard AndroidX
     * preference machinery (search, dividers, ripple) renders correctly.
     */
    class InnerFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            // Populate dynamic values.
            findPreference<androidx.preference.Preference>("model_classes")?.summary =
                getString(R.string.settings_classes_count, loadClassCount())

            findPreference<androidx.preference.Preference>("app_version")?.summary = loadAppVersion()
        }

        private fun loadClassCount(): Int = runCatching {
            requireContext().assets.open(PartDetector.LABELS_ASSET).bufferedReader().useLines { lines ->
                lines.count { it.isNotBlank() }
            }
        }.getOrDefault(0)

        private fun loadAppVersion(): String = runCatching {
            @Suppress("DEPRECATION")
            val info = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            info.versionName ?: "unknown"
        }.getOrDefault("unknown")
    }
}
