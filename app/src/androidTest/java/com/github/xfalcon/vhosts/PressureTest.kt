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
package com.github.xfalcon.vhosts

import android.util.Log
import androidx.test.InstrumentationRegistry
import androidx.test.espresso.Espresso
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.rule.ActivityTestRule
import androidx.test.runner.AndroidJUnit4
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.MalformedURLException
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future


/**
 * Instrumentation test, which will execute on an Android device.
 *
 * @see [Testing documentation](http://d.android.com/tools/testing)
 */
@RunWith(AndroidJUnit4::class)
class PressureTest {
    @Rule
    var mActivityRule: ActivityTestRule<VhostsActivity?> = ActivityTestRule<VhostsActivity?>(
        VhostsActivity::class.java
    )

    @Test
    @Throws(Exception::class)
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getTargetContext()
        Assert.assertEquals("com.github.xfalcon.vhosts", appContext.getPackageName())
        Espresso.onView(ViewMatchers.withId(R.id.activity_main)).perform(ViewActions.click())
        Thread.sleep((1000 * 3).toLong())
        val list = ArrayList<Future<*>?>()
        val pool = Executors.newFixedThreadPool(100)
        for (i in 10..9999) {
            val domain = "$i.com"
            list.add(pool.submit(object : Thread() {
                override fun run() {
                    try {
                        val ip = InetAddress.getByName(domain)
                        Log.d("TEST", "$ip : $domain")
                    } catch (e: Exception) {
                    }
                }
            }))
        }
        val it = list.iterator()
        while (it.hasNext()) {
            it.next()!!.get()
        }
    }

    companion object {
        @Throws(IOException::class)
        private fun readStream(`is`: InputStream): String {
            val sb = StringBuilder()
            val r = BufferedReader(InputStreamReader(`is`), 1000)
            var line = r.readLine()
            while (line != null) {
                sb.append(line)
                line = r.readLine()
            }
            `is`.close()
            return sb.toString()
        }

        fun get_html(url_path: String?): String {
            // TODO Auto-generated method stub
            try {
                val url = URL(url_path)
                val urlConnection = url.openConnection() as HttpURLConnection
                urlConnection.addRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 6.3; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/46.0.2490.80 Safari/537.36"
                )
                val `in`: InputStream = BufferedInputStream(urlConnection.getInputStream())
                return readStream(`in`)
            } catch (e: MalformedURLException) {
                // TODO Auto-generated catch block
            } catch (e: IOException) {
                // TODO Auto-generated catch block
            }
            return ""
        }
    }
}
