/*
 **Copyright (C) 2017  xfalcon
 **
 **This program is free software: you can redistribute it and/or modify
 **it under the terms of the GNU General Public License as published by
 **the Free Software Foundation, either version 3 of the License, or
 **(at your option) any later version.
 **
 **This program is distributed in the hope that it will be useful,
 **but WITHOUT ANY WARRANTY; without even the implied warranty of
 **MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 **GNU General Public License for more details.
 **
 **You should have received a copy of the GNU General Public License
 **along with this program.  If not, see <http://www.gnu.org/licenses/>.
 **
 */
package com.github.xfalcon.vhosts

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import com.github.xfalcon.vhosts.util.FileUtils
import com.github.xfalcon.vhosts.util.HttpUtils
import com.github.xfalcon.vhosts.util.LogUtils
import com.github.xfalcon.vhosts.vservice.DnsChange
import org.xbill.DNS.Address
import java.util.regex.Pattern

class SettingsFragment : PreferenceFragmentCompat(), OnSharedPreferenceChangeListener {
    private var handler: Handler? = null


    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        val sharedPreferences = preferenceScreen.getSharedPreferences()
        val prefScreen = preferenceScreen
        handeleSummary(prefScreen, sharedPreferences)
        val urlCustomPref = findPreference(HOSTS_URL)
        val dnsCustomPref = findPreference(IPV4_DNS)

        dnsCustomPref.onPreferenceChangeListener = object : Preference.OnPreferenceChangeListener {
            override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
                val ipv4_dns = newValue as String?
                try {
                    Address.getByAddress(ipv4_dns)
                    return true
                } catch (e: Exception) {
                    LogUtils.e(TAG, e.message, e)
                    Toast.makeText(
                        preference.context,
                        getString(R.string.dns4_error),
                        Toast.LENGTH_LONG
                    ).show()
                }
                return false
            }
        }


        //        dnsCustomPref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
//
//            public boolean onPreferenceClick(Preference preference) {
//                String ipv4_dns = sharedPreferences.getString(IPV4_DNS, "");
//                try {
//                    Address.getByAddress(ipv4_dns);
//                    return true;
//                } catch (Exception e) {
//                    LogUtils.e(TAG, e.getMessage(), e);
//                    Toast.makeText(preference.getContext(), getString(R.string.url_error), Toast.LENGTH_LONG).show();
//                }
//                return false;
//            }
//        });
        urlCustomPref.onPreferenceChangeListener = object : Preference.OnPreferenceChangeListener {
            override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
                val url = newValue as String
                if (isUrl(url)) {
                    setProgressDialog(preference.context, url)
                    return true
                } else {
                    Toast.makeText(
                        preference.context,
                        getString(R.string.url_error),
                        Toast.LENGTH_LONG
                    ).show()
                    return false
                }
            }
        }

        //        urlCustomPref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
