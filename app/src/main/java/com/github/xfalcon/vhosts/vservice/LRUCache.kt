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

class LRUCache<K, V>(private val maxSize: Int, private val callback: CleanupCallback<K, V>) :
    LinkedHashMap<K, V>(
        maxSize + 1, 1f, true
    ) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean {
        if (size > maxSize) {
            callback.cleanup(eldest)
            return true
        }
        return false
    }

    interface CleanupCallback<K, V> {
        fun cleanup(eldest: MutableMap.MutableEntry<K, V>)
    }
}
