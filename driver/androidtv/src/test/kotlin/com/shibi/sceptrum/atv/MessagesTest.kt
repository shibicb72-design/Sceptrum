package com.shibi.sceptrum.atv

import com.shibi.sceptrum.atv.proto.polo.Options
import com.shibi.sceptrum.atv.proto.polo.OuterMessage
import com.shibi.sceptrum.atv.proto.polo.PairingRequest
import com.shibi.sceptrum.atv.proto.remote.RemoteAppLinkLaunchRequest
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
    fun imeReplaceTextBytes() {
        val msg = RemoteMessage(remote_ime_batch_edit = AtvEdits.replaceText(ImeField(4, 9), "hi"))
        // field 21 (LEN) -> AA 01, len 18; ime_counter=4 -> 08 04, field_counter=9 -> 10 09,
        // edit_info (field 3, len 12): insert=1 -> 08 01, text_field_status (field 2, len 8):
        // start=1 -> 08 01, end=1 -> 10 01, value="hi" -> 1A 02 68 69
        val expected = bytes(
            0xAA, 0x01, 0x12,
            0x08, 0x04, 0x10, 0x09, 0x1A, 0x0C,
            0x08, 0x01, 0x12, 0x08,
            0x08, 0x01, 0x10, 0x01, 0x1A, 0x02, 0x68, 0x69,
        )
        assertArrayEquals(expected, msg.encode())
    }

    @Test
    fun imeReplaceTextPutsTheCursorAfterTheLastCharacterAndDefaultsTheCounters() {
        val edit = AtvEdits.replaceText(null, "netflix")
        assertEquals(0, edit.ime_counter)
        assertEquals(0, edit.field_counter)
        val status = edit.edit_info.single().text_field_status!!
        assertEquals(6, status.start)
        assertEquals(6, status.end)
        assertEquals("netflix", status.value_)
    }

    @Test(expected = IllegalArgumentException::class)
    fun imeReplaceTextRefusesEmptyText() {
        AtvEdits.replaceText(ImeField(1, 1), "")
    }

    @Test
    fun appLinkBytesAndPackagePrefix() {
        val msg = RemoteMessage(remote_app_link_launch_request = RemoteAppLinkLaunchRequest(AtvEdits.appLink("plex://")))
        // field 90 (LEN) -> D2 05, len 9; app_link (field 1) -> 0A 07 "plex://"
        assertArrayEquals(bytes(0xD2, 0x05, 0x09, 0x0A, 0x07) + "plex://".encodeToByteArray(), msg.encode())
        assertEquals("market://launch?id=com.plexapp.android", AtvEdits.appLink("com.plexapp.android"))
        assertEquals("https://www.youtube.com", AtvEdits.appLink("https://www.youtube.com"))
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
