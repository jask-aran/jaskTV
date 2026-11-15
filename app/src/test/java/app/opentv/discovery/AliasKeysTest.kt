package app.opentv.discovery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AliasKeysTest {
    @Test fun providerDecorationsFoldIntoOfficialIdentity() {
        assertThat(AliasKeys.key("UK | Sky Sports F1 HEVC FHD"))
            .isEqualTo(AliasKeys.key("Sky Sports F1"))
        assertThat(AliasKeys.key("UK | Sky Sports Main Event"))
            .isNotEqualTo(AliasKeys.key("Sky Sports F1"))
    }

    @Test fun ambiguousCountryMustNotSilentlyMap() {
        val uk = ChannelIdentity("Example.uk", "Example Sports", "GB", listOf(), null)
        val au = ChannelIdentity("Example.au", "Example Sports", "AU", listOf(), null)
        assertThat(AliasKeys.preferred(listOf(uk, au), null)).isNull()
        assertThat(AliasKeys.preferred(listOf(uk, au), "UK")).isEqualTo(uk)
        assertThat(AliasKeys.preferred(listOf(uk, au), "AU")).isEqualTo(au)
        assertThat(AliasKeys.preferred(listOf(uk), "US")).isNull()
    }

    @Test fun oneUniqueCandidateCanMapWithoutCountry() {
        val uk = ChannelIdentity("SkySportsF1.uk", "Sky Sports F1", "GB", listOf("Sky F1"), "Sky")
        assertThat(AliasKeys.preferred(listOf(uk, uk), null)).isEqualTo(uk)
    }
}
