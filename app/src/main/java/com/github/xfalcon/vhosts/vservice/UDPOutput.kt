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

import com.github.xfalcon.vhosts.util.LogUtils
import com.github.xfalcon.vhosts.vservice.LRUCache.CleanupCallback
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock


class UDPOutput(
    private val inputQueue: ConcurrentLinkedQueue<Packet?>,
    private val outputQueue: ConcurrentLinkedQueue<ByteBuffer?>,
    private val selector: Selector,
    private val udpSelectorLock: ReentrantLock,
    private val vpnService: VhostsService
) : Runnable {
    private val stringBuild: StringBuilder?


    private val channelCache = LRUCache<String?, DatagramChannel?>(
        MAX_CACHE_SIZE,
        object : CleanupCallback<String?, DatagramChannel?> {
            override fun cleanup(eldest: MutableMap.MutableEntry<String?, DatagramChannel?>) {
                closeChannel(eldest.value!!)
            }
        })

    init {
        this.stringBuild = StringBuilder(32)
    }

    override fun run() {
        LogUtils.i(TAG, "Started")
        try {
            while (!Thread.interrupted()) {
                val currentPacket = inputQueue.poll()
                if (currentPacket == null) {
                    Thread.sleep(11)
                    continue
                }
                // hook dns packet
                if (currentPacket.udpHeader!!.destinationPort == 53) {
                    val packet_buffer = DnsChange.handle_dns_packet(currentPacket)
                    if (packet_buffer != null) {
                        this.outputQueue.offer(packet_buffer)
                        continue
                    }
                }
                val destinationAddress = currentPacket.ipHeader!!.destinationAddress
                val destinationPort = currentPacket.udpHeader!!.destinationPort
                val sourcePort = currentPacket.udpHeader!!.sourcePort
                val ipAndPort = String.format(
                    "%s:%s:%s",
                    destinationAddress!!.hostAddress,
                    destinationPort,
                    sourcePort
                )
                var outputChannel = channelCache.get(ipAndPort)
                if (outputChannel == null) {
                    outputChannel = DatagramChannel.open()
                    vpnService.protect(outputChannel.socket())
                    try {
                        outputChannel.connect(
                            InetSocketAddress(
                                destinationAddress,
                                destinationPort
                            )
                        )
                    } catch (e: IOException) {
                        LogUtils.e(TAG, "Connection error: " + ipAndPort, e)
                        closeChannel(outputChannel)
                        ByteBufferPool.release(currentPacket.backingBuffer)
                        continue
                    }
                    outputChannel.configureBlocking(false)
                    currentPacket.swapSourceAndDestination()
                    udpSelectorLock.lock()
                    selector.wakeup()
                    outputChannel.register(selector, SelectionKey.OP_READ, currentPacket)
                    udpSelectorLock.unlock()
                    channelCache.put(ipAndPort, outputChannel)
                }

                try {
                    val payloadBuffer = currentPacket.backingBuffer
                    while (payloadBuffer.hasRemaining()) outputChannel.write(payloadBuffer)
                } catch (e: IOException) {
                    LogUtils.e(TAG, "Network write error: " + ipAndPort, e)
                    channelCache.remove(ipAndPort)
                    closeChannel(outputChannel)
                }
                ByteBufferPool.release(currentPacket.backingBuffer)
            }
        } catch (e: InterruptedException) {
            LogUtils.i(TAG, "Stopping")
        } catch (e: IOException) {
            LogUtils.i(TAG, e.toString(), e)
        } finally {
            closeAll()
        }
    }

    private fun closeAll() {
        val it: MutableIterator<MutableMap.MutableEntry<String?, DatagramChannel?>?> =
            channelCache.entries.iterator()
        while (it.hasNext()) {
            closeChannel(it.next()!!.value!!)
            it.remove()
        }
    }

    private fun closeChannel(channel: DatagramChannel) {
        try {
            channel.close()
        } catch (e: IOException) {
            // Ignore
        }
    }

    companion object {
        private val TAG: String = UDPOutput::class.java.simpleName

        private const val MAX_CACHE_SIZE = 50
    }
}
