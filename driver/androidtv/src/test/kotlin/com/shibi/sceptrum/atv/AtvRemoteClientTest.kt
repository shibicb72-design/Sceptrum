package com.shibi.sceptrum.atv

import com.shibi.sceptrum.atv.proto.remote.RemoteDirection
import com.shibi.sceptrum.testing.atv.AtvFixtures
import com.shibi.sceptrum.testing.atv.FakeAndroidTvServer
import com.shibi.sceptrum.testing.atv.ReceivedKey
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtvRemoteClientTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val servers = mutableListOf<FakeAndroidTvServer>()
    private val clients = mutableListOf<AtvRemoteClient>()

    private val fastConfig = RemoteConfig(
        packageName = "com.shibi.sceptrum",
        appVersion = "0.0.1",
        connectTimeoutMs = 2_000,
        readyTimeoutMs = 1_000,
        idleTimeoutMs = 400,
        backoffInitialMs = 50,
        backoffMaxMs = 400,
    )

    @After
    fun tearDown() {
        clients.forEach { it.stop() }
        servers.forEach { it.close() }
        scope.cancel()
    }

    private fun server(identity: ClientIdentity = AtvFixtures.tv, pingMs: Long = 50) =
        FakeAndroidTvServer(identity).apply {
            trust(AtvFixtures.client.certificate)
            pingIntervalMs = pingMs
            servers += this
        }

    private fun client(
        tv: FakeAndroidTvServer,
        identity: ClientIdentity = AtvFixtures.client,
        pin: ByteArray? = AtvTls.sha256(AtvFixtures.tv.certificate),
        config: RemoteConfig = fastConfig,
    ) = AtvRemoteClient(tv.host, { identity }, pin, scope, config, tv.remotePort).also {
        clients += it
        it.start()
    }

    private fun AtvRemoteClient.await(timeoutMs: Long = 5_000, predicate: (AtvState) -> Boolean): AtvState =
        runBlocking { withTimeout(timeoutMs) { state.first(predicate) } }

    private fun eventually(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met in $timeoutMs ms" }
            Thread.sleep(10)
        }
    }

    @Test
    fun configureSetActiveThenStartAndVolume() {
        val tv = server()
        val remote = client(tv)
        val state = remote.await { it.link == LinkState.Connected && it.volume != null }

        assertEquals(true, state.isOn)
        assertEquals(VolumeInfo(level = 23, max = 100, muted = false), state.volume)
        assertEquals(TvDeviceInfo("Fake TV", "Sceptrum Labs", "5.2.473254133"), state.device)
        // 615 requested, TV offers 623 (with voice): both sides agree on 615.
        assertEquals(615, state.features)
        val reply = tv.configureReplies.single()
        assertEquals(615, reply.code1)
        assertEquals("com.shibi.sceptrum", reply.device_info?.package_name)
        assertEquals("0.0.1", reply.device_info?.app_version)
        assertEquals(1, reply.device_info?.unknown1)
        assertEquals("1", reply.device_info?.unknown2)
        assertEquals(615, tv.setActiveReplies.single().active)
    }

    @Test
    fun tvMaskLimitsTheFeatures() {
        val tv = FakeAndroidTvServer(features = AtvFeatures.PING or AtvFeatures.KEY).apply {
            trust(AtvFixtures.client.certificate)
            servers += this
        }
        val remote = client(tv)
        assertEquals(3, remote.await { it.link == LinkState.Connected }.features)
        assertEquals(3, tv.setActiveReplies.single().active)
    }

    @Test
    fun keysTapAndLongPress() {
        val tv = server()
        val remote = client(tv)
        remote.await { it.link == LinkState.Connected }

        assertTrue(remote.sendKey(19))
        assertTrue(remote.sendKey(23, KeyDirection.StartLong))
        assertTrue(remote.sendKey(23, KeyDirection.EndLong))
        assertFalse("unknown key code", remote.sendKey(100_000))
        eventually { tv.keys.size == 3 }
        assertEquals(
            listOf(
                ReceivedKey(19, RemoteDirection.SHORT),
                ReceivedKey(23, RemoteDirection.START_LONG),
                ReceivedKey(23, RemoteDirection.END_LONG),
            ),
            tv.keys.toList(),
        )
    }

    @Test
    fun pingIsAnsweredWithTheSameValue() {
        val tv = server(pingMs = 30)
        val remote = client(tv, config = fastConfig.copy(idleTimeoutMs = 2_000))
        remote.await { it.link == LinkState.Connected }
        eventually { tv.pongs.size >= 3 }
        val pongs = tv.pongs.take(3)
        assertEquals(pongs.sorted(), pongs)
        assertTrue(pongs.first() >= 1)
    }

    @Test
    fun pingsKeepAnIdleSessionAlive() {
        val tv = server(pingMs = 50)
        val remote = client(tv)
        remote.await { it.link == LinkState.Connected }
        Thread.sleep(1_200) // three idle timeouts' worth
        assertEquals(LinkState.Connected, remote.state.value.link)
        assertEquals(1, tv.remoteConnections.get())
    }

    @Test
    fun silentTvTripsTheIdleWatchdogAndTheClientReconnects() {
        val tv = server(pingMs = 0)
        val remote = client(tv)
        remote.await { it.link == LinkState.Connected }
        val retry = remote.await { it.link is LinkState.Retrying }.link as LinkState.Retrying
        assertEquals(DisconnectReason.Idle, retry.reason)
        remote.await { it.link == LinkState.Connected }
        assertTrue(tv.remoteConnections.get() >= 2)
    }

    @Test
    fun reconnectsAfterTheConnectionDrops() {
        val tv = server()
        val remote = client(tv)
        remote.await { it.link == LinkState.Connected }

        tv.dropConnections()
        remote.await { it.link !is LinkState.Connected }
        assertFalse("keys are not queued while disconnected", remote.sendKey(19))
        remote.await { it.link == LinkState.Connected }
        assertEquals(2, tv.remoteConnections.get())
        assertTrue(remote.sendKey(20))
        eventually { tv.keys.any { it.keyCode == 20 } }
    }

    @Test
    fun backsOffWhileTheTvIsAway() {
        val tv = server()
        val port = tv.remotePort
        tv.close()
        val remote = AtvRemoteClient(tv.host, { AtvFixtures.client }, null, scope, fastConfig, port).also {
            clients += it
            it.start()
        }
        // A refused localhost connect takes about 2 s on Windows, so only the first steps are checked here;
        // the full schedule is backoffDoublesToTheCapAndResets.
        val first = remote.await(timeoutMs = 15_000) { it.link is LinkState.Retrying }.link as LinkState.Retrying
        assertEquals(LinkState.Retrying(1, 50, DisconnectReason.Unreachable), first)
        val second = remote.await(timeoutMs = 15_000) { (it.link as? LinkState.Retrying)?.attempt == 2 }.link
        assertEquals(LinkState.Retrying(2, 100, DisconnectReason.Unreachable), second)
    }

    @Test
    fun stateUpdatesFromTheTv() {
        val tv = server()
        val remote = client(tv)
        remote.await { it.link == LinkState.Connected }

        tv.pushVolume(level = 7, max = 50, muted = true)
        assertEquals(VolumeInfo(7, 50, true), remote.await { it.volume?.level == 7 }.volume)
        tv.pushPower(false)
        remote.await { it.isOn == false }
        tv.pushCurrentApp("com.google.android.youtube.tv")
        remote.await { it.currentApp == "com.google.android.youtube.tv" }
    }

    @Test
    fun powerKeyTogglesTheReportedState() {
        val tv = server()
        val remote = client(tv)
        remote.await { it.link == LinkState.Connected && it.isOn == true }
        assertTrue(remote.sendKey(26))
        remote.await { it.isOn == false }
    }

    @Test
    fun appLinksAndBarePackages() {
        val tv = server()
        val remote = client(tv)
        remote.await { it.link == LinkState.Connected }
        assertTrue(remote.launchApp("https://www.youtube.com"))
        assertTrue(remote.launchApp("com.android.tv.settings"))
        eventually { tv.appLinks.size == 2 }
        assertEquals(listOf("https://www.youtube.com", "market://launch?id=com.android.tv.settings"), tv.appLinks.toList())
    }

    @Test
    fun appLinkTheTvCannotOpenReconnectsAndNeverAsksToPairAgain() {
        val tv = server()
        val remote = client(tv)
        remote.await { it.link == LinkState.Connected }
        val seen = CopyOnWriteArrayList<LinkState>()
        val watcher = scope.launch { remote.state.collect { seen += it.link } }
        tv.closeOnAppLink = true
        tv.silentDrops.set(3)
        assertTrue(remote.launchApp("https://www.netflix.com/title"))
        eventually(10_000) { tv.remoteConnections.get() >= 5 } // 1 first + 3 empty closes + 1 good
        tv.closeOnAppLink = false
        remote.await(10_000) { it.link == LinkState.Connected }
        watcher.cancel()
        assertTrue(seen.none { it == LinkState.NeedsPairing })
        val retries = seen.filterIsInstance<LinkState.Retrying>()
        assertTrue(retries.isNotEmpty())
        assertTrue(retries.all { it.afterAppLink })
    }

    @Test
    fun hangUpsWithoutAnAppLinkStillMeanNeedsPairing() {
        val tv = server()
        val remote = client(tv)
        remote.await { it.link == LinkState.Connected }
        tv.silentDrops.set(10)
        tv.dropConnections()
        remote.await(10_000) { it.link == LinkState.NeedsPairing }
    }

    @Test
    fun textEchoesTheImeCounters() {
        val tv = server()
        val remote = client(tv)
        remote.await { it.link == LinkState.Connected }
        tv.pushImeField(imeCounter = 4, fieldCounter = 9)
        remote.await { it.imeField == ImeField(4, 9) }

        assertFalse(remote.sendText(""))
        assertTrue(remote.sendText("cats"))
        eventually { tv.imeEdits.isNotEmpty() }
        val edit = tv.imeEdits.single()
        assertEquals(4, edit.ime_counter)
        assertEquals(9, edit.field_counter)
        val status = edit.edit_info.single().text_field_status!!
        assertEquals(1, edit.edit_info.single().insert)
        assertEquals("cats", status.value_)
        assertEquals(3, status.start)
        assertEquals(3, status.end)
    }

    @Test
    fun focusedFieldReportsItsTextAndLabelAndFollowsWhatWasSent() {
        val tv = server()
        val remote = client(tv)
        remote.await { it.link == LinkState.Connected }
        tv.pushImeField(imeCounter = 2, fieldCounter = 5, value = "ca")
        remote.await { it.imeField == ImeField(2, 5, "ca") }
        tv.pushImeShow(value = "ca", label = "Search YouTube")
        remote.await { it.imeField?.label == "Search YouTube" }
        assertEquals(ImeField(2, 5, "ca", "Search YouTube"), remote.state.value.imeField)

        assertTrue(remote.sendText("cat videos"))
        assertEquals("cat videos", remote.state.value.imeField?.value)
        eventually { tv.imeEdits.isNotEmpty() }
        assertEquals(2, tv.imeEdits.single().ime_counter)
        assertEquals(5, tv.imeEdits.single().field_counter)
    }

    @Test
    fun textBeforeAnyFieldUsesZeroCountersAndNothingGoesOutWhenDisconnected() {
        val tv = server()
        val remote = client(tv)
        remote.await { it.link == LinkState.Connected }
        assertTrue(remote.sendText("a"))
        eventually { tv.imeEdits.isNotEmpty() }
        assertEquals(0, tv.imeEdits.single().ime_counter)
        assertEquals(0, tv.imeEdits.single().field_counter)

        remote.stop()
        assertFalse(remote.sendText("ab"))
        assertFalse(remote.launchApp("https://www.netflix.com/title"))
    }

    @Test
    fun unpairedPhoneIsToldToPairAgain() {
        val tv = server()
        val remote = client(tv, identity = AtvFixtures.stranger)
        remote.await { it.link == LinkState.NeedsPairing }
        Thread.sleep(300)
        assertEquals("no retries after a refused certificate", LinkState.NeedsPairing, remote.state.value.link)
    }

    @Test
    fun changedTvCertificateIsRefusedBeforeAnyKey() {
        val tv = server(identity = AtvFixtures.tvReset)
        val remote = client(tv, pin = AtvTls.sha256(AtvFixtures.tv.certificate))
        remote.await { it.link == LinkState.IdentityChanged }
        assertFalse(remote.sendKey(19))
        assertEquals(0, tv.configureReplies.size)
    }

    @Test
    fun tvThatNeverStartsTimesOut() {
        val tv = server().apply { sendStart = false }
        val remote = client(tv, config = fastConfig.copy(readyTimeoutMs = 300, idleTimeoutMs = 5_000))
        val retry = remote.await { it.link is LinkState.Retrying }.link as LinkState.Retrying
        assertEquals(DisconnectReason.Timeout, retry.reason)
    }

    @Test
    fun retryNowSkipsTheWaitAndStopGoesIdle() {
        val tv = server()
        val remote = client(tv, config = fastConfig.copy(backoffInitialMs = 60_000, backoffMaxMs = 60_000))
        remote.await { it.link == LinkState.Connected }
        tv.dropConnections()
        remote.await { it.link is LinkState.Retrying }
        remote.retryNow()
        remote.await(timeoutMs = 3_000) { it.link == LinkState.Connected }

        remote.stop()
        assertEquals(LinkState.Idle, remote.state.value.link)
        assertFalse(remote.sendKey(19))
    }

    @Test
    fun backoffDoublesToTheCapAndResets() {
        val backoff = Backoff(100, 30_000)
        val delays = List(10) { backoff.next() }
        assertEquals(listOf(100L, 200L, 400L, 800L, 1_600L, 3_200L, 6_400L, 12_800L, 25_600L, 30_000L), delays)
        backoff.reset()
        assertEquals(100L, backoff.next())
    }
}
