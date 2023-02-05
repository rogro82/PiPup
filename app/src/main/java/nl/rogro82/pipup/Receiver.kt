package nl.rogro82.pipup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat.startForegroundService

class Receiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        with(context) {
            val serviceIntent = Intent(this, PiPupService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }
}
