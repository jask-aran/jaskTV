package app.opentv

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.onNodeWithText
import app.opentv.ui.BrowsingViewport
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BrowsingViewportTest {
    private val compose = createComposeRule()

    // JVM test dependencies do not merge their manifests into the app manifest. Register
    // Compose's host only in Robolectric, before the Compose rule launches its activity.
    private val hostActivity = object : ExternalResource() {
        override fun before() {
            val context = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(context.packageManager).addActivityIfNotPresent(
                ComponentName(context, ComponentActivity::class.java),
            ).apply {
                exported = true
                theme = android.R.style.Theme_Material_Light_NoActionBar
            }
        }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(hostActivity).around(compose)

    @Test fun `safe bounds follow left and right navigation bars cutouts and IME without double padding`() {
        val insets = mutableStateOf(WindowInsets(0.dp))
        compose.setContent {
            Box(Modifier.fillMaxSize().testTag("window")) {
                BrowsingViewport(insets.value) {
                    // A descendant inset-aware component must not reserve the same edges again.
                    Box(Modifier.fillMaxSize().windowInsetsPadding(insets.value).testTag("content"))
                }
            }
        }
        // Physical left/top/right/bottom: portrait gesture, both landscape rotations with
        // three-button navigation opposite a cutout, bottom three-button, keyboard, and TV.
        val cases = listOf(
            listOf(0, 24, 0, 24),
            listOf(48, 24, 32, 0),
            listOf(32, 24, 48, 0),
            listOf(0, 24, 0, 48),
            listOf(48, 24, 32, 120),
            listOf(0, 0, 0, 0),
        )
        for ((left, top, right, bottom) in cases) {
            compose.runOnIdle {
                insets.value = WindowInsets(left.dp, top.dp, right.dp, bottom.dp)
            }
            val window = compose.onNodeWithTag("window").getUnclippedBoundsInRoot()
            val content = compose.onNodeWithTag("content").getUnclippedBoundsInRoot()
            assertThat(content.left).isEqualTo(window.left + left.dp)
            assertThat(content.top).isEqualTo(window.top + top.dp)
            assertThat(content.right).isEqualTo(window.right - right.dp)
            assertThat(content.bottom).isEqualTo(window.bottom - bottom.dp)
        }
    }

    @Test fun `portrait Material navigation bar does not add consumed bottom inset again`() {
        val insets = WindowInsets(bottom = 48.dp)
        compose.setContent {
            MaterialTheme {
                BrowsingViewport(insets) {
                    NavigationBar(modifier = Modifier.testTag("tabs"), windowInsets = insets) {}
                }
            }
        }
        compose.onNodeWithTag("tabs").assertHeightIsEqualTo(80.dp)
    }
}
