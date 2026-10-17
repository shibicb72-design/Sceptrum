package com.shibi.sceptrum.atv

import com.shibi.sceptrum.testing.atv.AtvFixtures
import javax.security.auth.x500.X500Principal
import org.junit.Assert.assertEquals
import org.junit.Test

class CertificateNamesTest {
    @Test
    fun shieldStyleSubject() {
        assertEquals(TvIdentity("SHIELD Android TV", "AA:BB:CC:DD:EE:01"), CertificateNames.parse(AtvFixtures.tv.certificate))
    }

    @Test
    fun nexusStyleSubjectWithDnQualifier() {
        assertEquals(TvIdentity("Nexus Player", "AA:BB:CC:DD:EE:02"), CertificateNames.parse(AtvFixtures.tvReset.certificate))
    }

    @Test
    fun plainCommonNameHasNoMac() {
        assertEquals(TvIdentity("Sceptrum", null), CertificateNames.parse(AtvFixtures.client.certificate))
    }

    @Test
    fun escapedCommaInAModelName() {
        val subject = X500Principal("CN=atvremote/x/y/TV\\, 75 inch/aa-bb-cc-dd-ee-ff")
        assertEquals(TvIdentity("TV, 75 inch", "AA:BB:CC:DD:EE:FF"), CertificateNames.parse(subject))
    }
}
