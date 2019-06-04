package nl.rogro82.pipup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.res.Resources
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface.getNetworkInterfaces
import java.net.SocketException

object Utils {
    fun getIpAddress(): String? {
        try {
            val interfaces = getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (ex: SocketException) {}

        return null
    }
}

fun Context.initNotificationChannel(id: String, name: String, description: String) {
    if (Build.VERSION.SDK_INT < 26) {
        return
    }
    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channel = NotificationChannel(id, name,
        NotificationManager.IMPORTANCE_DEFAULT
    )
    channel.description = description
    notificationManager.createNotificationChannel(channel)
}
