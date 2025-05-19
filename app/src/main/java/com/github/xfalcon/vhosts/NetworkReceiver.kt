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
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import com.github.xfalcon.vhosts.util.LogUtils
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException

class NetworkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (ConnectivityManager.CONNECTIVITY_ACTION != intent.action) {
            return
        }
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkInfo = connectivityManager.activeNetworkInfo
        if (networkInfo == null) return
        if (networkInfo.isAvailable and networkInfo.isConnected) {
            if (networkInfo.type == ConnectivityManager.TYPE_WIFI) {
                ipAddress = getWifiIpAddress(context)
                LogUtils.d(TAG, "WIFI " + ipAddress)
            } else if (networkInfo.type == ConnectivityManager.TYPE_MOBILE) {
                ipAddress = this.mobileIpAddress
                LogUtils.d(TAG, "MOBILE " + ipAddress)
            }
        }
    }

    private fun getWifiIpAddress(context: Context): String {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo = wifiManager.connectionInfo
        val int_ip = wifiInfo.ipAddress
        return intToIp(int_ip)
    }

    private val mobileIpAddress: String?
        get() {
            try {
                val enNetI =
                    NetworkInterface
                        .getNetworkInterfaces()
                while (enNetI.hasMoreElements()) {
                    val netI = enNetI.nextElement()
                    val enumIpAddr = netI
                        .inetAddresses
                    while (enumIpAddr.hasMoreElements()) {
                        val inetAddress = enumIpAddr.nextElement()
                        if (inetAddress is Inet4Address && !inetAddress.isLoopbackAddress) {
                            return inetAddress.hostAddress
                        }
                    }
                }
            } catch (e: SocketException) {
                e.printStackTrace()
            }
            return ""
        }

    companion object {
        private val TAG: String = NetworkReceiver::class.java.simpleName
        var ipAddress: String? = null

        private fun intToIp(i: Int): String {
            return (i and 0xFF).toString() + "." +
                    ((i shr 8) and 0xFF) + "." +
                    ((i shr 16) and 0xFF) + "." +
                    (i shr 24 and 0xFF)
        }
    }
}