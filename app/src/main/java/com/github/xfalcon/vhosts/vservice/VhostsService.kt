/*
 ** Copyright 2015, Mohamed Naufal
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */
package com.github.xfalcon.vhosts.vservice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import com.github.xfalcon.vhosts.NetworkReceiver
import com.github.xfalcon.vhosts.R
import com.github.xfalcon.vhosts.SettingsFragment
import com.github.xfalcon.vhosts.util.LogUtils
import org.xbill.DNS.Address
import java.io.Closeable
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock

class VhostsService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null

    private val pendingIntent: PendingIntent? = null

    private var deviceToNetworkUDPQueue: ConcurrentLinkedQueue<Packet?>? = null
    private var deviceToNetworkTCPQueue: ConcurrentLinkedQueue<Packet?>? = null
    private var networkToDeviceQueue: ConcurrentLinkedQueue<ByteBuffer?>? = null
    private var executorService: ExecutorService? = null

    private var udpSelector: Selector? = null
    private var tcpSelector: Selector? = null
    private var udpSelectorLock: ReentrantLock? = null
    private var tcpSelectorLock: ReentrantLock? = null
    private val netStateReceiver: NetworkReceiver? = null
    override fun onCreate() {
//        registerNetReceiver();
        super.onCreate()
        if (isOAndBoot) {
            //android 8.0 boot
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    "vhosts_channel_id",
                    "System",
                    NotificationManager.IMPORTANCE_NONE
                )
                val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(channel)
                val notification = Notification.Builder(this, "vhosts_channel_id")
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("Virtual Hosts Running")
                    .build()
                startForeground(1, notification)
            }
            isOAndBoot = false
        }
        setupHostFile()
        setupVPN()
        if (vpnInterface == null) {
            LogUtils.d(TAG, "unknow error")
            stopVService()
            return
        }
        isRunning = true
        try {
            udpSelector = Selector.open()
            tcpSelector = Selector.open()
            deviceToNetworkUDPQueue = ConcurrentLinkedQueue<Packet?>()
            deviceToNetworkTCPQueue = ConcurrentLinkedQueue<Packet?>()
            networkToDeviceQueue = ConcurrentLinkedQueue<ByteBuffer?>()
            udpSelectorLock = ReentrantLock()
            tcpSelectorLock = ReentrantLock()
            executorService = Executors.newFixedThreadPool(5)
            executorService!!.submit(UDPInput(networkToDeviceQueue!!, udpSelector!!, udpSelectorLock!!))
            executorService!!.submit(
                UDPOutput(
                    deviceToNetworkUDPQueue!!,
                    networkToDeviceQueue!!,
                    udpSelector!!,
                    udpSelectorLock!!,
                    this
                )
            )
            executorService!!.submit(TCPInput(networkToDeviceQueue!!, tcpSelector!!, tcpSelectorLock!!))
            executorService!!.submit(
                TCPOutput(
                    deviceToNetworkTCPQueue!!,
                    networkToDeviceQueue!!,
                    tcpSelector!!,
                    tcpSelectorLock!!,
                    this
                )
            )
            executorService!!.submit(
                VPNRunnable(
                    vpnInterface!!.fileDescriptor,
                    deviceToNetworkUDPQueue!!, deviceToNetworkTCPQueue!!, networkToDeviceQueue!!
                )
            )
            LocalBroadcastManager.getInstance(this)
                .sendBroadcast(Intent(BROADCAST_VPN_STATE).putExtra("running", true))
            LogUtils.i(TAG, "Started")
        } catch (e: Exception) {
            // TODO: Here and elsewhere, we should explicitly notify the user of any errors
            // and suggest that they stop the service, since we can't do it ourselves
            LogUtils.e(TAG, "Error starting service", e)
            stopVService()
        }
    }


    private fun setupHostFile() {
        val settings = PreferenceManager.getDefaultSharedPreferences(this)
        val is_net = settings.getBoolean(SettingsFragment.IS_NET, false)
        val uri_path = settings.getString(SettingsFragment.HOSTS_URI, null)
        try {
            val inputStream: InputStream?
            if (is_net) inputStream = openFileInput(SettingsFragment.NET_HOST_FILE)
            else inputStream = contentResolver.openInputStream(Uri.parse(uri_path))
            object : Thread() {
                override fun run() {
                    if (DnsChange.handle_hosts(inputStream!!) == 0) {
                        Looper.prepare()
                        if (is_net) {
                            Toast.makeText(
                                applicationContext,
                                R.string.no_net_record,
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            Toast.makeText(
                                applicationContext,
                                R.string.no_local_record,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        Looper.loop()
                    }
                }
            }.start()
        } catch (e: Exception) {
            if (is_net) {
                Toast.makeText(applicationContext, R.string.no_net_record, Toast.LENGTH_LONG)
                    .show()
            } else {
                Toast.makeText(applicationContext, R.string.no_local_record, Toast.LENGTH_LONG)
                    .show()
            }
            LogUtils.e(TAG, "error setup host file service", e)
        }
    }

    private fun setupVPN() {
        if (vpnInterface == null) {
            val builder: Builder = VpnService().Builder()
            builder.addAddress(VPN_ADDRESS, 32)
            builder.addAddress(VPN_ADDRESS6, 128)


            val settings = PreferenceManager.getDefaultSharedPreferences(this)
            val VPN_DNS4_DEFAULT = getString(R.string.dns_server)
            val is_cus_dns = settings.getBoolean(SettingsFragment.IS_CUS_DNS, false)
            var VPN_DNS4 = VPN_DNS4_DEFAULT
            if (is_cus_dns) {
                VPN_DNS4 = settings.getString(SettingsFragment.IPV4_DNS, VPN_DNS4_DEFAULT)!!
                try {
                    Address.getByAddress(VPN_DNS4)
                } catch (e: Exception) {
                    VPN_DNS4 = VPN_DNS4_DEFAULT
                    LogUtils.e(TAG, e.message, e)
                }
            }

            LogUtils.d(TAG, "use dns:" + VPN_DNS4)
            builder.addRoute(VPN_DNS4, 32)
            builder.addRoute(VPN_DNS6, 128)
            //            builder.addRoute(VPN_ROUTE,0);
//            builder.addRoute(VPN_ROUTE6,0);
            builder.addDnsServer(VPN_DNS4)
            builder.addDnsServer(VPN_DNS6)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val whiteList = arrayOf<String?>(
                    "com.android.vending",
                    "com.google.android.apps.docs",
                    "com.google.android.apps.photos",
                    "com.google.android.gm",
                    "com.google.android.apps.translate"
                )
                for (white in whiteList) {
                    try {
                        builder.addDisallowedApplication(white!!)
                    } catch (e: PackageManager.NameNotFoundException) {
                        LogUtils.e(TAG, e.message, e)
                    }
                }
            }
            vpnInterface = builder.setSession(getString(R.string.app_name)).setConfigureIntent(
                pendingIntent!!
            ).establish()
        }
    }

    private fun registerNetReceiver() {
        //wifi 4G state
//        IntentFilter filter = new IntentFilter();
//        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
//        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
//        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
//        netStateReceiver = new NetworkReceiver();
//        registerReceiver(netStateReceiver, filter);
    }

    private fun unregisterNetReceiver() {
//        if (netStateReceiver != null) {
//            unregisterReceiver(netStateReceiver);
//            netStateReceiver = null;
//        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            if (ACTION_DISCONNECT == intent.action) {
                stopVService()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun stopVService() {
        if (threadHandleHosts != null) threadHandleHosts.interrupt()
        //        unregisterNetReceiver();
        if (executorService != null) executorService!!.shutdownNow()
        isRunning = false
        cleanup()
        stopSelf()
        LogUtils.d(TAG, "Stopping")
    }

    override fun onRevoke() {
        stopVService()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVService()
        super.onDestroy()
    }

    private fun cleanup() {
        udpSelectorLock = null
        tcpSelectorLock = null
        deviceToNetworkTCPQueue = null
        deviceToNetworkUDPQueue = null
        networkToDeviceQueue = null
        ByteBufferPool.clear()
        closeResources(udpSelector!!, tcpSelector!!, vpnInterface!!)
    }

    private class VPNRunnable(
        private val vpnFileDescriptor: FileDescriptor?,
        private val deviceToNetworkUDPQueue: ConcurrentLinkedQueue<Packet?>,
        private val deviceToNetworkTCPQueue: ConcurrentLinkedQueue<Packet?>,
        private val networkToDeviceQueue: ConcurrentLinkedQueue<ByteBuffer?>
    ) : Runnable {
        override fun run() {
            LogUtils.i(TAG, "Started")

            val vpnInput = FileInputStream(vpnFileDescriptor).channel
            val vpnOutput = FileOutputStream(vpnFileDescriptor).channel
            try {
                var bufferToNetwork: ByteBuffer? = null
                var dataSent = true
                var dataReceived: Boolean
                while (!Thread.interrupted()) {
                    if (dataSent) bufferToNetwork = ByteBufferPool.acquire()
                    else bufferToNetwork!!.clear()

                    // TODO: Block when not connected
                    val readBytes = vpnInput.read(bufferToNetwork)
                    if (readBytes > 0) {
                        dataSent = true
                        bufferToNetwork!!.flip()
                        val packet = Packet(bufferToNetwork)
                        if (packet.isUDP) {
                            deviceToNetworkUDPQueue.offer(packet)
                        } else if (packet.isTCP) {
                            deviceToNetworkTCPQueue.offer(packet)
                        } else {
                            LogUtils.w(TAG, "Unknown packet type")
                            dataSent = false
                        }
                    } else {
                        dataSent = false
                    }
                    val bufferFromNetwork = networkToDeviceQueue.poll()
                    if (bufferFromNetwork != null) {
                        bufferFromNetwork.flip()
                        while (bufferFromNetwork.hasRemaining()) try {
                            vpnOutput.write(bufferFromNetwork)
                        } catch (e: Exception) {
                            LogUtils.e(TAG, e.toString(), e)
                            break
                        }
                        dataReceived = true
                        ByteBufferPool.release(bufferFromNetwork)
                    } else {
                        dataReceived = false
                    }

                    // TODO: Sleep-looping is not very battery-friendly, consider blocking instead
                    // Confirm if throughput with ConcurrentQueue is really higher compared to BlockingQueue
                    if (!dataSent && !dataReceived) Thread.sleep(11)
                }
            } catch (e: InterruptedException) {
                LogUtils.i(TAG, "Stopping")
            } catch (e: IOException) {
                LogUtils.w(TAG, e.toString(), e)
            } finally {
                closeResources(vpnInput, vpnOutput)
            }
        }

        companion object {
            private val TAG: String = VPNRunnable::class.java.simpleName
        }
    }

    companion object {
        private val TAG: String = VhostsService::class.java.simpleName
        private const val VPN_ADDRESS = "192.0.2.111"
        private const val VPN_ADDRESS6 = "fe80:49b1:7e4f:def2:e91f:95bf:fbb6:1111"
        private const val VPN_ROUTE = "0.0.0.0" // Intercept everything
        private const val VPN_ROUTE6 = "::" // Intercept everything
        private const val VPN_DNS4 = "8.8.8.8"
        private const val VPN_DNS6 = "2001:4860:4860::8888"

        val BROADCAST_VPN_STATE: String = VhostsService::class.java.name + ".VPN_STATE"
        val ACTION_CONNECT: String = VhostsService::class.java.name + ".START"
        val ACTION_DISCONNECT: String = VhostsService::class.java.name + ".STOP"

        var isRunning: Boolean = false
            private set
        private val threadHandleHosts: Thread? = null
        private var isOAndBoot = false


        fun startVService(context: Context, method: Int) {
            val intent = prepare(context)
            if (intent != null) {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                LogUtils.e(TAG, "Run Fail On Boot")
            }
            try {
                if (method == 2 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    isOAndBoot = true
                    context.startForegroundService(
                        Intent(context, VhostsService::class.java).setAction(
                            ACTION_CONNECT
                        )
                    )
                } else {
                    isOAndBoot = false
                    context.startService(
                        Intent(context, VhostsService::class.java).setAction(
                            ACTION_CONNECT
                        )
                    )
                }
            } catch (e: RuntimeException) {
                LogUtils.e(TAG, "Not allowed to start service Intent", e)
            }
        }

        fun stopVService(context: Context) {
            context.startService(
                Intent(context, VhostsService::class.java).setAction(
                    ACTION_DISCONNECT
                )
            )
        }

        // TODO: Move this to a "utils" class for reuse
        private fun closeResources(vararg resources: Closeable) {
            for (resource in resources) {
                try {
                    resource.close()
                } catch (e: Exception) {
                    LogUtils.e(TAG, e.toString(), e)
                }
            }
        }
    }
}
