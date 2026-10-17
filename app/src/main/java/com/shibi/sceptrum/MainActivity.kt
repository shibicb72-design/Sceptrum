package com.shibi.sceptrum

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.shibi.sceptrum.atv.KeyDirection
import com.shibi.sceptrum.domain.ConnectionStatus
import com.shibi.sceptrum.domain.RemoteKey
import com.shibi.sceptrum.ui.SceptrumApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SceptrumApp()
        }
    }

    /**
     * Plan §3.3: while the remote screen is in front and connected, the phone's
     * volume keys drive the TV (key repeat included) instead of the phone.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val key = volumeKey(keyCode)
        if (key == null || !forwardsVolume()) return super.onKeyDown(keyCode, event)
        appGraph.sessionManager.send(key.keyCode, KeyDirection.Short)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (volumeKey(keyCode) != null && forwardsVolume()) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun forwardsVolume(): Boolean {
        val sessions = appGraph.sessionManager
        return sessions.inForeground && sessions.state.value.status == ConnectionStatus.Connected
    }

    private fun volumeKey(keyCode: Int): RemoteKey? = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> RemoteKey.VolumeUp
        KeyEvent.KEYCODE_VOLUME_DOWN -> RemoteKey.VolumeDown
        KeyEvent.KEYCODE_VOLUME_MUTE -> RemoteKey.Mute
        else -> null
    }
}
