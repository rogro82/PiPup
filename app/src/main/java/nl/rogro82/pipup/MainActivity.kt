/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package nl.rogro82.pipup

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION
import android.provider.Settings.canDrawOverlays
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import android.view.Gravity
import android.graphics.PixelFormat
import android.webkit.WebView
import android.widget.Button
import android.widget.VideoView
import android.support.v4.media.session.MediaControllerCompat.setMediaController
import android.view.View
import android.widget.MediaController
import android.widget.TextView
import nl.rogro82.pipup.Utils.getIpAddress

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // start service in foreground

        val textViewConnection = findViewById<TextView>(R.id.textViewServerAddress)
        val textViewServerAddress = findViewById<TextView>(R.id.textViewServerAddress)

        when(val ipAddress = getIpAddress()) {
            is String -> {
                textViewConnection.setText(R.string.server_running)
                textViewServerAddress.apply {
                    visibility = View.VISIBLE
                    text = "$ipAddress:7979"
                }
            }
            else -> {
                textViewConnection.setText(R.string.no_network_connection)
                textViewServerAddress.visibility = View.INVISIBLE
            }
        }


        startService(Intent(this, PiPupService::class.java))
    }
}