//
//            public boolean onPreferenceClick(Preference preference) {
//                String url = sharedPreferences.getString(HOSTS_URL, "");
//                if (isUrl(url)) {
//                    setProgressDialog(preference.getContext(), url);
//                    return true;
//                } else {
//                    Toast.makeText(preference.getContext(), getString(R.string.url_error), Toast.LENGTH_LONG).show();
//                    return false;
//                }
//
//            }
//        });
    }

    fun setProgressDialog(context: Context, url: String?) {
        val llPadding = 30
        val ll = LinearLayout(context)
        ll.orientation = LinearLayout.HORIZONTAL
        ll.setPadding(llPadding, llPadding, llPadding, llPadding)
        ll.gravity = Gravity.CENTER
        var llParam = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        llParam.gravity = Gravity.CENTER
        ll.layoutParams = llParam

        val progressBar = ProgressBar(context)
        progressBar.isIndeterminate = true
        progressBar.setPadding(0, 0, llPadding, 0)
        progressBar.layoutParams = llParam

        llParam = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        llParam.gravity = Gravity.CENTER
        val tvText = TextView(context)
        tvText.text = getString(R.string.download_alert)
        tvText.setTextColor(Color.parseColor("#000000"))
        tvText.textSize = 20f
        tvText.layoutParams = llParam

        ll.addView(progressBar)
        ll.addView(tvText)

        val builder = AlertDialog.Builder(context)
        builder.setCancelable(true)
        builder.setView(ll)

        val dialog = builder.create()
        val window = dialog.window
        if (window != null) {
            val layoutParams = WindowManager.LayoutParams()
            layoutParams.copyFrom(dialog.window!!.attributes)
            layoutParams.width = LinearLayout.LayoutParams.WRAP_CONTENT
            layoutParams.height = LinearLayout.LayoutParams.WRAP_CONTENT
            dialog.window!!.attributes = layoutParams
        }
        handler = Handler()

        Thread(object : Runnable {
            override fun run() {
                try {
                    Looper.prepare()
                    val result = HttpUtils.get(url)
                    FileUtils.writeFile(
                        context.openFileOutput(NET_HOST_FILE, Context.MODE_PRIVATE),
                        result ?: ""
                    )
                    Toast.makeText(
                        context, String.format(
                            getString(R.string.down_success), DnsChange.handle_hosts(
                                context.openFileInput(
                                    NET_HOST_FILE
                                )
                            )
                        ), Toast.LENGTH_LONG
                    ).show()
                    handler!!.post { dialog.hide() }
                    Looper.loop()
                } catch (e: Exception) {
                    Toast.makeText(context, getString(R.string.down_error), Toast.LENGTH_LONG)
                        .show()
                    LogUtils.e(TAG, e.message, e)
                }
            }
        }).start()
        dialog.show()
    }

    private fun handeleSummary(
        preferenceGroup: PreferenceGroup,
        sharedPreferences: SharedPreferences
    ) {
        val count = preferenceGroup.preferenceCount

        for (i in 0..<count) {
            val p = preferenceGroup.getPreference(i)
            if (p is PreferenceCategory) {
                handeleSummary(p, sharedPreferences)
            }
            if (p !is CheckBoxPreference) {
                val value: String = sharedPreferences.getString(p.key, "")!!
                setPreferenceSummary(p, value)
            }
        }
    }

    private fun setPreferenceSummary(preference: Preference?, value: String?) {
        if (preference is ListPreference) {
            val listPreference = preference
            val prefIndex = listPreference.findIndexOfValue(value)
            if (prefIndex >= 0) {
                listPreference.setSummary(listPreference.entries[prefIndex])
            }
        } else if (preference is EditTextPreference) {
            preference.summary = value
        }
    }

    fun isUrl(str: String): Boolean {
        val regex = "http(s)?://([\\w-]+\\.)+[\\w-]+(/[\\w- ./?%&=]*)?"
        val pattern = Pattern.compile(regex)
        val matcher = pattern.matcher(str)
        return matcher.matches()
    }

    override fun onSharedPreferenceChanged(
        sharedPreferences: SharedPreferences,
        key: String?
    ) {
        val preference = findPreference(key)
        if (null != preference) {
            if (preference !is CheckBoxPreference) {
                val value: String = sharedPreferences.getString(preference.key, "")!!
                setPreferenceSummary(preference, value)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferenceScreen.getSharedPreferences()
            .registerOnSharedPreferenceChangeListener(this)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // create ContextThemeWrapper from the original Activity Context with the custom theme

        // clone the inflater using the ContextThemeWrapper

        inflater.context.setTheme(R.style.AppPreferenceSettingsFragmentTheme)

        // inflate the layout using the cloned inflater, not default inflater
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        preferenceManager.getSharedPreferences().registerOnSharedPreferenceChangeListener(this)
    }

    override fun onPause() {
        preferenceManager.getSharedPreferences()
            .unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        preferenceScreen.getSharedPreferences()
            .unregisterOnSharedPreferenceChangeListener(this)
    }

    companion object {
        private val TAG: String = SettingsFragment::class.java.name

        const val VPN_REQUEST_CODE: Int = 0x0F
        const val SELECT_FILE_CODE: Int = 0x05
        val PREFS_NAME: String = SettingsFragment::class.java.name
        const val IS_NET: String = "IS_NET"
        const val HOSTS_URL: String = "HOSTS_URL"
        const val HOSTS_URI: String = "HOST_URI"
        const val NET_HOST_FILE: String = "net_hosts"
        const val IPV4_DNS: String = "IPV4_DNS"
        const val IS_CUS_DNS: String = "IS_CUS_DNS"
    }
}
