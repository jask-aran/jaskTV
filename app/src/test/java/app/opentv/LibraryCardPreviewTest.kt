package app.opentv

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.opentv.ui.library.ContinueCard
import app.opentv.ui.library.LibraryViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Fixture render of Continue watching cards (no artwork loads under Robolectric). Output: build/reports/library-cards.png */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w390dp-h400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryCardPreviewTest {
    private val compose = createComposeRule()
    private val host = object : ExternalResource() {
        override fun before() {
            val context = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, ComponentActivity::class.java)).apply {
                exported = true; theme = android.R.style.Theme_Material_Light_NoActionBar
            }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(host).around(compose)

    private fun item(title: String, code: String?, name: String?, chips: List<String>, progress: Float, left: Int?, upNext: Boolean, new: Boolean = false) =
        LibraryViewModel.Activity("k:$title", title, code, name, chips, code?.let { "Episode 2 of 10" }, null, null, progress, left, upNext, new,
            0, if (code == null) 1 else null, null, 0)

    @Test fun continueCards() {
        var view: android.view.View? = null
        compose.setContent {
            view = androidx.compose.ui.platform.LocalView.current
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                Column(Modifier.fillMaxSize().background(Color(0xFF141312)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ContinueCard(item("Silo", "S1 · E2", "Holston's Pick", listOf("Apple TV+", "Running"), 0f, null, true, new = true), {}, {}, {})
                    ContinueCard(item("Berlin ER", "S1 · E4", "A very long episode title that has to wrap somewhere", listOf("ARD", "Running"), 0.4f, 31, false), {}, {}, {})
                    ContinueCard(item("500 Miles", null, null, listOf("★ 7.0", "2026 · EN"), 0.7f, 74, false), {}, {}, {})
                }
            }
        }
        compose.onNodeWithText("NEW EPISODE").assertIsDisplayed()
        compose.onNodeWithText("1h 14m left").assertIsDisplayed()
        val v = checkNotNull(view)
        val bitmap = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
        compose.runOnIdle { v.draw(android.graphics.Canvas(bitmap)) }
        File("build/reports/library-cards.png").apply { parentFile?.mkdirs() }.outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
