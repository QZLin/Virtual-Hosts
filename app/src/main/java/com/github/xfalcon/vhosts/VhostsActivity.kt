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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import com.github.clans.fab.FloatingActionButton
import com.github.xfalcon.vhosts.util.LogUtils
import com.github.xfalcon.vhosts.vservice.VhostsService
import com.suke.widget.SwitchButton
import java.lang.reflect.Field

//import com.google.firebase.analytics.FirebaseAnalytics;
class VhostsActivity : AppCompatActivity() {
    //    private FirebaseAnalytics mFirebaseAnalytics;
    private var waitingForVPNStart = false

    private val vpnStateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            if (VhostsService.BROADCAST_VPN_STATE == intent.action) {
                if (intent.getBooleanExtra("running", false)) waitingForVPNStart = false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launch()

        //        StatService.autoTrace(this, true, false);
//        mFirebaseAnalytics = FirebaseAnalytics.getInstance(this);
        setContentView(R.layout.activity_vhosts)
        LogUtils.context = applicationContext
        val vpnButton = findViewById<SwitchButton>(R.id.button_start_vpn)

        val selectHosts = findViewById<Button>(R.id.button_select_hosts)
        val fab_setting = findViewById<FloatingActionButton>(R.id.fab_setting)
        val fab_boot = findViewById<FloatingActionButton>(R.id.fab_boot)
        val fab_donation = findViewById<FloatingActionButton>(R.id.fab_donation)

        if (checkHostUri() == -1) {
            selectHosts.text = getString(R.string.select_hosts)
        }
        if (BootReceiver.getEnabled(this)) {
            fab_boot.setColorNormalResId(R.color.startup_on)
        }
        vpnButton.setOnCheckedChangeListener { view, isChecked ->
            if (isChecked) {
                if (checkHostUri() == -1) {
                    showDialog()
                } else {
                    startVPN()
                }
            } else {
                shutdownVPN()
            }
        }
        fab_setting.setOnClickListener {
            startActivity(
                Intent(
                    applicationContext,
                    SettingsActivity::class.java
                )
            )
        }
        fab_boot.setOnClickListener { v ->
            if (BootReceiver.getEnabled(v.context)) {
                BootReceiver.setEnabled(v.context, false)
                fab_boot.setColorNormalResId(R.color.startup_off)
            } else {
                BootReceiver.setEnabled(v.context, true)
                fab_boot.setColorNormalResId(R.color.startup_on)
            }
        }
        selectHosts.setOnClickListener { selectFile() }
        selectHosts.setOnLongClickListener {
            startActivity(Intent(applicationContext, SettingsActivity::class.java))
            false
        }
        fab_donation.setOnClickListener {
            //                startActivity(new Intent(getApplicationContext(), DonationActivity.class));
        }

        LocalBroadcastManager.getInstance(this).registerReceiver(
            vpnStateReceiver,
            IntentFilter(VhostsService.BROADCAST_VPN_STATE)
        )
    }

    private fun launch() {
        val uri = intent.data
        if (uri == null) return
        val data_str = uri.toString()
        if ("on" == data_str) {
            if (!VhostsService.isRunning) VhostsService.startVService(this, 1)
            finish()
        } else if ("off" == data_str) {
            VhostsService.stopVService(this)
            finish()
        }
    }

    private fun selectFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.type = "*/*"
        try {
            var SHOW_ADVANCED: String?
            try {
                val f: Field = DocumentsContract::class.java.getField("EXTRA_SHOW_ADVANCED")
                SHOW_ADVANCED = (f.get(f.name) ?: "") as String?
            } catch (e: NoSuchFieldException) {
                LogUtils.e(TAG, e.message, e)
                SHOW_ADVANCED = "android.content.extra.SHOW_ADVANCED"
            }
            intent.putExtra(SHOW_ADVANCED, true)
        } catch (e: Throwable) {
            LogUtils.e(TAG, "SET EXTRA_SHOW_ADVANCED", e)
        }

