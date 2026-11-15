/*
 * jaskTV — GPL-3.0-or-later.
 */
package app.opentv.discovery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BroadcasterMatcherTest {
    @Test fun matchesRegionAndCodecDecoratedChannelNames() {
        assertTrue(BroadcasterMatcher.matches("UK | SKY SPORTS F1 HEVC 1080p", "Sky Sports F1"))
        assertTrue(BroadcasterMatcher.matches("US: NBA-TV 4K", "NBA TV"))
    }

    @Test fun excludesDifferentChannelsFromSameBroadcaster() {
        assertFalse(BroadcasterMatcher.matches("Sky Sports Main Event", "Sky Sports F1"))
        assertFalse(BroadcasterMatcher.matches("Sky Sports News", "Sky Sports NBA"))
    }

    @Test fun excludesShortAndEmptyMatches() {
        assertFalse(BroadcasterMatcher.matches("ABC", ""))
        assertFalse(BroadcasterMatcher.matches("F1", "F1"))
    }

    @Test fun handlesCaseAndPunctuationWithoutLocaleDependence() {
        assertTrue(BroadcasterMatcher.matches("USA | ESPN-HD", "espn"))
        assertFalse(BroadcasterMatcher.matches("FOX NEWS", "Fox Sports"))
    }
}
