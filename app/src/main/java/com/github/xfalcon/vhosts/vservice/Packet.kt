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
import java.net.InetAddress
import java.nio.ByteBuffer


/**
 * Representation of an IP Packet
 */
// TODO: Reduce public mutability
class Packet(buffer: ByteBuffer) {
    private var IP_HEADER_SIZE = 0
    var IP_TRAN_SIZE: Int = 0
    var ipHeader: IPHeader? = null
    var tcpHeader: TCPHeader? = null
    var udpHeader: UDPHeader? = null
    var backingBuffer: ByteBuffer

    var isTCP: Boolean = false
        private set
    var isUDP: Boolean = false
        private set

    init {
        val versionAndIHL = buffer.get()
        val version = (versionAndIHL.toInt() shr 4).toByte()
        if (version.toInt() == 4) {
            IP_HEADER_SIZE = IP4_HEADER_SIZE
            val IHL = (versionAndIHL.toInt() and 0x0F).toByte()
            val headerLength = IHL.toInt() shl 2
            this.ipHeader = IP4Header(buffer, version, IHL, headerLength)
        } else if (version.toInt() == 6) {
            IP_HEADER_SIZE = IP6_HEADER_SIZE
            this.ipHeader = IP6Header(buffer, version)
        } else {
            LogUtils.d("Un Know Packet", version.toString() + "")
            this.isTCP = false
            this.isUDP = false
//            return
        }
        if (this.ipHeader!!.protocol.toInt() == TCP) {
            this.tcpHeader = TCPHeader(buffer)
            this.isTCP = true
            this.IP_TRAN_SIZE = IP_HEADER_SIZE + TCP_HEADER_SIZE
        } else if (ipHeader!!.protocol.toInt() == UDP) {
            this.udpHeader = UDPHeader(buffer)
            this.isUDP = true
            this.IP_TRAN_SIZE = IP_HEADER_SIZE + UDP_HEADER_SIZE
        }
        this.backingBuffer = buffer
    }

    override fun toString(): String {
        val sb = StringBuilder("Packet{")
        sb.append("IpHeader=").append(ipHeader)
        if (isTCP) sb.append(", tcpHeader=").append(tcpHeader)
        else if (isUDP) sb.append(", udpHeader=").append(udpHeader)
        sb.append(", payloadSize=").append(backingBuffer.limit() - backingBuffer.position())
        sb.append('}')
        return sb.toString()
    }

    fun swapSourceAndDestination() {
        val newSourceAddress = ipHeader!!.destinationAddress!!
        ipHeader!!.destinationAddress = ipHeader!!.sourceAddress
        ipHeader!!.sourceAddress = newSourceAddress

        if (isUDP) {
            val newSourcePort = udpHeader!!.destinationPort
            udpHeader!!.destinationPort = udpHeader!!.sourcePort
            udpHeader!!.sourcePort = newSourcePort
        } else if (isTCP) {
            val newSourcePort = tcpHeader!!.destinationPort
            tcpHeader!!.destinationPort = tcpHeader!!.sourcePort
            tcpHeader!!.sourcePort = newSourcePort
        }
    }

    fun updateTCPBuffer(
        buffer: ByteBuffer,
        flags: Byte,
        sequenceNum: Long,
        ackNum: Long,
        payloadSize: Int
    ) {
        buffer.position(0)
        fillHeader(buffer)
        backingBuffer = buffer

        tcpHeader!!.flags = flags
        backingBuffer.put(IP_HEADER_SIZE + 13, flags)

        tcpHeader!!.sequenceNumber = sequenceNum
        backingBuffer.putInt(IP_HEADER_SIZE + 4, sequenceNum.toInt())

        tcpHeader!!.acknowledgementNumber = ackNum
        backingBuffer.putInt(IP_HEADER_SIZE + 8, ackNum.toInt())

        // Reset header size, since we don't need options
        val dataOffset = (TCP_HEADER_SIZE shl 2).toByte()
        tcpHeader!!.dataOffsetAndReserved = dataOffset
        backingBuffer.put(IP_HEADER_SIZE + 12, dataOffset)
        checksum(payloadSize)
        val totalLength: Int = TCP_HEADER_SIZE + payloadSize
        ipHeader!!.updateIpHeader(this, totalLength)
    }

    fun updateUDPBuffer(buffer: ByteBuffer, payloadSize: Int) {
        buffer.position(0)
        fillHeader(buffer)
        backingBuffer = buffer

        val udpTotalLength: Int = UDP_HEADER_SIZE + payloadSize
        backingBuffer.putShort(IP_HEADER_SIZE + 4, udpTotalLength.toShort())
        udpHeader!!.length = udpTotalLength
        checksum(payloadSize)
        ipHeader!!.updateIpHeader(this, udpTotalLength)
    }