        try {
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            startActivityForResult(intent, SettingsFragment.SELECT_FILE_CODE)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.file_select_error, Toast.LENGTH_LONG).show()
            LogUtils.e(TAG, "START SELECT_FILE_ACTIVE FAIL", e)
            val settings = getSharedPreferences(SettingsFragment.PREFS_NAME, MODE_PRIVATE)
            val editor = settings.edit()
            editor.putBoolean(SettingsFragment.IS_NET, true)
            editor.apply()
            startActivity(Intent(applicationContext, SettingsActivity::class.java))
        }
    }

    private fun startVPN() {
        waitingForVPNStart = false
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) startActivityForResult(vpnIntent, SettingsFragment.VPN_REQUEST_CODE)
        else onActivityResult(SettingsFragment.VPN_REQUEST_CODE, RESULT_OK, null)
    }

    private fun checkHostUri(): Int {
        val settings = PreferenceManager.getDefaultSharedPreferences(this)
        if (settings.getBoolean(SettingsFragment.IS_NET, false)) {
            try {
                openFileInput(SettingsFragment.NET_HOST_FILE).close()
                return 2
            } catch (e: Exception) {
                LogUtils.e(TAG, "NET HOSTS FILE NOT FOUND", e)
                return -2
            }
        } else {
            try {
                contentResolver.openInputStream(
                    Uri.parse(
                        settings.getString(
                            SettingsFragment.HOSTS_URI,
                            null
                        )
                    )
                )
                    ?.close()
                return 1
            } catch (e: Exception) {
                LogUtils.e(TAG, "HOSTS FILE NOT FOUND", e)
                return -1
            }
        }
    }

    private fun setUriByPREFS(intent: Intent) {
        val settings = PreferenceManager.getDefaultSharedPreferences(this)
        val editor = settings.edit()
        val uri = intent.data
        try {
            checkNotNull(uri)
            contentResolver.takePersistableUriPermission(
                uri!!, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            editor.putString(SettingsFragment.HOSTS_URI, uri.toString())
            editor.apply()
            when (checkHostUri()) {
                1 -> {
                    setButton(true)
                    setButton(false)
                }

                -1 -> {
                    Toast.makeText(this, R.string.permission_error, Toast.LENGTH_LONG).show()
                }

                2, -2 -> {}
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "permission error", e)
        }
    }

    private fun shutdownVPN() {
        if (VhostsService.isRunning) startService(
            Intent(
                this,
                VhostsService::class.java
            ).setAction(VhostsService.ACTION_DISCONNECT)
        )
        setButton(true)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SettingsFragment.VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            waitingForVPNStart = true
            startService(
                Intent(
                    this,
                    VhostsService::class.java
                ).setAction(VhostsService.ACTION_CONNECT)
            )
            setButton(false)
        } else if (requestCode == SettingsFragment.SELECT_FILE_CODE && resultCode == RESULT_OK) {
//            setUriByPREFS(data)
        }
    }

    override fun onResume() {
        super.onResume()
        setButton(!waitingForVPNStart && !VhostsService.isRunning)
    }

    override fun onStop() {
        super.onStop()
    }

    private fun setButton(enable: Boolean) {
        val vpnButton = findViewById<View?>(R.id.button_start_vpn) as SwitchButton
        val selectHosts = findViewById<View?>(R.id.button_select_hosts) as Button
        if (enable) {
            vpnButton.setChecked(false)
            selectHosts.alpha = 1.0f
            selectHosts.isClickable = true
        } else {
            vpnButton.setChecked(true)
            selectHosts.alpha = .5f
            selectHosts.isClickable = false
        }
    }

    private fun showDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setCancelable(false)
        builder.setTitle(R.string.dialog_title)
        builder.setMessage(R.string.dialog_message)
        builder.setPositiveButton(
            R.string.dialog_confirm
        ) { dialogInterface, i -> selectFile() }

        builder.setNegativeButton(
            R.string.dialog_cancel
        ) { dialogInterface, i -> setButton(true) }
        builder.show()
    }

    companion object {
        private val TAG: String = VhostsActivity::class.java.simpleName
    }
}
