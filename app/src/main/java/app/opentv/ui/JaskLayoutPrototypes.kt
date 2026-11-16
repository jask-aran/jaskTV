package app.opentv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.opentv.ui.vod.JaskPosterCard
import app.opentv.ui.vod.JaskTheme

/** Fixture-only previews reuse the production chrome and card, without a network or a ViewModel. */
@Composable
internal fun JaskLayoutPrototype(widthDp: Int) {
    var selected by remember { mutableStateOf(JaskDestination.MOVIES) }
    val titles = listOf("Inception", "The Thing", "Spirited Away", "Arrival", "The Grand Budapest Hotel", "Dune", "Past Lives", "The Apartment", "Perfect Days", "The Red Turtle", "Moonlight", "The Handmaiden")
    CompositionLocalProvider(LocalLayoutClass provides if (widthDp < 600) LayoutClass.PHONE else LayoutClass.TABLET) {
        JaskTheme {
            JaskChromeFrame(selected, { selected = it }, "", {}, {}, emptyList()) {
                LazyVerticalGrid(columns = if (widthDp < 600) GridCells.Fixed(2) else GridCells.Adaptive(176.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 80.dp, bottom = 112.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize().background(Color(0xFF141312))) {
                    items(titles.size, key = { titles[it] }) { index ->
                        JaskPosterCard(titles[index], null, listOf("${2010 + index}", "EN")) {}
                    }
                }
            }
        }
    }
}

@Preview(name = "Phone portrait · 390 × 844", widthDp = 390, heightDp = 844, showBackground = true)
@Composable private fun PhonePortraitPrototype() = JaskLayoutPrototype(390)
@Preview(name = "Tablet portrait · 800 × 1280", widthDp = 800, heightDp = 1280, showBackground = true)
@Composable private fun TabletPortraitPrototype() = JaskLayoutPrototype(800)
@Preview(name = "Tablet landscape · 1280 × 800", widthDp = 1280, heightDp = 800, showBackground = true)
@Composable private fun TabletLandscapePrototype() = JaskLayoutPrototype(1280)