    private fun fillHeader(buffer: ByteBuffer) {
        ipHeader!!.fillHeader(buffer)
        if (isUDP) udpHeader!!.fillHeader(buffer)
        else if (isTCP) tcpHeader!!.fillHeader(buffer)
    }

    private fun checksum(payloadSize: Int) {
        var sum = 0
        var length: Int
        val pos: Int
        if (this.isTCP) {
            length = TCP_HEADER_SIZE + payloadSize
            pos = 16
        } else {
            length = UDP_HEADER_SIZE + payloadSize
            pos = 6
        }
        var buffer: ByteBuffer?
        // Calculate pseudo-header checksum
        if (this.ipHeader!!.version.toInt() == 4) {
            if (this.isUDP) {
                backingBuffer.putShort(IP_HEADER_SIZE + 6, 0.toShort())
                udpHeader!!.checksum = 0
                return
            }
            buffer = ByteBuffer.wrap(ipHeader!!.sourceAddress!!.address)
            sum =
                BitUtils.getUnsignedShort(buffer.getShort()) + BitUtils.getUnsignedShort(buffer.getShort())
            buffer = ByteBuffer.wrap(ipHeader!!.destinationAddress!!.address)
            sum += BitUtils.getUnsignedShort(buffer.getShort()) + BitUtils.getUnsignedShort(buffer.getShort())
            sum += ipHeader!!.protocol + length
        } else if (this.ipHeader!!.version.toInt() == 6) {
            val bbLength = 38 // IPv6 src + dst + nextHeader (with padding) + length 16+16+2+4
            buffer = ByteBufferPool.acquire()
            buffer.put(ipHeader!!.sourceAddress!!.address)
            buffer.put(ipHeader!!.destinationAddress!!.address)
            buffer.put(0.toByte()) // padding
            buffer.put(ipHeader!!.protocol)
            buffer.putInt(length)
            buffer.rewind()
            for (i in 0..<bbLength / 2) {
                sum += 0xffff and buffer.getShort().toInt()
            }
            ByteBufferPool.release(buffer)
        }
        buffer = backingBuffer.duplicate()
        // Clear previous checksum
        buffer.putShort(IP_HEADER_SIZE + pos, 0.toShort())

        // Calculate TCP segment checksum
        buffer.position(IP_HEADER_SIZE)
        while (length > 1) {
            sum += BitUtils.getUnsignedShort(buffer.getShort())
            length -= 2
        }
        if (length > 0) sum += BitUtils.getUnsignedByte(buffer.get()).toInt() shl 8

        while (sum shr 16 > 0) sum = (sum and 0xFFFF) + (sum shr 16)

        sum = sum.inv()
        if (this.isUDP) udpHeader!!.checksum = sum
        else tcpHeader!!.checksum = sum
        backingBuffer.putShort(IP_HEADER_SIZE + pos, sum.toShort())
    }

    open class IPHeader {
        var version: Byte = 0
        var protocol: Byte = 0
        var sourceAddress: InetAddress? = null
        var destinationAddress: InetAddress? = null
        var totalLength: Int = 0

        open fun fillHeader(buffer: ByteBuffer?) {
        }

        open fun updateIpHeader(packet: Packet?, totalLength: Int) {
        }
    }

