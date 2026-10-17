package com.shibi.sceptrum.pairing

import com.shibi.sceptrum.atv.AtvPairingSession
import com.shibi.sceptrum.atv.ClientIdentityProvider
import com.shibi.sceptrum.atv.PairingTimeouts
import com.shibi.sceptrum.domain.PairingTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Starts a pairing attempt with a TV. Faked in view-model tests. */
fun interface PairingGateway {
    /** @throws com.shibi.sceptrum.atv.PairingException */
    suspend fun start(target: PairingTarget): PairingAttempt
}

/** A TV showing its code, waiting for it. */
interface PairingAttempt {
    /** Name from the TV certificate, when it has one. */
    val tvName: String?
    val mac: String?
    val serverSha256: ByteArray

    /** @throws com.shibi.sceptrum.atv.PairingException */
    suspend fun submit(code: String)

    fun close()
}

class AtvPairingGateway(
    private val identity: ClientIdentityProvider,
    private val clientName: String,
    private val timeouts: PairingTimeouts = PairingTimeouts(),
) : PairingGateway {
    override suspend fun start(target: PairingTarget): PairingAttempt {
        // First use generates the Keystore key, which takes a moment.
        val client = withContext(Dispatchers.IO) { identity.identity() }
        val session = AtvPairingSession.start(target.host, client, clientName, target.pairingPort, timeouts)
        val tv = session.serverIdentity
        return object : PairingAttempt {
            override val tvName = tv.name.takeIf { it.isNotBlank() }
            override val mac = tv.mac
            override val serverSha256 = session.serverSha256

            override suspend fun submit(code: String) = session.submitCode(code)

            override fun close() = session.close()
        }
    }
}
