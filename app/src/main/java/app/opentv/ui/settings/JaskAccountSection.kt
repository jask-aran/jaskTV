package app.opentv.ui.settings

import app.opentv.ui.jaskFocusLift
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material3.Icon
import androidx.compose.ui.text.style.TextOverflow

import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.DisposableEffect
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.opentv.account.AccountManager
import app.opentv.account.AccountManager.Status

/**
 * The account card at the top of Settings. Optional: signed out it offers email and password (and Google, when set up); signed in it shows
 * sync status, this account's devices, the recovery code and sign out. Hidden when the build has no server.
 */
@Composable
internal fun AccountGroup() {
    val context = LocalContext.current
    val account = remember { AccountManager.get(context) }
    val status by account.status.collectAsState()
    if (status is Status.SignedOut && !(status as Status.SignedOut).available) return
    when (val s = status) {
        is Status.SignedOut -> SignedOutCard(account, s)
        is Status.Working -> Group("Account") { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), color = JaskInk, strokeWidth = 2.dp)
            Spacer(Modifier.width(14.dp)); Text(s.message, color = JaskInk, style = MaterialTheme.typography.bodyLarge)
        } }
        is Status.Locked -> Group("Account") {
            Header(s.email.take(1), s.email, "Signed in. Enter your recovery code to unlock your synced data on this device.")
            var code by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(code, { code = it.uppercase() }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                singleLine = true, placeholder = { Text("XXXX-XXXX-XXXX-XXXX-XXXX") },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace, color = JaskInk),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = JaskInk, unfocusedBorderColor = Color.White.copy(alpha = .3f)))
            s.error?.let { Error(it) }
            Actions {
                JaskPill("Unlock", primary = true, enabled = code.count { it.isLetterOrDigit() } >= 20) { account.unlock(code) }
                JaskPill("Sign out") { account.signOut() }
            }
        }
        is Status.SaveCode -> Group("Account") {
            Header("!", "Save your recovery code", "It unlocks your data on a new device if you forget your password. Keep it in a password manager.")
            Text(s.code, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = .08f)).padding(14.dp),
                color = JaskInk, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            Actions {
                JaskPill("Copy") {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("jaskTV recovery code", s.code))
                }
                JaskPill("I've saved it", primary = true) { account.acknowledgeCode() }
            }
        }
        is Status.Ready -> SignedInCards(account, s)
    }
}

/** Signed out: the TV's QR code (on a TV), then email and password, with "Create account" and "Forgot password?". */
@Composable
private fun SignedOutCard(account: AccountManager, s: Status.SignedOut) = Group("Account") {
    val context = LocalContext.current
    Header(null, "Sign in to jaskTV", "Optional. Keeps playlists, settings and watch history the same on every device, encrypted so only your devices can read them.")
    val tv = remember { app.opentv.isRunningOnTelevision(context) }
    var showCode by rememberSaveable { mutableStateOf(tv) }
    if (showCode) { JaskDivider(); TvPairPanel(account); JaskDivider() }
    var create by rememberSaveable { mutableStateOf(false) }
    var forgot by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    Field(email, { email = it.trim() }, "Email", KeyboardType.Email)
    Field(password, { password = it }, "Password", KeyboardType.Password, secret = true)
    if (create) {
        Field(confirm, { confirm = it }, "Password again", KeyboardType.Password, secret = true)
        Hint("At least 10 characters.")
    }
    val problem = when {
        !create -> null
        password.isNotEmpty() && password.length < 10 -> "Use at least 10 characters."
        confirm.isNotEmpty() && confirm != password -> "The passwords don't match."
        else -> null
    }
    (problem ?: s.error)?.let { Error(it) }
    val ready = email.contains('@') && password.isNotEmpty() && (!create || (password.length >= 10 && confirm == password))
    Actions {
        JaskPill(if (create) "Create account" else "Sign in", primary = true, enabled = ready) { account.signInWithPassword(email, password, create) }
        if (s.google) JaskPill("Google") { context.findActivity()?.let(account::signInWithGoogle) }
    }
    Row(Modifier.padding(start = 8.dp, bottom = 10.dp)) {
        SmallLink(if (create) "I have an account" else "Create account") { create = !create; forgot = false }
        if (!create) SmallLink("Forgot password?") { forgot = !forgot }
        if (!showCode) SmallLink("Sign in from another device") { showCode = true }
    }
    if (forgot) Hint("On a device that's still signed in, open Settings → Account → Change password: no old password needed. " +
        "Then sign in here with the new one.", Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp))
}

