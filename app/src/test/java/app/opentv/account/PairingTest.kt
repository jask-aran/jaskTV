package app.opentv.account

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PairingTest {
    @Test fun theAccountKeyReachesOnlyTheTv() {
        val key = AccountCrypto.newAccountKey()
        val tv = AccountCrypto.pairKeyPair()
        val tvKey = AccountCrypto.publicKeyText(tv)
        val (phoneKey, sealed) = AccountCrypto.sealForTv(key, tvKey, "ABCD2345")
        assertArrayEquals(key, AccountCrypto.openFromPhone(tv, phoneKey, "ABCD2345", sealed))
        // Another device's key (say, the server swapping in its own) can't open it, nor can the same TV under another code.
        assertTrue(runCatching { AccountCrypto.openFromPhone(AccountCrypto.pairKeyPair(), phoneKey, "ABCD2345", sealed) }.isFailure)
        assertTrue(runCatching { AccountCrypto.openFromPhone(tv, phoneKey, "ZZZZ2345", sealed) }.isFailure)
        assertEquals(6, AccountCrypto.pairFingerprint(tvKey).length)
    }

    /** The whole flow through the real server code on `wrangler dev` (local only; skipped when it isn't running). */
    @Test fun aTvSignsInFromThePhone() = runBlocking {
        val base = LOCAL_SERVER
        assumeTrue(runCatching { (java.net.URL("$base/v1/health").openConnection() as java.net.HttpURLConnection).run { connectTimeout = 1000; readTimeout = 1000; inputStream.bufferedReader().readText() }.contains("\"local\"") }.getOrDefault(false))
        val api = HttpAccountServer(base)
        val phone = api.signInWithGoogle("dev:pair-${System.nanoTime()}:tester@example.com", "Phone", "phone")
        val key = AccountCrypto.newAccountKey()
        // TV: a key pair and a code.
        val tv = AccountCrypto.pairKeyPair()
        val start = api.pairStart(AccountCrypto.publicKeyText(tv), "Living Room TV", "tv")
        assertEquals("pending", api.pairTake(start.code, start.secret).state)
        // Phone: looks it up, seals the key for that TV, approves.
        val info = api.pairInfo(phone.token, start.code)
        assertEquals("Living Room TV", info.device.name)
        val (phoneKey, sealed) = AccountCrypto.sealForTv(key, info.tvKey, start.code)
        api.pairApprove(phone.token, start.code, phoneKey, sealed)
        // TV: collects its sign-in and the key.
        val r = api.pairTake(start.code, start.secret).result!!
        assertArrayEquals(key, AccountCrypto.openFromPhone(tv, r.phoneKey, start.code, r.sealedKey))
        assertEquals(phone.user.id, r.user.id)
        assertEquals(listOf("Phone", "Living Room TV"), api.me(r.token).devices.map { it.name })
    }

    companion object {
        /** Tests only ever talk to a server on this machine, never a deployed one (no Cloudflare usage). */
        const val LOCAL_SERVER = "http://127.0.0.1:8787"
    }
}
