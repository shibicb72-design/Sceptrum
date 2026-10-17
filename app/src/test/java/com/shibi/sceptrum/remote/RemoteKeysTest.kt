package com.shibi.sceptrum.remote

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shibi.sceptrum.atv.DisconnectReason
import com.shibi.sceptrum.atv.LinkState
import com.shibi.sceptrum.atv.proto.remote.RemoteKeyCode
import com.shibi.sceptrum.domain.ConnectionStatus
import com.shibi.sceptrum.domain.RemoteKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RemoteKeysTest {
    /** Every control sends Android's own KeyEvent code, which the proto enum mirrors. */
    @Test
    fun keyCodesMatchAndroidKeyEvent() {
        val expected = mapOf(
            RemoteKey.Up to KeyEvent.KEYCODE_DPAD_UP,
            RemoteKey.Down to KeyEvent.KEYCODE_DPAD_DOWN,
            RemoteKey.Left to KeyEvent.KEYCODE_DPAD_LEFT,
            RemoteKey.Right to KeyEvent.KEYCODE_DPAD_RIGHT,
            RemoteKey.Ok to KeyEvent.KEYCODE_DPAD_CENTER,
            RemoteKey.Back to KeyEvent.KEYCODE_BACK,
            RemoteKey.Home to KeyEvent.KEYCODE_HOME,
            RemoteKey.Menu to KeyEvent.KEYCODE_MENU,
            RemoteKey.Power to KeyEvent.KEYCODE_POWER,
            RemoteKey.VolumeUp to KeyEvent.KEYCODE_VOLUME_UP,
            RemoteKey.VolumeDown to KeyEvent.KEYCODE_VOLUME_DOWN,
            RemoteKey.Mute to KeyEvent.KEYCODE_VOLUME_MUTE,
            RemoteKey.ChannelUp to KeyEvent.KEYCODE_CHANNEL_UP,
            RemoteKey.ChannelDown to KeyEvent.KEYCODE_CHANNEL_DOWN,
            RemoteKey.PlayPause to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            RemoteKey.Rewind to KeyEvent.KEYCODE_MEDIA_REWIND,
            RemoteKey.FastForward to KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            RemoteKey.Previous to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            RemoteKey.Next to KeyEvent.KEYCODE_MEDIA_NEXT,
            RemoteKey.Input to KeyEvent.KEYCODE_TV_INPUT,
            RemoteKey.Settings to KeyEvent.KEYCODE_SETTINGS,
        ) + (0..9).associate { RemoteKey.digit(it) to KeyEvent.KEYCODE_0 + it }
        assertEquals(RemoteKey.entries.toSet(), expected.keys)
        expected.forEach { (key, code) ->
            assertEquals(key.name, code, key.keyCode)
            assertNotNull("${key.name} is in RemoteKeyCode", RemoteKeyCode.fromValue(key.keyCode))
        }
    }

    @Test
    fun onlyDpadAndVolumeRepeat() {
        assertEquals(
            setOf(RemoteKey.Up, RemoteKey.Down, RemoteKey.Left, RemoteKey.Right, RemoteKey.VolumeUp, RemoteKey.VolumeDown),
            RemoteKey.entries.filter { it.repeats }.toSet(),
        )
    }

    @Test
    fun statusChipStates() {
        assertEquals(ConnectionStatus.Connecting, ConnectionStatus.from(LinkState.Connecting(0)))
        assertEquals(ConnectionStatus.Reconnecting, ConnectionStatus.from(LinkState.Connecting(2)))
        assertEquals(ConnectionStatus.Connected, ConnectionStatus.from(LinkState.Connected))
        assertEquals(
            ConnectionStatus.Reconnecting,
            ConnectionStatus.from(LinkState.Retrying(1, 100, DisconnectReason.ConnectionLost)),
        )
        assertEquals(
            ConnectionStatus.Offline,
            ConnectionStatus.from(LinkState.Retrying(4, 1_600, DisconnectReason.ConnectionLost)),
        )
        assertEquals(
            ConnectionStatus.Offline,
            ConnectionStatus.from(LinkState.Retrying(2, 200, DisconnectReason.Unreachable)),
        )
        assertEquals(ConnectionStatus.Offline, ConnectionStatus.from(LinkState.Idle))
        assertEquals(ConnectionStatus.NeedsPairing, ConnectionStatus.from(LinkState.NeedsPairing))
        assertEquals(ConnectionStatus.IdentityChanged, ConnectionStatus.from(LinkState.IdentityChanged))
    }
}