/** Signed in: who and sync state; this account's devices; sign in a TV; password and recovery; sign out and delete. */
@Composable
private fun SignedInCards(account: AccountManager, s: Status.Ready) {
    val playing by account.nowPlaying.collectAsState()
    Group("Account") {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Initial(s.name ?: s.email)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(s.name ?: s.email, color = JaskInk, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(when {
                    s.syncing -> "Syncing…"
                    s.error != null -> "Not synced · tap to retry"
                    s.lastSyncAt > 0 -> "Synced " + ago(s.lastSyncAt)
                    else -> "Signed in"
                } + if (s.name != null) " · ${s.email}" else "", color = if (s.error != null) Color(0xFFFF8A80) else JaskDim,
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            androidx.compose.material3.IconButton({ account.syncNow() }, enabled = !s.syncing) {
                Icon(Icons.Outlined.Sync, "Sync now", tint = if (s.syncing) JaskDim else JaskInk)
            }
        }
    }
    Group("Devices") {
        s.devices.forEachIndexed { i, d ->
            if (i > 0) JaskDivider()
            var confirmRemove by remember(d.id) { mutableStateOf(false) }
            val p = playing[d.id]?.takeIf { it.title != null && System.currentTimeMillis() - it.at < 5 * 60_000 }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(when (d.kind) { "tv" -> Icons.Outlined.Tv; "core" -> Icons.Outlined.Dns; "web" -> Icons.Outlined.Language; else -> Icons.Outlined.PhoneAndroid }, null, Modifier.size(24.dp), tint = JaskInk)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.name + if (d.id == s.deviceId) "  ·  This device" else "", color = JaskInk, style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (p != null) (if (p.playing || p.kind == "live") "Playing · " else "Paused · ") + app.opentv.ui.vod.cleanShowTitle(p.title.orEmpty()) + (p.detail?.let { " · $it" } ?: "")
                        else "Last seen " + ago(d.lastSeen),
                        color = if (p != null) JaskInk else JaskDim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (d.id != s.deviceId) {
                    if (confirmRemove) SmallLink("Remove?", danger = true) { account.removeDevice(d.id); confirmRemove = false }
                    else SmallLink("Remove") { confirmRemove = true }
                }
            }
        }
        JaskDivider()
        var tvCodeOpen by rememberSaveable { mutableStateOf(false) }
        val context = LocalContext.current
        // Scans the TV's code in the app (Google's scanner from Play services: no camera permission), then asks to sign it in.
        Link(Icons.Outlined.QrCodeScanner, "Sign in a TV", "Scan the code on the TV") {
            com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context,
                com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
                    .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE).build())
                .startScan()
                .addOnSuccessListener { code ->
                    val uri = android.net.Uri.parse(code.rawValue ?: "")
                    val params = (uri.fragment ?: "").split('&').mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 } }.associate { it[0] to it[1] }
                    val c = params["c"]
                    if (uri.path == "/pair" && c != null) account.offerPairing(c, params["k"]) else tvCodeOpen = true
                }
                // No Play services (or the scanner can't load): type the code instead.
                .addOnFailureListener { tvCodeOpen = true }
        }
        if (!tvCodeOpen) SmallLink("Enter the code instead", modifier = Modifier.padding(start = 56.dp, bottom = 8.dp)) { tvCodeOpen = true }
        if (tvCodeOpen) {
            var tvCode by rememberSaveable { mutableStateOf("") }
            Field(tvCode, { tvCode = it.uppercase().filter(Char::isLetterOrDigit).take(8) }, "Code on the TV", KeyboardType.Ascii)
            Actions { JaskPill("Find TV", primary = true, enabled = tvCode.length == 8) { account.offerPairing(tvCode, null); tvCode = ""; tvCodeOpen = false } }
        }
    }
    var sheet by remember { mutableStateOf<String?>(null) }
    Group("Security") {
        Link(Icons.Outlined.Password, "Change password", "No old password needed on a signed-in device") { sheet = "password" }
        JaskDivider()
        Link(Icons.Outlined.Key, "New recovery code", "Replaces the old one") { account.newRecoveryCode() }
        JaskDivider()
        Link(Icons.AutoMirrored.Outlined.Logout, "Sign out of this device", "Playlists and settings stay here; they stop syncing") { sheet = "signout" }
        JaskDivider()
        Link(Icons.Outlined.DeleteForever, "Delete account", "Removes everything synced from the server") { sheet = "delete" }
    }
    when (sheet) {
        "password" -> ChangePasswordSheet(account) { sheet = null }
        "signout" -> ConfirmSheet("Sign out of this device?", "Playlists, settings and history stay on this device; they just stop syncing.",
            "Sign out", { account.signOut() }) { sheet = null }
        "delete" -> ConfirmSheet("Delete your account?", "Everything synced is deleted from the server and every device is signed out. " +
            "Each device keeps its own playlists and settings. This can't be undone.", "Delete account", { account.deleteAccount() }) { sheet = null }
    }
}

