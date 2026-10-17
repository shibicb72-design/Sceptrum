package com.shibi.sceptrum.atv

import com.shibi.sceptrum.atv.proto.polo.Configuration
import com.shibi.sceptrum.atv.proto.polo.Options
import com.shibi.sceptrum.atv.proto.polo.OuterMessage
import com.shibi.sceptrum.atv.proto.polo.PairingRequest
import com.shibi.sceptrum.atv.proto.polo.Secret
import com.squareup.wire.FieldEncoding
import com.squareup.wire.ProtoReader
import okio.Buffer
import okio.ByteString.Companion.toByteString

/** The reply the client is waiting for when pairing stops; names the TV's message. */
enum class PairingStep {
    /** TCP connect to the pairing port. */
    Connect,

    /** TLS handshake, including the client certificate. */
    TlsHandshake,

    /** Sent PairingRequest, waiting for PairingRequestAck. */
    PairingRequestAck,

    /** Sent Options, waiting for the TV's Options. */
    Options,

    /** Sent Configuration, waiting for ConfigurationAck (the TV then shows the code). */
    ConfigurationAck,

    /** Sent Secret, waiting for SecretAck. */
    SecretAck,
}

/**
 * The client's four polo messages, built exactly as androidtvremote2
 * `pairing.py` builds them: protocol version 2, STATUS_OK, hexadecimal
 * 6-symbol encoding, client role INPUT.
 */
object PoloMessages {
    const val PROTOCOL_VERSION = 2
    const val STATUS_OK = 200
    const val STATUS_BAD_SECRET = 402

    val hexEncoding = Options.Encoding(Options.Encoding.EncodingType.ENCODING_TYPE_HEXADECIMAL, PairingCode.LENGTH)

    fun pairingRequest(clientName: String): OuterMessage =
        outer().copy(pairing_request = PairingRequest(service_name = AtvPairingSession.SERVICE_NAME, client_name = clientName))

    fun options(): OuterMessage =
        outer().copy(options = Options(input_encodings = listOf(hexEncoding), preferred_role = Options.RoleType.ROLE_TYPE_INPUT))

    fun configuration(): OuterMessage =
        outer().copy(configuration = Configuration(hexEncoding, Options.RoleType.ROLE_TYPE_INPUT))

    fun secret(secret: ByteArray): OuterMessage = outer().copy(secret = Secret(secret.toByteString()))

    /** androidtvremote2 `_create_message`. */
    private fun outer() = OuterMessage(protocol_version = PROTOCOL_VERSION, status = OuterMessage.Status.STATUS_OK)
}

/**
 * A TV reply read field by field rather than through the generated adapter,
 * so a status outside the enum or a missing required field is still reported
 * with its raw value instead of failing to decode.
 */
class PoloReply(
    /** Raw `status` (field 2), or null when the TV left it out. */
    val status: Int?,
    /** Top-level field numbers present, e.g. 11 for pairing_request_ack. */
    val fields: Set<Int>,
    /** `pairing_request_ack.server_name`, when sent. */
    val serverName: String?,
) {
    fun has(step: PairingStep): Boolean = expectedField(step)?.let { it in fields } ?: false

    companion object {
        const val PAIRING_REQUEST_ACK = 11
        const val OPTIONS = 20
        const val CONFIGURATION_ACK = 31
        const val SECRET_ACK = 41

        fun expectedField(step: PairingStep): Int? = when (step) {
            PairingStep.PairingRequestAck -> PAIRING_REQUEST_ACK
            PairingStep.Options -> OPTIONS
            PairingStep.ConfigurationAck -> CONFIGURATION_ACK
            PairingStep.SecretAck -> SECRET_ACK
            PairingStep.Connect, PairingStep.TlsHandshake -> null
        }

        /** @throws java.io.IOException if [raw] is not a protobuf message. */
        fun parse(raw: ByteArray): PoloReply {
            val reader = ProtoReader(Buffer().write(raw))
            var status: Int? = null
            var serverName: String? = null
            val fields = mutableSetOf<Int>()
            reader.forEachTag { tag ->
                fields += tag
                when {
                    tag == 2 && reader.peekFieldEncoding() == FieldEncoding.VARINT -> status = reader.readVarint32()
                    tag == PAIRING_REQUEST_ACK && reader.peekFieldEncoding() == FieldEncoding.LENGTH_DELIMITED ->
                        reader.forEachTag { inner ->
                            if (inner == 1) serverName = reader.readString() else reader.skip()
                        }
                    else -> reader.skip()
                }
            }
            return PoloReply(status, fields, serverName)
        }
    }
}
