package app.opentv.account

import app.opentv.core.get
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AccountSyncTest {
    @Test fun recoveryCodeWrapsTheAccountKey() {
        val key = AccountCrypto.newAccountKey()
        val code = AccountCrypto.newRecoveryCode()
        assertTrue(Regex("""^[0-9A-Z]{4}(-[0-9A-Z]{4}){4}$""").matches(code))
        val vault = AccountCrypto.wrap(key, code)
        assertArrayEquals(key, AccountCrypto.unwrap(vault, code))
        // Typed loosely: lower case, spaces, O for 0.
        assertArrayEquals(key, AccountCrypto.unwrap(vault, code.lowercase().replace("-", " ").replace('0', 'o')))
        assertNull(AccountCrypto.unwrap(vault, AccountCrypto.newRecoveryCode()))
    }

    @Test fun passwordUnlocksTheAccountKeyOnAnyDevice() {
        val keys = AccountCrypto.passwordKeys("Someone@Example.com ", "correct horse battery")
        val again = AccountCrypto.passwordKeys("someone@example.com", "correct horse battery")
        assertEquals(keys.loginKey, again.loginKey) // the same on every device
        assertTrue(Regex("^[A-Za-z0-9+/=]{40,100}$").matches(keys.loginKey))
        val accountKey = AccountCrypto.newAccountKey()
        val vault = AccountCrypto.Vault(AccountCrypto.wrapWithKey(accountKey, keys.wrapKey), AccountCrypto.wrap(accountKey, AccountCrypto.newRecoveryCode()))
        val parsed = AccountCrypto.Vault.parse(vault.encode())
        assertArrayEquals(accountKey, AccountCrypto.unwrapWithKey(parsed.password!!, again.wrapKey))
        assertNull(AccountCrypto.unwrapWithKey(parsed.password!!, AccountCrypto.passwordKeys("someone@example.com", "wrong password").wrapKey))
        // What the server sees (the login key) doesn't unlock anything.
        assertFalse(keys.loginKey.toByteArray().contentEquals(keys.wrapKey))
        assertEquals(AccountCrypto.Vault(null, "jv1:x"), AccountCrypto.Vault.parse("jv1:x"))
    }

    @Test fun preferencesCarryEverythingAcross() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val settings = app.opentv.core.AppSettings.get(context)
        val prefs = AccountPrefs(context, settings)
        val fromOtherDevice = prefs.read().toMutableMap().apply {
            put("tmdbKey", JsonPrimitive("tmdb-123")); put("omdbKey", JsonPrimitive("omdb-456"))
            put("pinHash", JsonPrimitive("abc123")); put("liveRegions", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("AU"), JsonPrimitive("IN"))))
            put("channelSort", JsonPrimitive("NAME")); put("catalogueSort", JsonPrimitive("YEAR"))
            put("nas", kotlinx.serialization.json.JsonArray(listOf("nas.local", "media", "jaskTV", "me", "pw").map(::JsonPrimitive)))
            put("stremioAddons", kotlinx.serialization.json.Json.parseToJsonElement("""[{"manifestUrl":"https://a.example/manifest.json","name":"A"}]"""))
            put("fromANewerVersion", JsonPrimitive(true)) // ignored
        }
        prefs.apply(fromOtherDevice)
        assertEquals("tmdb-123", settings.tmdbApiKey.value)
        assertEquals("omdb-456", settings.omdbApiKey.value)
        assertEquals("abc123", settings.pinHash()); assertTrue(settings.pinIsSet.value)
        assertEquals(setOf("AU", "IN"), settings.liveRegions.value)
        assertEquals(app.opentv.core.AppSettings.ChannelSort.NAME, settings.channelSort.value)
        assertEquals("pw", settings.smbPassword.value)
        assertEquals(listOf("A"), settings.stremioAddons.value.map { it.name })
        assertEquals("YEAR", context.getSharedPreferences("jask_catalogue", 0).getString("sort", null))
        // Reading back gives what was applied, so the two devices agree and nothing is pushed again.
        assertEquals(fromOtherDevice - "fromANewerVersion", prefs.read())
        // Screen settings travel only between devices of the same kind; where recordings go never travels.
        assertTrue("phone.uiScale" in prefs.read())
        assertFalse(AccountPrefs(context, settings, "tv").read().keys.any { it.startsWith("phone.") })
        assertFalse(prefs.read().keys.any { it in setOf("usbTree", "recordingTarget", "hiddenUnlocked") })
    }

    @Test fun itemsAreBoundToTheirKey() {
        val key = AccountCrypto.newAccountKey()
        val sealed = AccountCrypto.seal(key, "source", "a", "secret playlist")
        assertFalse(sealed.contains("secret"))
        assertEquals("secret playlist", AccountCrypto.open(key, "source", "a", sealed))
        // The server swapping one item's data into another's slot is caught.
        assertTrue(runCatching { AccountCrypto.open(key, "source", "b", sealed) }.isFailure)
        assertTrue(runCatching { AccountCrypto.open(AccountCrypto.newAccountKey(), "source", "a", sealed) }.isFailure)
    }

    @Test fun pbkdf2MatchesTheStandardVector() {
        // RFC 7914 §11 PBKDF2-HMAC-SHA256 test vector (P="passwd", S="salt", c=1), first 32 bytes.
        val out = AccountCrypto.pbkdf2("passwd", "salt".toByteArray(), 1)
        assertEquals("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc", out.joinToString("") { "%02x".format(it) })
    }

    /** A device's playlists and preferences, in memory. */
}
