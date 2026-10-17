package com.shibi.sceptrum.atv

import com.shibi.sceptrum.atv.proto.polo.Options
import com.shibi.sceptrum.atv.proto.polo.OuterMessage
import com.shibi.sceptrum.atv.proto.polo.PairingRequest
import com.shibi.sceptrum.atv.proto.remote.RemoteDirection
import com.shibi.sceptrum.atv.proto.remote.RemoteKeyCode
import com.shibi.sceptrum.atv.proto.remote.RemoteKeyInject
import com.shibi.sceptrum.atv.proto.remote.RemoteMessage
import com.shibi.sceptrum.atv.proto.remote.RemotePingResponse
import com.shibi.sceptrum.atv.proto.remote.RemoteSetVolumeLevel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Wire output checked byte for byte against hand-encoded protobuf. */
class MessagesTest {
    @Test
    fun keyInjectBytes() {
        val msg = RemoteMessage(remote_key_inject = RemoteKeyInject(RemoteKeyCode.KEYCODE_DPAD_UP, RemoteDirection.SHORT))
        // field 10 (LEN) -> 0x52, len 4, key_code=19 -> 08 13, direction=3 -> 10 03
        assertArrayEquals(bytes(0x52, 0x04, 0x08, 0x13, 0x10, 0x03), msg.encode())
    }

    @Test
    fun pingResponseBytes() {
        val msg = RemoteMessage(remote_ping_response = RemotePingResponse(val1 = 5))
        assertArrayEquals(bytes(0x4A, 0x02, 0x08, 0x05), msg.encode())
    }

    @Test
    fun keyCodesFollowAndroidKeyEvent() {
        assertEquals(3, RemoteKeyCode.KEYCODE_HOME.value)
        assertEquals(4, RemoteKeyCode.KEYCODE_BACK.value)
        assertEquals(23, RemoteKeyCode.KEYCODE_DPAD_CENTER.value)
        assertEquals(26, RemoteKeyCode.KEYCODE_POWER.value)
        assertEquals(85, RemoteKeyCode.KEYCODE_MEDIA_PLAY_PAUSE.value)
        assertEquals(164, RemoteKeyCode.KEYCODE_VOLUME_MUTE.value)
        assertEquals(178, RemoteKeyCode.KEYCODE_TV_INPUT.value)
        assertEquals(615, AtvFeatures.REQUESTED)
    }

    @Test
    fun volumeLevelDecodes() {
        // field 50 (LEN) tag = 50<<3|2 = 402 -> 92 03; volume_max=100 (field 6), level=23 (7), muted (8)
        val raw = bytes(0x92, 0x03, 0x06, 0x30, 0x64, 0x38, 0x17, 0x40, 0x01)
        val volume = RemoteMessage.ADAPTER.decode(raw).remote_set_volume_level
        assertEquals(RemoteSetVolumeLevel(volume_max = 100, volume_level = 23, volume_muted = true), volume)
    }

    @Test
    fun pairingRequestRoundTrip() {
        val msg = OuterMessage(
            protocol_version = 2,
            status = OuterMessage.Status.STATUS_OK,
            pairing_request = PairingRequest(service_name = "atvremote", client_name = "Sceptrum"),
        )
        val bytes = msg.encode()
        // protocol_version=2 -> 08 02, status=200 -> 10 C8 01
        assertArrayEquals(bytes(0x08, 0x02, 0x10, 0xC8, 0x01), bytes.copyOf(5))
        assertEquals(msg, OuterMessage.ADAPTER.decode(bytes))
    }

    @Test
    fun optionsCarryHexadecimalSixAsInput() {
        val hex = Options.Encoding(Options.Encoding.EncodingType.ENCODING_TYPE_HEXADECIMAL, 6)
        val options = Options(input_encodings = listOf(hex), preferred_role = Options.RoleType.ROLE_TYPE_INPUT)
        // input_encodings (field 1): 0A 04 08 03 10 06; preferred_role (field 3) = 1: 18 01
        assertArrayEquals(bytes(0x0A, 0x04, 0x08, 0x03, 0x10, 0x06, 0x18, 0x01), options.encode())
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}
