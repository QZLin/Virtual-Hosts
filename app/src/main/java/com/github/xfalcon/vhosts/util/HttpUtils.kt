package com.github.xfalcon.vhosts.util

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

object HttpUtils {
    /**
     * Send a get request
     * @param url         Url as string
     * @param headers     Optional map with headers
     * @return response   Response as string
     * @throws IOException
     */
    /**
     * Send a get request
     * @param url
     * @return response
     * @throws IOException
     */
    @JvmOverloads
    @Throws(IOException::class)
    fun get(
        url: String?,
        headers: MutableMap<String?, String?>? = null
    ): String? {
        return fetch("GET", url, null, headers)
    }

    @Throws(IOException::class)
    fun fetch(
        method: String?, url: String?, body: String?,
        headers: MutableMap<String?, String?>?
    ): String? {
        // connection
        val u = URL(url)
        val conn = u.openConnection() as HttpURLConnection
        conn.setConnectTimeout(10000)
        conn.setReadTimeout(10000)

        // method
        if (method != null) {
            conn.setRequestMethod(method)
        }

        // headers
        if (headers != null) {
            for (key in headers.keys) {
                conn.addRequestProperty(key, headers.get(key))
            }
        }

        // body
        if (body != null) {
            conn.setDoOutput(true)
            val os = conn.getOutputStream()
            os.write(body.toByteArray())
            os.flush()
            os.close()
        }

        // response
        val `is` = conn.getInputStream()
        val response = streamToString(`is`)
        `is`.close()

        // handle redirects
        if (conn.getResponseCode() == 301) {
            val location = conn.getHeaderField("Location")
            return fetch(method, location, body, headers)
        }

        return response
    }

    /**
     * Read an input stream into a string
     * @param in
     * @return
     * @throws IOException
     */
    @Throws(IOException::class)
    fun streamToString(`in`: InputStream): String {
        val out = StringBuffer()
        val b = ByteArray(4096)
        var n: Int
        while ((`in`.read(b).also { n = it }) != -1) {
            out.append(String(b, 0, n))
        }
        return out.toString()
    }
}