    class IP4Header(
        buffer: ByteBuffer,
        version: Byte,
        IHL: Byte,
        headerLength: Int
    ) : IPHeader() {
        private val IHL: Byte
        private val headerLength: Int
        private val typeOfService: Short

        private val identificationAndFlagsAndFragmentOffset: Int

        private val TTL: Short
        private var headerChecksum: Int
        var optionsAndPadding: Int = 0


        init {
            this.version = version
            this.IHL = IHL
            this.headerLength = headerLength

            this.typeOfService = BitUtils.getUnsignedByte(buffer.get())
            this.totalLength = BitUtils.getUnsignedShort(buffer.getShort())

            this.identificationAndFlagsAndFragmentOffset = buffer.getInt()

            this.TTL = BitUtils.getUnsignedByte(buffer.get())
            this.protocol = buffer.get()
            this.headerChecksum = BitUtils.getUnsignedShort(buffer.getShort())
            buffer.get(addressBytes)
            this.sourceAddress = InetAddress.getByAddress(addressBytes)
            buffer.get(addressBytes)
            this.destinationAddress = InetAddress.getByAddress(addressBytes)

            //this.optionsAndPadding = buffer.getInt();
        }

        override fun fillHeader(buffer: ByteBuffer?) {
            buffer!!.put((this.version.toInt() shl 4 or this.IHL.toInt()).toByte())
            buffer.put(this.typeOfService.toByte())
            buffer.putShort(this.totalLength.toShort())

            buffer.putInt(this.identificationAndFlagsAndFragmentOffset)

            buffer.put(this.TTL.toByte())
            buffer.put(this.protocol)
            buffer.putShort(this.headerChecksum.toShort())

            buffer.put(this.sourceAddress!!.address)
            buffer.put(this.destinationAddress!!.address)
        }

        override fun updateIpHeader(packet: Packet?, tcpPayLength: Int) {
            this.totalLength = packet!!.IP_HEADER_SIZE + tcpPayLength
            packet.backingBuffer.putShort(2, this.totalLength.toShort())
            val buffer = packet.backingBuffer.duplicate()
            buffer.position(0)

            // Clear previous checksum
            buffer.putShort(10, 0.toShort())

            var ipLength = headerLength
            var sum = 0
            while (ipLength > 0) {
                sum += BitUtils.getUnsignedShort(buffer.getShort())
                ipLength -= 2
            }
            while (sum shr 16 > 0) sum = (sum and 0xFFFF) + (sum shr 16)

            sum = sum.inv()
            headerChecksum = sum
            packet.backingBuffer.putShort(10, sum.toShort())
        }

        override fun toString(): String {
            val sb = StringBuilder("IP4Header{")
            sb.append("version=").append(version.toInt())
            sb.append(", IHL=").append(IHL.toInt())
            sb.append(", typeOfService=").append(typeOfService.toInt())
            sb.append(", totalLength=").append(totalLength)
            sb.append(", identificationAndFlagsAndFragmentOffset=")
                .append(identificationAndFlagsAndFragmentOffset)
            sb.append(", TTL=").append(TTL.toInt())
            sb.append(", protocol=").append(protocol.toInt())
            sb.append(", headerChecksum=").append(headerChecksum)
            sb.append(", sourceAddress=").append(sourceAddress!!.hostAddress)
            sb.append(", destinationAddress=").append(destinationAddress!!.hostAddress)
            sb.append('}')
            return sb.toString()
        }

        companion object {
            private val addressBytes = ByteArray(4)
        }
    }

    class IP6Header constructor(buffer: ByteBuffer, version: Byte) : IPHeader() {
        private val versionTrafficFlowLabel: Long
        private val hotLimit: Byte

        init {
            this.version = version
            buffer.position(0)
            this.versionTrafficFlowLabel = BitUtils.getUnsignedInt(buffer.getInt())
            //ipv6 payload == totalLength
            this.totalLength = BitUtils.getUnsignedShort(buffer.getShort())
            this.protocol = buffer.get()
            this.hotLimit = buffer.get()
            buffer.get(addressBytes)
            this.sourceAddress = InetAddress.getByAddress(addressBytes)
            buffer.get(addressBytes)
            this.destinationAddress = InetAddress.getByAddress(addressBytes)
            //this.optionsAndPadding = buffer.getInt();
        }

        override fun updateIpHeader(packet: Packet?, totalLength: Int) {
            packet!!.backingBuffer.putShort(4, totalLength.toShort())
            this.totalLength = totalLength
        }

        override fun fillHeader(buffer: ByteBuffer?) {
            buffer!!.putInt(this.versionTrafficFlowLabel.toInt())
            buffer.putShort(this.totalLength.toShort())
            buffer.put(this.protocol)
            buffer.put(this.hotLimit)
            buffer.put((this.sourceAddress!!.address))
            buffer.put(this.destinationAddress!!.address)
        }

        override fun toString(): String {
            val sb = StringBuilder("IP6Header{")
            sb.append("version=").append(version.toInt())
            sb.append(", trafficClassFlowLable=").append(versionTrafficFlowLabel)
            sb.append(", payload=").append(totalLength)
            sb.append(", protocol=").append(protocol.toInt())
            sb.append(", hotLimit=").append(hotLimit.toInt())
            sb.append(", sourceAddress=").append(sourceAddress!!.hostAddress)
            sb.append(", destinationAddress=").append(destinationAddress!!.hostAddress)
            sb.append('}')
            return sb.toString()
        }

        companion object {
            private val addressBytes = ByteArray(16)
        }
    }

    class TCPHeader constructor(buffer: ByteBuffer) {
        var sourcePort: Int
        var destinationPort: Int

        var sequenceNumber: Long
        var acknowledgementNumber: Long

        var dataOffsetAndReserved: Byte
        val headerLength: Int
        var flags: Byte
        val window: Int

        var checksum: Int
        val urgentPointer: Int

        var optionsAndPadding: ByteArray? = null

