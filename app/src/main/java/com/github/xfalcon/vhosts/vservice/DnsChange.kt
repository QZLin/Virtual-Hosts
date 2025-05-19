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
package com.github.xfalcon.vhosts.vservice

import com.github.xfalcon.vhosts.util.LogUtils
import org.xbill.DNS.AAAARecord
import org.xbill.DNS.ARecord
import org.xbill.DNS.Address
import org.xbill.DNS.Flags
import org.xbill.DNS.Message
import org.xbill.DNS.Record
import org.xbill.DNS.Type
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

object DnsChange {
    var TAG: String = DnsChange::class.java.simpleName
    var DOMAINS_IP_MAPS4: ConcurrentHashMap<String?, String?>? = null
    var DOMAINS_IP_MAPS6: ConcurrentHashMap<String?, String?>? = null


    fun handle_dns_packet(packet: Packet): ByteBuffer? {
        if (DOMAINS_IP_MAPS4 == null) {
            LogUtils.d(TAG, "DOMAINS_IP_MAPS IS　NULL　HOST FILE ERROR")
            return null
        }
        try {
            val packet_buffer = packet.backingBuffer
            packet_buffer.mark()
            val tmp_bytes = ByteArray(packet_buffer.remaining())
            packet_buffer.get(tmp_bytes)
            packet_buffer.reset()
            val message = Message(tmp_bytes)
            val question = message.getQuestion()
            val DOMAINS_IP_MAPS: ConcurrentHashMap<String?, String?>?
            val type = question.getType()
            if (type == Type.A) DOMAINS_IP_MAPS = DOMAINS_IP_MAPS4
            else if (type == Type.AAAA) DOMAINS_IP_MAPS = DOMAINS_IP_MAPS6
            else return null
            val query_domain = message.getQuestion().getName()
            var query_string = query_domain.toString()
            LogUtils.d(TAG, "query: " + question.getType() + " :" + query_string)
            if (!DOMAINS_IP_MAPS!!.containsKey(query_string)) {
                query_string = "." + query_string
                var j = 0
                while (true) {
                    val i = query_string.indexOf(".", j)
                    if (i == -1) {
                        return null
                    }
                    val str = query_string.substring(i)

                    if ("." == str || "" == str) {
                        return null
                    }
                    if (DOMAINS_IP_MAPS.containsKey(str)) {
                        query_string = str
                        break
                    }
                    j = i + 1
                }
            }
            val address = Address.getByAddress(DOMAINS_IP_MAPS.get(query_string))
            val record: Record?
            if (type == Type.A) record = ARecord(query_domain, 1, 86400, address)
            else record = AAAARecord(query_domain, 1, 86400, address)
            message.addRecord(record, 1)
            message.header.setFlag(Flags.QR.toInt())
            packet_buffer.limit(packet_buffer.capacity())
            packet_buffer.put(message.toWire())
            packet_buffer.limit(packet_buffer.position())
            packet_buffer.reset()
            packet.swapSourceAndDestination()
            packet.updateUDPBuffer(packet_buffer, packet_buffer.remaining())
            packet_buffer.position(packet_buffer.limit())
            LogUtils.d(
                TAG,
                "hit: " + question.getType() + " :" + query_domain.toString() + " :" + address.hostName
            )
            return packet_buffer
        } catch (e: Exception) {
            LogUtils.d(TAG, "dns hook error", e)
            return null
        }
    }

    fun handle_hosts(inputStream: InputStream): Int {
        val STR_COMMENT = "#"
        val HOST_PATTERN_STR =
            "^\\s*(" + STR_COMMENT + "?)\\s*(\\S*)\\s*([^" + STR_COMMENT + "]*)" + STR_COMMENT + "?(.*)$"
        val HOST_PATTERN = Pattern.compile(HOST_PATTERN_STR)
        try {
            val reader = BufferedReader(InputStreamReader(inputStream))
            var line = ""
            DOMAINS_IP_MAPS4 = ConcurrentHashMap<String?, String?>()
            DOMAINS_IP_MAPS6 = ConcurrentHashMap<String?, String?>()
            while (!Thread.interrupted() && (reader.readLine().also { line = it }) != null) {
                if (line.length > 1000 || line.startsWith(STR_COMMENT)) continue
                val matcher = HOST_PATTERN.matcher(line)
                if (matcher.find()) {
                    val ip = matcher.group(2).trim { it <= ' ' }
                    try {
                        Address.getByAddress(ip)
                    } catch (e: Exception) {
                        continue
                    }
                    if (ip.contains(":")) {
                        DOMAINS_IP_MAPS6!!.put(matcher.group(3).trim { it <= ' ' } + ".", ip)
                    } else {
                        DOMAINS_IP_MAPS4!!.put(matcher.group(3).trim { it <= ' ' } + ".", ip)
                    }
                }
            }
            reader.close()
            inputStream.close()
            LogUtils.d(TAG, DOMAINS_IP_MAPS4.toString())
            LogUtils.d(TAG, DOMAINS_IP_MAPS6.toString())
            return DOMAINS_IP_MAPS4!!.size + DOMAINS_IP_MAPS6!!.size
        } catch (e: IOException) {
            LogUtils.d(TAG, "Hook dns error", e)
            return 0
        }
    }
}
