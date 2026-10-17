package com.shibi.sceptrum

import android.content.ComponentName
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.shibi.sceptrum.devices.DeviceEdit
import com.shibi.sceptrum.devices.DevicesUiState
import com.shibi.sceptrum.domain.AppBackground
import com.shibi.sceptrum.domain.DiscoveredRow
import com.shibi.sceptrum.domain.SavedRow
import com.shibi.sceptrum.domain.PairingTarget
import com.shibi.sceptrum.domain.PairingUiState
import com.shibi.sceptrum.remote.KeyboardUiState
import com.shibi.sceptrum.session.SessionState
import com.shibi.sceptrum.ui.SceptrumFrame
import com.shibi.sceptrum.ui.SceptrumScaffold
import com.shibi.sceptrum.ui.devices.DevicesScreen
import com.shibi.sceptrum.ui.nav.NavSection
import com.shibi.sceptrum.ui.remote.RemoteExtras
import com.shibi.sceptrum.ui.remote.RemotePanel
import com.shibi.sceptrum.ui.remote.RemoteScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val Folded400 = "w400dp-h891dp-xxhdpi"
private const val Split610 = "w610dp-h891dp-xhdpi"
private const val ImeHeight = 320
private const val Dir = "build/outputs/roborazzi"

/**
 * The soft keyboard covers the bottom [ImeHeight] dp of the window (insets
 * injected, edge-to-edge, as on the phone). The focused field must still end
 * above it, folded (400 dp) and in a split window (610 dp).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = Folded400)
class ImeInsetsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        compose.runOnUiThread { WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false) }
    }

    @Test
    fun pairingCodeStaysAboveTheKeyboardFolded() = pairing("ime_pairing_code_folded_400")

    @Config(qualifiers = Split610)
    @Test
    fun pairingCodeStaysAboveTheKeyboardSplit() = pairing("ime_pairing_code_split_610")

    @Test
    fun keyboardBarStaysAboveTheKeyboardFolded() {
        setScreen(NavSection.Remote) { widthClass ->
            RemoteScreen(widthClass, session = Connected, extras = KeyboardOpen)
        }
        val field = compose.onNodeWithText("lo-fi beats")
        field.performClick()
        showKeyboard()
        assertAboveKeyboard(field)
        assertAboveKeyboard(compose.onNodeWithContentDescription("Close keyboard"))
        // Sits right on the keyboard: an extra imePadding would lift it by the carousel and nav bar too.
        val gap = window().getBoundsInRoot().bottom - ImeHeight.dp - field.getBoundsInRoot().bottom
        assertTrue("keyboard bar floats $gap above the keyboard (double IME padding?)", gap < 48.dp)
        window().captureRoboImage("$Dir/ime_keyboard_bar_folded_400.png")
    }

    @Config(qualifiers = Split610)
    @Test
    fun keyboardCardIsScrolledAboveTheKeyboardSplit() {
        setScreen(NavSection.Remote) { widthClass ->
            RemoteScreen(widthClass, session = Connected, extras = KeyboardOpen.copyPanel(RemotePanel.None))
        }
        val field = compose.onNodeWithText("lo-fi beats")
        field.performClick()
        showKeyboard()
        assertAboveKeyboard(field)
        window().captureRoboImage("$Dir/ime_keyboard_card_split_610.png")
    }

    @Test
    fun addByIpFieldIsScrolledAboveTheKeyboardFolded() = manualIp("ime_add_by_ip_folded_400")

    @Config(qualifiers = Split610)
    @Test
    fun addByIpFieldIsScrolledAboveTheKeyboardSplit() = manualIp("ime_add_by_ip_split_610")

    @Test
    fun renameFieldStaysAboveTheKeyboardFolded() {
        setScreen(NavSection.Devices) { widthClass ->
            DevicesScreen(widthClass, state = SampleDevices.copy(editing = Editing), pairing = PairingUiState.Idle)
        }
        val field = compose.onNodeWithText("Living room projector")
        field.performClick()
        showKeyboard()
        assertAboveKeyboard(field)
    }

    @Test
    fun mainActivityResizesForTheKeyboard() {
        val context = RuntimeEnvironment.getApplication()
        val info = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        // 0x10 is the manifest value of windowSoftInputMode="adjustResize" (the Java constant is deprecated).
        assertEquals(0x10, info.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
    }

    private fun manualIp(name: String) {
        setScreen(NavSection.Devices) { widthClass ->
            DevicesScreen(widthClass, state = SampleDevices, pairing = PairingUiState.Idle)
        }
        val field = compose.onNodeWithText("IP address")
        field.performScrollTo().performClick()
        showKeyboard()
        assertAboveKeyboard(field)
        window().captureRoboImage("$Dir/$name.png")
    }

    private fun pairing(name: String) {
        setScreen(NavSection.Devices) { widthClass ->
            DevicesScreen(widthClass, state = DevicesUiState(), pairing = CodeEntry)
        }
        val code = compose.onNodeWithContentDescription("6-character code")
        code.performClick()
        showKeyboard()
        assertAboveKeyboard(code)
        window().captureRoboImage("$Dir/$name.png")
    }

    private fun setScreen(section: NavSection, screen: @Composable (com.shibi.sceptrum.domain.WindowWidthClass) -> Unit) {
        compose.setContent {
            SceptrumFrame(darkTheme = false, background = AppBackground.Solid("sky")) { widthClass ->
                SceptrumScaffold(section = section, onSelect = {}) { screen(widthClass) }
            }
        }
        compose.waitForIdle()
    }

    /** Edge-to-edge insets as Android sends them with the keyboard up. */
    private fun showKeyboard() {
        compose.runOnUiThread {
            val activity = compose.activity
            val density = activity.resources.displayMetrics.density
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, (24 * density).toInt(), 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, (24 * density).toInt()))
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, (ImeHeight * density).toInt()))
                .setVisible(WindowInsetsCompat.Type.ime(), true)
                .build()
            ViewCompat.dispatchApplyWindowInsets(activity.window.decorView, insets)
        }
        compose.waitForIdle()
    }

    /** The activity window; a focused field with text also opens a cursor-handle popup root. */
    private fun window(): SemanticsNodeInteraction = compose.onAllNodes(isRoot()).onFirst()

    private fun assertAboveKeyboard(node: SemanticsNodeInteraction) {
        val windowBottom = window().getBoundsInRoot().bottom
        val keyboardTop = windowBottom - ImeHeight.dp
        val bottom = node.getBoundsInRoot().bottom
        assertTrue("field bottom $bottom must be above the keyboard top $keyboardTop", bottom <= keyboardTop)
        assertTrue("field must be on screen, bottom $bottom", bottom > 0.dp)
    }

    private companion object {
        val Target = PairingTarget(name = "XGIMI Horizon S Max", host = "192.168.1.50")
        val CodeEntry = PairingUiState.EnterCode(Target, code = "4FA")
        val Connected = SessionState(deviceName = "XGIMI Horizon S Max", status = com.shibi.sceptrum.domain.ConnectionStatus.Connected)
        val KeyboardOpen = RemoteExtras(
            keyboard = KeyboardUiState(text = "lo-fi beats", fieldActive = true, fieldLabel = "Search", connected = true),
            panel = RemotePanel.Keyboard,
        )

        val Editing = DeviceEdit("mac:01", "XGIMI Horizon S Max", "192.168.1.50", "AA:BB:CC:DD:EE:01", draft = "Living room projector")
        val SampleDevices = DevicesUiState(
            saved = listOf(
                SavedRow("mac:01", "XGIMI Horizon S Max", "192.168.1.50", active = true, online = true),
                SavedRow("mac:02", "TCL 75QM6K", "192.168.1.60", active = false, online = false),
            ),
            discovered = listOf(DiscoveredRow("Bedroom TV", "192.168.1.71", 6466)),
        )

        fun RemoteExtras.copyPanel(panel: RemotePanel) = RemoteExtras(keyboard = keyboard, panel = panel)
    }
}
