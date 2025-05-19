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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.Random
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock


class TCPOutput(
    private val inputQueue: ConcurrentLinkedQueue<Packet?>,
    private val outputQueue: ConcurrentLinkedQueue<ByteBuffer?>,
    private val selector: Selector,
    private val tcpSelectorLock: ReentrantLock,
    private val vpnService: VhostsService
) : Runnable {
    private val random = Random()

    override fun run() {
        LogUtils.i(TAG, "Started")
        try {
            while (!Thread.interrupted()) {
                val currentPacket = inputQueue.poll()
                if (currentPacket == null) {
                    Thread.sleep(11)
                    continue
                }

                val payloadBuffer = currentPacket.backingBuffer
//                currentPacket.backingBuffer = null
                val responseBuffer = ByteBufferPool.acquire()

                val destinationAddress = currentPacket.ipHeader!!.destinationAddress

                val tcpHeader = currentPacket.tcpHeader
                val destinationPort = tcpHeader!!.destinationPort
                val sourcePort = tcpHeader.sourcePort

                val ipAndPort = destinationAddress!!.hostAddress + ":" +
                        destinationPort + ":" + sourcePort
                val tcb: TCB? = TCB.Companion.getTCB(ipAndPort)
                if (tcb == null) initializeConnection(
                    ipAndPort, destinationAddress, destinationPort,
                    currentPacket, tcpHeader, responseBuffer
                )
                else if (tcpHeader.isSYN) processDuplicateSYN(tcb, tcpHeader, responseBuffer)
                else if (tcpHeader.isRST) closeCleanly(tcb, responseBuffer)
                else if (tcpHeader.isFIN) processFIN(tcb, tcpHeader, responseBuffer)
                else if (tcpHeader.isACK) processACK(
                    tcb,
                    tcpHeader,
                    payloadBuffer,
                    responseBuffer
                )

                // XXX: cleanup later
                if (responseBuffer.position() == 0) ByteBufferPool.release(responseBuffer)
                ByteBufferPool.release(payloadBuffer)
            }
        } catch (e: InterruptedException) {
            LogUtils.i(TAG, "Stopping")
        } catch (e: IOException) {
            LogUtils.e(TAG, e.toString(), e)
        } finally {
            TCB.Companion.closeAll()
        }
    }

    @Throws(IOException::class)
    private fun initializeConnection(
        ipAndPort: String?, destinationAddress: InetAddress?, destinationPort: Int,
        currentPacket: Packet, tcpHeader: TCPHeader, responseBuffer: ByteBuffer
    ) {
        currentPacket.swapSourceAndDestination()
        if (tcpHeader.isSYN) {
            val outputChannel = SocketChannel.open()
            outputChannel.configureBlocking(false)
            vpnService.protect(outputChannel.socket())

            val tcb = TCB(
                ipAndPort,
                random.nextInt(Short.Companion.MAX_VALUE + 1).toLong(),
                tcpHeader.sequenceNumber,
                tcpHeader.sequenceNumber + 1,
                tcpHeader.acknowledgementNumber,
                outputChannel,
                currentPacket
            )
            TCB.Companion.putTCB(ipAndPort, tcb)

            try {
                outputChannel.connect(InetSocketAddress(destinationAddress, destinationPort))
                if (outputChannel.finishConnect()) {
                    tcb.status = TCBStatus.SYN_RECEIVED
                    // TODO: Set MSS for receiving larger packets from the device
                    currentPacket.updateTCPBuffer(
                        responseBuffer,
                        (TCPHeader.Companion.SYN or TCPHeader.Companion.ACK).toByte(),
                        tcb.mySequenceNum,
                        tcb.myAcknowledgementNum,
                        0
                    )
                    tcb.mySequenceNum++ // SYN counts as a byte
                } else {
                    tcb.status = TCBStatus.SYN_SENT
                    tcpSelectorLock.lock()
                    selector.wakeup()
                    tcb.selectionKey =
                        outputChannel.register(selector, SelectionKey.OP_CONNECT, tcb)
                    tcpSelectorLock.unlock()
                    return
                }
            } catch (e: IOException) {
                LogUtils.e(TAG, "Connection error: " + ipAndPort, e)
                currentPacket.updateTCPBuffer(
                    responseBuffer,
                    TCPHeader.Companion.RST.toByte(),
                    0,
                    tcb.myAcknowledgementNum,
                    0
                )
                TCB.Companion.closeTCB(tcb)
            }
        } else {
            currentPacket.updateTCPBuffer(
                responseBuffer, TCPHeader.Companion.RST.toByte(),
                0, tcpHeader.sequenceNumber + 1, 0
            )
        }
        outputQueue.offer(responseBuffer)
    }

    private fun processDuplicateSYN(tcb: TCB, tcpHeader: TCPHeader, responseBuffer: ByteBuffer) {
        synchronized(tcb) {
            if (tcb.status == TCBStatus.SYN_SENT) {
                tcb.myAcknowledgementNum = tcpHeader.sequenceNumber + 1
                return
            }
        }
        sendRST(tcb, 1, responseBuffer)
    }

    private fun processFIN(tcb: TCB, tcpHeader: TCPHeader, responseBuffer: ByteBuffer) {
        synchronized(tcb) {
            val referencePacket = tcb.referencePacket
            tcb.myAcknowledgementNum = tcpHeader.sequenceNumber + 1
            tcb.theirAcknowledgementNum = tcpHeader.acknowledgementNumber
            if (tcb.waitingForNetworkData) {
                tcb.status = TCBStatus.CLOSE_WAIT
                referencePacket!!.updateTCPBuffer(
                    responseBuffer, TCPHeader.Companion.ACK.toByte(),
                    tcb.mySequenceNum, tcb.myAcknowledgementNum, 0
                )
            } else {
                tcb.status = TCBStatus.LAST_ACK
                referencePacket!!.updateTCPBuffer(
                    responseBuffer, (TCPHeader.Companion.FIN or TCPHeader.Companion.ACK).toByte(),
                    tcb.mySequenceNum, tcb.myAcknowledgementNum, 0
                )
                tcb.mySequenceNum++ // FIN counts as a byte
            }
        }
        outputQueue.offer(responseBuffer)
    }

    @Throws(IOException::class)
    private fun processACK(
        tcb: TCB,
        tcpHeader: TCPHeader,
        payloadBuffer: ByteBuffer,
        responseBuffer: ByteBuffer
    ) {
        val payloadSize = payloadBuffer.limit() - payloadBuffer.position()

        synchronized(tcb) {
            val outputChannel = tcb.channel
            if (tcb.status == TCBStatus.SYN_RECEIVED) {
                tcb.status = TCBStatus.ESTABLISHED
                tcpSelectorLock.lock()
                selector.wakeup()
                tcb.selectionKey = outputChannel.register(selector, SelectionKey.OP_READ, tcb)
                tcpSelectorLock.unlock()
                tcb.waitingForNetworkData = true
            } else if (tcb.status == TCBStatus.LAST_ACK) {
                closeCleanly(tcb, responseBuffer)
                return
            }

            if (payloadSize == 0) return  // Empty ACK, ignore


            if (!tcb.waitingForNetworkData) {
                selector.wakeup()
                tcb.selectionKey!!.interestOps(SelectionKey.OP_READ)
                tcb.waitingForNetworkData = true
            }

            // Forward to remote server
            try {
                while (payloadBuffer.hasRemaining()) outputChannel.write(payloadBuffer)
            } catch (e: IOException) {
                LogUtils.e(TAG, "Network write error: " + tcb.ipAndPort, e)
                sendRST(tcb, payloadSize, responseBuffer)
                return
            }

            // TODO: We don't expect out-of-order packets, but verify
            tcb.myAcknowledgementNum = tcpHeader.sequenceNumber + payloadSize
            tcb.theirAcknowledgementNum = tcpHeader.acknowledgementNumber
            val referencePacket = tcb.referencePacket
            referencePacket!!.updateTCPBuffer(
                responseBuffer,
                TCPHeader.Companion.ACK.toByte(),
                tcb.mySequenceNum,
                tcb.myAcknowledgementNum,
                0
            )
        }
        outputQueue.offer(responseBuffer)
    }

    private fun sendRST(tcb: TCB, prevPayloadSize: Int, buffer: ByteBuffer) {
        tcb.referencePacket!!.updateTCPBuffer(
            buffer,
            TCPHeader.Companion.RST.toByte(),
            0,
            tcb.myAcknowledgementNum + prevPayloadSize,
            0
        )
        outputQueue.offer(buffer)
        TCB.Companion.closeTCB(tcb)
    }

    private fun closeCleanly(tcb: TCB, buffer: ByteBuffer) {
        ByteBufferPool.release(buffer)
        TCB.Companion.closeTCB(tcb)
    }

    companion object {
        private val TAG: String = TCPOutput::class.java.simpleName
    }
}
