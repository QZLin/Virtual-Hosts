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
import androidx.preference.PreferenceManager
import com.github.xfalcon.vhosts.vservice.VhostsService

//use adb for test
//am broadcast -a android.intent.action.BOOT_COMPLETED -p com.github.xfalcon.vhosts
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (getEnabled(context)) {
            if (!VhostsService.isRunning) {
                VhostsService.startVService(context, 2)
            }
        }
    }

    companion object {
        const val RECONNECT_ON_REBOOT: String = "RECONNECT_ON_REBOOT"

        fun setEnabled(context: Context, enabled: Boolean) {
            val settings = PreferenceManager.getDefaultSharedPreferences(context)
            val editor = settings.edit()
            editor.putBoolean(RECONNECT_ON_REBOOT, enabled)
            editor.apply()
        }

        fun getEnabled(context: Context): Boolean {
            val settings = PreferenceManager.getDefaultSharedPreferences(context)
            return settings.getBoolean(RECONNECT_ON_REBOOT, false)
        }
    }
}
