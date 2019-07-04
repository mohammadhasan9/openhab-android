/*
 * Copyright (c) 2010-2019 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.openhab.habdroid.util

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.MulticastLock
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

class AsyncServiceResolver(
    context: Context,
    private val serviceType: String,
    private val scope: CoroutineScope
) : ServiceListener {
    // Multicast lock for mDNS
    private val multicastLock: MulticastLock
    // mDNS service
    private var jmdns: JmDNS? = null
    private val serviceInfoChannel = Channel<ServiceInfo>(0)

    private val localIpv4Address: InetAddress? get() {
        try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                val enumIpAddr = intf.inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    Log.i(TAG, "IP: ${inetAddress.hostAddress}")
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                        Log.i(TAG, "Selected ${inetAddress.getHostAddress()}")
                        return inetAddress
                    }
                }
            }
        } catch (ex: SocketException) {
            Log.e(TAG, ex.toString())
        }

        return null
    }

    init {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("HABDroidMulticastLock")
        multicastLock.setReferenceCounted(true)
    }

    suspend fun resolve(): ServiceInfo? {
        try {
            multicastLock.acquire()
        } catch (e: SecurityException) {
            Log.i(TAG, "Could not acquire multicast lock", e)
        }

        Log.i(TAG, "Discovering service $serviceType")

        withContext(Dispatchers.IO) {
            jmdns = JmDNS.create(localIpv4Address)
            jmdns?.addServiceListener(serviceType, this@AsyncServiceResolver)
        }

        val info = withTimeoutOrNull(DEFAULT_DISCOVERY_TIMEOUT) {
            serviceInfoChannel.receive()
        }

        multicastLock.release()
        jmdns?.close()
        return info
    }

    override fun serviceAdded(event: ServiceEvent) {
        Log.d(TAG, "Service added ${event.name}")
        jmdns?.requestServiceInfo(event.type, event.name, 1)
    }

    override fun serviceRemoved(event: ServiceEvent) {}

    override fun serviceResolved(event: ServiceEvent) {
        scope.launch {
            serviceInfoChannel.offer(event.info)
        }
    }

    companion object {
        private val TAG = AsyncServiceResolver::class.java.simpleName

        private const val DEFAULT_DISCOVERY_TIMEOUT = 3000L
    }
}