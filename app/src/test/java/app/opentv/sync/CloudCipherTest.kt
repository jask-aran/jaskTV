package app.opentv.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class CloudCipherTest {
    @Test fun encryptedSnapshotRoundTrip() {
        val plain = """{"version":4,"profiles":["Me"],"collections":[]}"""
        val phrase = "a long shared encryption passphrase"
        val encrypted = CloudCipher.seal(plain, phrase)
        assertThat(encrypted).startsWith("jasktv:v1:")
        assertThat(encrypted).doesNotContain("profiles")
        assertThat(CloudCipher.open(encrypted, phrase)).isEqualTo(plain)
    }

    @Test fun randomNonceMakesIdenticalDataProduceDifferentEncryptedFiles() {
        val a = CloudCipher.seal("save", "twelve-or-more-characters")
        val b = CloudCipher.seal("save", "twelve-or-more-characters")
        assertThat(a).isNotEqualTo(b)
    }

    @Test fun wrongPhraseAndModifiedCiphertextCannotBeRead() {
        val encrypted = CloudCipher.seal("private library data", "a correct passphrase")
        assertThrows(Exception::class.java) {
            CloudCipher.open(encrypted, "an incorrect passphrase")
        }
        val damaged = encrypted.dropLast(5) + "AAAAA"
        assertThrows(Exception::class.java) {
            CloudCipher.open(damaged, "a correct passphrase")
        }
    }
}
