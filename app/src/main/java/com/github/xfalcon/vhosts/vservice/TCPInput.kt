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
import com.github.xfalcon.vhosts.vservice.Packet.TCPHeader
import com.github.xfalcon.vhosts.vservice.TCB.TCBStatus
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock


class TCPInput(
    private val outputQueue: ConcurrentLinkedQueue<ByteBuffer?>,
    private val selector: Selector,
    private val tcpSelectorLock: ReentrantLock
) : Runnable {
    override fun run() {
        try {
            LogUtils.i(TAG, "Started")
            while (!Thread.interrupted()) {
                tcpSelectorLock.lock()
                tcpSelectorLock.unlock()

                val readyChannels = selector.select()

                if (readyChannels == 0) {
                    Thread.sleep(11)
                    continue
                }
                val keys = selector.selectedKeys()
                val keyIterator: MutableIterator<SelectionKey> = keys.iterator()

                while (keyIterator.hasNext() && !Thread.interrupted()) {
                    val key = keyIterator.next()
                    if (key.isValid) {
                        if (key.isConnectable) processConnect(key, keyIterator)
                        else if (key.isReadable) processInput(key, keyIterator)
                    }
                }
            }
        } catch (e: InterruptedException) {
            LogUtils.i(TAG, "Stopping")
        } catch (e: IOException) {
            LogUtils.w(TAG, e.toString(), e)
        }
    }

    private fun processConnect(key: SelectionKey, keyIterator: MutableIterator<SelectionKey>) {
        val tcb = key.attachment() as TCB
        val referencePacket = tcb.referencePacket

        try {
            if (tcb.channel.finishConnect()) {
                keyIterator.remove()
                tcb.status = TCBStatus.SYN_RECEIVED

                // TODO: Set MSS for receiving larger packets from the device
                val responseBuffer = ByteBufferPool.acquire()
                referencePacket!!.updateTCPBuffer(
                    responseBuffer, (TCPHeader.Companion.SYN or TCPHeader.Companion.ACK).toByte(),
                    tcb.mySequenceNum, tcb.myAcknowledgementNum, 0
                )
                outputQueue.offer(responseBuffer)

                tcb.mySequenceNum++ // SYN counts as a byte
                key.interestOps(SelectionKey.OP_READ)
            }
        } catch (e: IOException) {
            LogUtils.e(TAG, "Connection error: " + tcb.ipAndPort, e)
            val responseBuffer = ByteBufferPool.acquire()
            referencePacket!!.updateTCPBuffer(
                responseBuffer,
                TCPHeader.Companion.RST.toByte(),
                0,
                tcb.myAcknowledgementNum,
                0
            )
            outputQueue.offer(responseBuffer)
            TCB.Companion.closeTCB(tcb)
        }
    }

    private fun processInput(key: SelectionKey, keyIterator: MutableIterator<SelectionKey>) {
        keyIterator.remove()
        val receiveBuffer = ByteBufferPool.acquire()

        // Leave space for the header
        val tcb = key.attachment() as TCB
        synchronized(tcb) {
            val referencePacket = tcb.referencePacket
            receiveBuffer.position(referencePacket!!.IP_TRAN_SIZE)
            val inputChannel = key.channel() as SocketChannel
            val readBytes: Int
            try {
                readBytes = inputChannel.read(receiveBuffer)
            } catch (e: IOException) {
                LogUtils.e(TAG, "Network read error: " + tcb.ipAndPort, e)
                referencePacket.updateTCPBuffer(
                    receiveBuffer,
                    TCPHeader.Companion.RST.toByte(),
                    0,
                    tcb.myAcknowledgementNum,
                    0
                )
                outputQueue.offer(receiveBuffer)
                TCB.Companion.closeTCB(tcb)
                return
            }
            if (readBytes == -1) {
                // End of stream, stop waiting until we push more data
                key.interestOps(0)
                tcb.waitingForNetworkData = false

                if (tcb.status != TCBStatus.CLOSE_WAIT) {
                    ByteBufferPool.release(receiveBuffer)
                    return
                }

                tcb.status = TCBStatus.LAST_ACK
                referencePacket.updateTCPBuffer(
                    receiveBuffer,
                    TCPHeader.Companion.FIN.toByte(),
                    tcb.mySequenceNum,
                    tcb.myAcknowledgementNum,
                    0
                )
                tcb.mySequenceNum++ // FIN counts as a byte
            } else {
                // XXX: We should ideally be splitting segments by MTU/MSS, but this seems to work without
                referencePacket.updateTCPBuffer(
                    receiveBuffer, (TCPHeader.Companion.PSH or TCPHeader.Companion.ACK).toByte(),
                    tcb.mySequenceNum, tcb.myAcknowledgementNum, readBytes
                )
                tcb.mySequenceNum += readBytes.toLong() // Next sequence number
                receiveBuffer.position(referencePacket.IP_TRAN_SIZE + readBytes)
            }
        }
        outputQueue.offer(receiveBuffer)
    }

    companion object {
        private val TAG: String = TCPInput::class.java.simpleName
    }
}
