package nl.rogro82.pipup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class Receiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {

        // stop the current running service instance
        context.stopService(Intent(context, PiPupService::class.java))

        // start a new service instance
        context.startService(Intent(context, PiPupService::class.java))
    }
}
