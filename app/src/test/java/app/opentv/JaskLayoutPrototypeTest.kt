package app.opentv.ui

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.core.app.ApplicationProvider
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class JaskLayoutPrototypeTest {
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
    private fun render(width: Int, name: String) {
        var renderedView: android.view.View? = null
        compose.setContent { renderedView = androidx.compose.ui.platform.LocalView.current; JaskLayoutPrototype(width) }
        compose.onNodeWithContentDescription("jaskTV").assertIsDisplayed()
        for (label in listOf("Live TV", "Movies", "Shows", "Library")) compose.onNodeWithContentDescription(label).assertIsDisplayed()
        compose.onAllNodesWithTag("jask.header").assertCountEquals(1)
        compose.onAllNodesWithTag("jask.navigation").assertCountEquals(1)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)).assertCountEquals(0)
        val view = checkNotNull(renderedView)
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        compose.runOnIdle { view.draw(android.graphics.Canvas(bitmap)) }
        val file = File("build/reports/layout-prototypes/$name.png").apply { parentFile?.mkdirs() }
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test @Config(qualifiers = "w390dp-h844dp-mdpi") fun phonePortrait() = render(390, "phone-portrait")
    @Test @Config(qualifiers = "w800dp-h1280dp-mdpi") fun tabletPortrait() = render(800, "tablet-portrait")
    @Test @Config(qualifiers = "w1280dp-h800dp-land-mdpi") fun tabletLandscape() = render(1280, "tablet-landscape")
}