        init {
            this.sourcePort = BitUtils.getUnsignedShort(buffer.getShort())
            this.destinationPort = BitUtils.getUnsignedShort(buffer.getShort())

            this.sequenceNumber = BitUtils.getUnsignedInt(buffer.getInt())
            this.acknowledgementNumber = BitUtils.getUnsignedInt(buffer.getInt())

            this.dataOffsetAndReserved = buffer.get()
            this.headerLength = (this.dataOffsetAndReserved.toInt() and 0xF0) shr 2
            this.flags = buffer.get()
            this.window = BitUtils.getUnsignedShort(buffer.getShort())

            this.checksum = BitUtils.getUnsignedShort(buffer.getShort())
            this.urgentPointer = BitUtils.getUnsignedShort(buffer.getShort())

            val optionsLength: Int = this.headerLength - TCP_HEADER_SIZE
            if (optionsLength > 0) {
                optionsAndPadding = ByteArray(optionsLength)
                buffer.get(optionsAndPadding, 0, optionsLength)
            }
        }

        val isFIN: Boolean
            get() = (flags.toInt() and FIN) == FIN

        val isSYN: Boolean
            get() = (flags.toInt() and SYN) == SYN

        val isRST: Boolean
            get() = (flags.toInt() and RST) == RST

        val isPSH: Boolean
            get() = (flags.toInt() and PSH) == PSH

        val isACK: Boolean
            get() = (flags.toInt() and ACK) == ACK

        val isURG: Boolean
            get() = (flags.toInt() and URG) == URG

        fun fillHeader(buffer: ByteBuffer) {
            buffer.putShort(sourcePort.toShort())
            buffer.putShort(destinationPort.toShort())

            buffer.putInt(sequenceNumber.toInt())
            buffer.putInt(acknowledgementNumber.toInt())

            buffer.put(dataOffsetAndReserved)
            buffer.put(flags)
            buffer.putShort(window.toShort())

            buffer.putShort(checksum.toShort())
            buffer.putShort(urgentPointer.toShort())
        }

        override fun toString(): String {
            val sb = StringBuilder("TCPHeader{")
            sb.append("sourcePort=").append(sourcePort)
            sb.append(", destinationPort=").append(destinationPort)
            sb.append(", sequenceNumber=").append(sequenceNumber)
            sb.append(", acknowledgementNumber=").append(acknowledgementNumber)
            sb.append(", headerLength=").append(headerLength)
            sb.append(", window=").append(window)
            sb.append(", checksum=").append(checksum)
            sb.append(", flags=")
            if (this.isFIN) sb.append(" FIN")
            if (this.isSYN) sb.append(" SYN")
            if (this.isRST) sb.append(" RST")
            if (this.isPSH) sb.append(" PSH")
            if (this.isACK) sb.append(" ACK")
            if (this.isURG) sb.append(" URG")
            sb.append('}')
            return sb.toString()
        }

        companion object {
            const val FIN: Int = 0x01
            const val SYN: Int = 0x02
            const val RST: Int = 0x04
            const val PSH: Int = 0x08
            const val ACK: Int = 0x10
            const val URG: Int = 0x20
        }
    }

    class UDPHeader constructor(buffer: ByteBuffer) {
        var sourcePort: Int
        var destinationPort: Int

        var length: Int
        var checksum: Int

        init {
            this.sourcePort = BitUtils.getUnsignedShort(buffer.getShort())
            this.destinationPort = BitUtils.getUnsignedShort(buffer.getShort())

            this.length = BitUtils.getUnsignedShort(buffer.getShort())
            this.checksum = BitUtils.getUnsignedShort(buffer.getShort())
        }

        fun fillHeader(buffer: ByteBuffer) {
            buffer.putShort(this.sourcePort.toShort())
            buffer.putShort(this.destinationPort.toShort())

            buffer.putShort(this.length.toShort())
            buffer.putShort(this.checksum.toShort())
        }

        override fun toString(): String {
            val sb = StringBuilder("UDPHeader{")
            sb.append("sourcePort=").append(sourcePort)
            sb.append(", destinationPort=").append(destinationPort)
            sb.append(", length=").append(length)
            sb.append(", checksum=").append(checksum)
            sb.append('}')
            return sb.toString()
        }
    }

    private object BitUtils {
        fun getUnsignedByte(value: Byte): Short {
            return (value.toInt() and 0xFF).toShort()
        }

        fun getUnsignedShort(value: Short): Int {
            return value.toInt() and 0xFFFF
        }

        fun getUnsignedInt(value: Int): Long {
            return value.toLong() and 0xFFFFFFFFL
        }
    }

    companion object {
        private const val IP4_HEADER_SIZE = 20
        private const val IP6_HEADER_SIZE = 40
        private const val TCP_HEADER_SIZE = 20
        private const val UDP_HEADER_SIZE = 8
        private const val TCP = 6
        private const val UDP = 17
    }
}