@Composable
private fun ChangePasswordSheet(account: AccountManager, onDone: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    app.opentv.ui.JaskSheet(onDone) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
            Text("Change password", Modifier.padding(horizontal = 16.dp), color = JaskInk, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Hint("Your other devices stay signed in. Use the new password to sign in on new ones.", Modifier.padding(16.dp, 6.dp, 16.dp, 8.dp))
            Field(password, { password = it }, "New password", KeyboardType.Password, secret = true)
            Field(confirm, { confirm = it }, "New password again", KeyboardType.Password, secret = true)
            val problem = when {
                password.isNotEmpty() && password.length < 10 -> "Use at least 10 characters."
                confirm.isNotEmpty() && confirm != password -> "The passwords don't match."
                else -> null
            }
            (problem ?: result)?.let { if (it == "ok") Hint("Password changed.", Modifier.padding(horizontal = 16.dp)) else Error(it) }
            Actions {
                JaskPill(if (result == "ok") "Done" else "Cancel", onClick = onDone)
                if (result != "ok") JaskPill(if (working) "Saving…" else "Change password", primary = true,
                    enabled = !working && password.length >= 10 && confirm == password) {
                    working = true
                    account.changePassword(password) { error -> working = false; result = error ?: "ok" }
                }
            }
        }
    }
}

@Composable
private fun ConfirmSheet(title: String, body: String, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) =
    app.opentv.ui.JaskSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(title, color = JaskInk, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(body, color = JaskDim, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                JaskPill("Cancel", onClick = onDismiss)
                JaskPill(action, primary = true) { onConfirm(); onDismiss() }
            }
        }
    }

@Composable private fun Header(initial: String?, title: String, subtitle: String) =
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (initial != null) { Initial(initial); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, color = JaskInk, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = JaskDim, style = MaterialTheme.typography.bodySmall)
        }
    }

@Composable private fun Initial(name: String) = Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF3A3632)), contentAlignment = Alignment.Center) {
    Text(name.take(1).uppercase(), color = JaskInk, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable private fun Hint(text: String, modifier: Modifier = Modifier.padding(horizontal = 16.dp)) =
    Text(text, modifier, color = JaskDim, style = MaterialTheme.typography.bodySmall)

/** "just now", "5 minutes ago", "yesterday": never "0 minutes ago". */
private fun ago(millis: Long): String =
    if (System.currentTimeMillis() - millis < 60_000) "just now"
    else android.text.format.DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()

@Composable private fun SmallLink(text: String, danger: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) =
    Text(text, modifier.jaskFocusLift(1.05f).clip(RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        color = if (danger) Color(0xFFFF8A80) else JaskInk, style = MaterialTheme.typography.labelLarge)

@Composable private fun Field(value: String, onChange: (String) -> Unit, label: String, type: KeyboardType, secret: Boolean = false) =
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), singleLine = true,
        label = { Text(label) }, textStyle = MaterialTheme.typography.bodyLarge.copy(color = JaskInk),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = type, autoCorrectEnabled = false),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = JaskInk, unfocusedBorderColor = Color.White.copy(alpha = .3f),
            focusedLabelColor = JaskInk, unfocusedLabelColor = JaskDim))

@Composable private fun Note(text: String) =
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), color = JaskDim, style = MaterialTheme.typography.bodyMedium)

@Composable private fun Error(text: String) =
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall)

@Composable private fun Title(title: String, subtitle: String) = Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
    Text(title, color = JaskInk, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(2.dp))
    Text(subtitle, color = JaskDim, style = MaterialTheme.typography.bodySmall)
}

@Composable private fun Actions(content: @Composable () -> Unit) =
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * What a device with no playlists opens to: sign in to bring everything from your other devices (playlists and
 * their logins, settings, guide sources, watch history), or add a playlist by hand. Once signed in, it waits for the
 * playlists to arrive; then the home takes over while each catalogue downloads.
 */
