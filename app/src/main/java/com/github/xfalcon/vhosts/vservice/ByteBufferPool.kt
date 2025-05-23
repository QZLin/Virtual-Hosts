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

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

object ByteBufferPool {
    private const val BUFFER_SIZE = 16384 // XXX: Is this ideal?
    private val pool = ConcurrentLinkedQueue<ByteBuffer?>()

    fun acquire(): ByteBuffer {
        var buffer = pool.poll()
        if (buffer == null) buffer =
            ByteBuffer.allocateDirect(BUFFER_SIZE) // Using DirectBuffer for zero-copy

        return buffer
    }

    fun release(buffer: ByteBuffer) {
        buffer.clear()
        pool.offer(buffer)
    }

    fun clear() {
        pool.clear()
    }
}
