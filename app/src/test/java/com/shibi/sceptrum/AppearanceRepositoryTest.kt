package com.shibi.sceptrum

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shibi.sceptrum.appearance.AppearanceRepository
import com.shibi.sceptrum.domain.AppBackground
import com.shibi.sceptrum.domain.shownWith
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AppearanceRepositoryTest {
    private lateinit var repository: AppearanceRepository

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "appearance-test.preferences_pb")
        if (file.exists()) file.delete()
        repository = AppearanceRepository(PreferenceDataStoreFactory.create(produceFile = { file }))
    }

    @Test
    fun freshInstallIsDarkOnTheDefaultBackdrop() = runBlocking {
        assertTrue(repository.darkTheme.first())
        assertEquals(AppBackground.Default, repository.background.first())
    }

    @Test
    fun persistsSolidAndBlendPicks() = runBlocking {
        repository.setBackground(AppBackground.Solid("mint"))
        assertEquals(AppBackground.Solid("mint"), repository.background.first())
        repository.setBackground(AppBackground.Gradient("sunrise"))
        assertEquals(AppBackground.Gradient("sunrise"), repository.background.first())
    }

    /** Dark on paints the dark default but never writes it; dark off shows the last pick again. */
    @Test
    fun darkOnThenOffRestoresTheLastPick() = runBlocking {
        repository.setDarkTheme(false)
        repository.setBackground(AppBackground.Solid("sky"))

        repository.setDarkTheme(true)
        assertEquals(AppBackground.Solid("sky"), repository.background.first())
        assertEquals(AppBackground.Default, repository.background.first().shownWith(repository.darkTheme.first()))

        repository.setDarkTheme(false)
        assertEquals(AppBackground.Solid("sky"), repository.background.first().shownWith(repository.darkTheme.first()))
    }
}
