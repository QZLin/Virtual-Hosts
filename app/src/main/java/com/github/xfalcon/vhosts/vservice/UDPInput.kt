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
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock

class UDPInput(
    private val outputQueue: ConcurrentLinkedQueue<ByteBuffer?>,
    private val selector: Selector,
    private val udpSelectorLock: ReentrantLock
) : Runnable {
    override fun run() {
        try {
            LogUtils.i(TAG, "Started")
            while (!Thread.interrupted()) {
                udpSelectorLock.lock()
                udpSelectorLock.unlock()
                val readyChannels = selector.select()
                if (readyChannels == 0) {
                    Thread.sleep(11)
                    continue
                }
                val keys = selector.selectedKeys()
                val keyIterator: MutableIterator<SelectionKey> = keys.iterator()

                while (keyIterator.hasNext() && !Thread.interrupted()) {
                    val key = keyIterator.next()
                    if (key.isValid && key.isReadable) {
                        keyIterator.remove()

                        val receiveBuffer = ByteBufferPool.acquire()


                        // Leave space for the header
                        val inputChannel = key.channel() as DatagramChannel
                        val referencePacket = key.attachment() as Packet
                        receiveBuffer.position(referencePacket.IP_TRAN_SIZE)
                        var readBytes = 0
                        try {
                            readBytes = inputChannel.read(receiveBuffer)
                        } catch (e: Exception) {
                            LogUtils.e(TAG, "Network read error", e)
                        }
                        referencePacket.updateUDPBuffer(receiveBuffer, readBytes)
                        receiveBuffer.position(referencePacket.IP_TRAN_SIZE + readBytes)
                        outputQueue.offer(receiveBuffer)
                    }
                }
            }
        } catch (e: InterruptedException) {
            LogUtils.i(TAG, "Stopping")
        } catch (e: IOException) {
            LogUtils.w(TAG, e.toString(), e)
        }
    }

    companion object {
        private val TAG: String = UDPInput::class.java.simpleName
    }
}