@Composable
internal fun JaskWelcome(onAddPlaylist: () -> Unit) {
    val context = LocalContext.current
    val account = remember { AccountManager.get(context) }
    val status by account.status.collectAsState()
    // The shell's own spacing: clear of the header at the top and the tab bar at the bottom.
    val shell = app.opentv.ui.LocalJaskContentPadding.current
    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(top = shell.calculateTopPadding() + 16.dp, start = 16.dp, end = 16.dp,
        bottom = shell.calculateBottomPadding() + 16.dp), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Welcome to jaskTV", color = JaskInk, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            val signedIn = status is Status.Ready || status is Status.SaveCode
            // Signed in and synced, still nothing: this account has no playlists yet (its first device).
            val emptyAccount = (status as? Status.Ready)?.let { !it.syncing && it.lastSyncAt > 0 } == true
            Text(when {
                emptyAccount -> "Signed in. Your account has no playlists yet: add one, and your other devices get it too."
                signedIn -> "Signed in. Your playlists, settings and watch history are on their way; channels and movies " +
                    "then download from your provider."
                else -> "Sign in to bring your playlists, settings and watch history from your other devices, or add a playlist."
            },
                color = JaskDim, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            AccountGroup()
            if (!signedIn || emptyAccount) Actions { JaskPill(if (signedIn) "Add a playlist" else "Add a playlist instead", primary = emptyAccount, onClick = onAddPlaylist) }
        }
    }
}


/** On the TV: the QR code (and the code itself) a signed-in phone approves. Asks the server only while on screen. */
@Composable
private fun TvPairPanel(account: AccountManager) {
    DisposableEffect(Unit) { account.startPairing(); onDispose { account.stopPairing() } }
    val pairing by account.tvPairing.collectAsState()
    val p = pairing
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        val qr = remember(p?.link) { p?.link?.takeIf { it.isNotBlank() && !p.expired }?.let { runCatching { qrBitmap(it) }.getOrNull() } }
        Box(Modifier.size(196.dp).clip(RoundedCornerShape(16.dp)).background(Color.White), contentAlignment = Alignment.Center) {
            if (qr != null) androidx.compose.foundation.Image(qr, "Sign-in code", Modifier.size(176.dp),
                filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
            else Text("…", color = Color.Black)
        }
        Column(Modifier.weight(1f)) {
            Text("Sign in with your phone", color = JaskInk, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("Point your phone's camera at the code, and approve in jaskTV. Or on the phone, go to Settings → Account → Sign in a TV and enter:",
                color = JaskDim, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            if (p?.expired == true) {
                Spacer(Modifier.height(4.dp))
                JaskPill("Show a new code", primary = true) { account.stopPairing(); account.startPairing() }
                return@Column
            }
            Text(p?.code?.let { it.take(4) + " " + it.drop(4) } ?: "Getting a code…", color = JaskInk,
                style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            p?.fingerprint?.let { Text("Check: $it", color = JaskDim, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** A QR code as a bitmap, one pixel per module (scaled up crisply by the Image). */
private fun qrBitmap(text: String): androidx.compose.ui.graphics.ImageBitmap {
    val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0,
        mapOf(com.google.zxing.EncodeHintType.MARGIN to 1, com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M))
    val bmp = android.graphics.Bitmap.createBitmap(m.width, m.height, android.graphics.Bitmap.Config.ARGB_8888)
    for (y in 0 until m.height) for (x in 0 until m.width) bmp.setPixel(x, y, if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    return bmp.asImageBitmap()
}

/** On the phone: a TV asking to be signed in (from its QR code or a typed code). Shown over whatever screen is open. */
@Composable
internal fun PairOfferDialog() {
    val context = LocalContext.current
    val account = remember { AccountManager.get(context) }
    val offer by account.pairOffer.collectAsState()
    val o = offer ?: return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = account::dismissPairing,
        title = { Text(if (o.error == null) "Sign in ${o.tvName}?" else "Sign in a TV") },
        text = {
            Column {
                when {
                    o.error != null -> Text(o.error)
                    o.working && o.fingerprint.isEmpty() -> Text("Looking up the code…")
                    else -> {
                        Text("It gets your playlists, settings and watch history, and stays signed in until you remove it in Settings → Account.")
                        if (o.fingerprint.isNotEmpty()) { Spacer(Modifier.height(10.dp)); Text("The TV should show: Check ${o.fingerprint}", fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        },
        confirmButton = {
            if (o.error == null && o.fingerprint.isNotEmpty())
                androidx.compose.material3.TextButton(onClick = account::approvePairing, enabled = !o.working) { Text(if (o.working) "Signing in…" else "Sign in TV") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = account::dismissPairing) { Text(if (o.error == null) "Cancel" else "Close") } },
    )
}
