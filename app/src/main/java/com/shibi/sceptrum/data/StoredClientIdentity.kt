package com.shibi.sceptrum.data

import com.shibi.sceptrum.atv.ClientIdentity
import com.shibi.sceptrum.atv.ClientIdentityProvider
import com.shibi.sceptrum.atv.SoftwareClientIdentity
import kotlinx.coroutines.runBlocking

/**
 * The phone's TV-remote identity: a software RSA-2048 key and self-signed
 * certificate ([SoftwareClientIdentity]), created on first use and kept in the
 * Tink-encrypted device store.
 *
 * Decision (PR 4b): this replaced an Android Keystore key. The TLS stack signs
 * the client CertificateVerify, and against an Android TV that means RSA-PSS
 * (TLS 1.3 requires it; TLS 1.2 servers that offer it get it too). Conscrypt
 * signs PSS with a Keystore key as a raw RSA operation, which a key generated
 * for PKCS#1/PSS signing refuses, so the handshake failed before any pairing
 * message was sent. androidtvremote2 and other working clients use an
 * in-memory key, so that is what Sceptrum does. The key is only as safe as the
 * store, which is encrypted under a Keystore-wrapped key and excluded from backup.
 *
 * [identity] blocks on the store; callers are already on an IO thread.
 */
class StoredClientIdentityProvider(
    private val repository: DeviceRepository,
    private val create: () -> ClientIdentity = { SoftwareClientIdentity.generate() },
) : ClientIdentityProvider {
    @Volatile
    private var cached: ClientIdentity? = null

    @Synchronized
    override fun identity(): ClientIdentity =
        cached ?: runBlocking { repository.clientIdentity(create) }.also { cached = it }
}
