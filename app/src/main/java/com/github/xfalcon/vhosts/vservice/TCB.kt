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

import com.github.xfalcon.vhosts.vservice.LRUCache.CleanupCallback
import java.io.IOException
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel

/**
 * Transmission Control Block
 */
class TCB
    (
    var ipAndPort: String?,
    var mySequenceNum: Long,
    var theirSequenceNum: Long,
    var myAcknowledgementNum: Long,
    var theirAcknowledgementNum: Long,
    var channel: SocketChannel,
    var referencePacket: Packet?
) {
    var status: TCBStatus? = null

    // TCP has more states, but we need only these
    enum class TCBStatus {
        SYN_SENT,
        SYN_RECEIVED,
        ESTABLISHED,
        CLOSE_WAIT,
        LAST_ACK,
    }

    var waitingForNetworkData: Boolean = false
    var selectionKey: SelectionKey? = null

    private fun closeChannel() {
        try {
            channel.close()
        } catch (e: IOException) {
            // Ignore
        }
    }

    companion object {
        private const val MAX_CACHE_SIZE = 50 // XXX: Is this ideal?
        private val tcbCache =
            LRUCache<String?, TCB?>(MAX_CACHE_SIZE, object : CleanupCallback<String?, TCB?> {
                override fun cleanup(eldest: MutableMap.MutableEntry<String?, TCB?>) {
                    eldest.value!!.closeChannel()
                }
            })

        fun getTCB(ipAndPort: String?): TCB? {
            synchronized(tcbCache) {
                return tcbCache.get(ipAndPort)
            }
        }

        fun putTCB(ipAndPort: String?, tcb: TCB?) {
            synchronized(tcbCache) {
                tcbCache.put(ipAndPort, tcb)
            }
        }

        fun closeTCB(tcb: TCB) {
            tcb.closeChannel()
            synchronized(tcbCache) {
                tcbCache.remove(tcb.ipAndPort)
            }
        }

        fun closeAll() {
            synchronized(tcbCache) {
                val it: MutableIterator<MutableMap.MutableEntry<String?, TCB?>?> =
                    tcbCache.entries.iterator()
                while (it.hasNext()) {
                    it.next()!!.value!!.closeChannel()
                    it.remove()
                }
            }
        }
    }
}
