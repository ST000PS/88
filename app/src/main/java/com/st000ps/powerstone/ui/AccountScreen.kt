package com.st000ps.powerstone.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.st000ps.powerstone.data.Quality
import kotlinx.coroutines.launch

/** Runs a suspending action with a busy flag and an error message, for the forms below. */
private class Busy {
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
}

@Composable
private fun rememberBusy() = remember { Busy() }

@Composable
fun AccountScreen(state: UiState, vm: AppViewModel, openSound: () -> Unit) {
    // Coming back from Stripe (checkout / portal): pick up the new plan.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (state.me.signedIn) vm.refreshMe() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Account", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        if (state.me.signedIn) {
            Text(state.me.email ?: "Signed in")
            Text(
                when {
                    state.me.isSubscriber -> "SUBSCRIBER${state.me.plan?.let { " · ${it.uppercase()}" } ?: ""}"
                    else -> "NO ACTIVE PLAN · previews only"
                },
                color = if (state.me.isSubscriber) Rose else Dim,
            )
            Spacer(Modifier.height(16.dp))
            Subscription(state, vm)
            Divider()
            PasswordSection(state, vm)
        } else {
            SignInForm(vm)
            Divider()
            Text("Not a subscriber? 28-second previews of everything are free. Subscribe from here after signing in.", style = MaterialTheme.typography.bodySmall, color = Dim)
        }
        Divider()
        DownloadsSection(state, vm)
        Divider()
        QualitySection(state, vm)
        Divider()
        Row(Modifier.fillMaxWidth().clickable(onClick = openSound).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("SOUND", style = MaterialTheme.typography.labelLarge, color = Dim)
                Text("EQ · vinyl stop · video · visualizer")
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
        if (state.me.signedIn) {
            Divider()
            OutlinedButton(onClick = { vm.logout() }) { Text("SIGN OUT") }
        }
    }
}

@Composable
private fun Divider() = HorizontalDivider(color = Edge, modifier = Modifier.padding(vertical = 20.dp))

@Composable
private fun Subscription(state: UiState, vm: AppViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val b = rememberBusy()
    fun open(fetch: suspend () -> String) {
        b.busy = true; b.message = null
        scope.launch {
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fetch()))) } catch (e: Exception) { b.message = e.message }
            b.busy = false
        }
    }
    if (!state.me.isSubscriber) {
        val (month, year) = state.me.prices
        Text("SUBSCRIBE", style = MaterialTheme.typography.labelLarge, color = Dim)
        Text("The full remastered catalogue, unreleased & rare trax, music videos, repeat and more.", style = MaterialTheme.typography.bodySmall, color = Dim)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !b.busy, onClick = { open { vm.checkoutUrl("month") } }) { Text("$month / MONTH") }
            OutlinedButton(enabled = !b.busy, onClick = { open { vm.checkoutUrl("year") } }) { Text("$year / YEAR") }
        }
        Text("Checkout opens in your browser (Stripe). Come back here when it's done.", style = MaterialTheme.typography.bodySmall, color = Dim)
    }
    if (state.me.status != null && !state.me.owner) {
        Spacer(Modifier.height(8.dp))
        OutlinedButton(enabled = !b.busy, onClick = { open { vm.portalUrl() } }) { Text("MANAGE SUBSCRIPTION") }
    }
    if (b.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
    b.message?.let { Text(it, color = Rose) }
}

@Composable
private fun PasswordSection(state: UiState, vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    val b = rememberBusy()
    var pw by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(false) }
    Text("PASSWORD", style = MaterialTheme.typography.labelLarge, color = Dim)
    Text(if (state.password.hasPassword) "You can sign in with your email and password." else "Make a password to sign in without waiting for an email code.",
        style = MaterialTheme.typography.bodySmall, color = Dim)
    if (!open) {
        TextButton(onClick = { open = true; b.message = null }) { Text(if (state.password.hasPassword) "CHANGE PASSWORD" else "MAKE A PASSWORD") }
        b.message?.let { Text(it, color = Dim) }
        return
    }
    OutlinedTextField(
        value = pw, onValueChange = { pw = it }, label = { Text(if (state.password.hasPassword) "New password · 8+ characters" else "Password · 8+ characters") },
        singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
    )
    Row {
        Button(enabled = !b.busy, onClick = {
            if (pw.length < 8) { b.message = "Use at least 8 characters."; return@Button }
            b.busy = true; b.message = null
            scope.launch {
                try { vm.setPassword(pw); pw = ""; open = false; b.message = "Password saved." } catch (e: Exception) { b.message = e.message }
                b.busy = false
            }
        }) { Text("SAVE") }
        TextButton(onClick = { open = false; pw = "" }) { Text("CANCEL") }
    }
    b.message?.let { Text(it, color = Rose) }
}

@Composable
private fun QualitySection(state: UiState, vm: AppViewModel) {
    Text("AUDIO QUALITY", style = MaterialTheme.typography.labelLarge, color = Dim)
    Quality.entries.forEach { q ->
        Row(Modifier.fillMaxWidth().clickable { vm.setQuality(q) }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = state.quality == q, onClick = { vm.setQuality(q) })
            Text(q.label)
        }
    }
    val r = vm.resolvedQuality()
    val mb = when (r) { Quality.LO -> 0.5; Quality.STD -> 1.2; else -> 2.4 }
    Text(
        "Now streaming ${r.label.substringBefore(" ·").uppercase()} · about $mb MB per minute." +
            if (state.me.isSubscriber) "" else " Previews always play at standard quality.",
        style = MaterialTheme.typography.bodySmall, color = Dim,
    )
    Text("Applies to the next track you start.", style = MaterialTheme.typography.bodySmall, color = Dim)
}

@Composable
private fun SignInForm(vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var usePassword by rememberSaveable { mutableStateOf(true) }
    var stay by rememberSaveable { mutableStateOf(true) }
    var codeSent by rememberSaveable { mutableStateOf(false) }
    val b = rememberBusy()

    fun run(block: suspend () -> Unit) {
        b.busy = true; b.message = null
        scope.launch {
            try { block() } catch (e: Exception) { b.message = e.message ?: "Something went wrong." }
            b.busy = false
        }
    }

    Text("Sign in with your thepowerstone.com account.", color = Dim)
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = email, onValueChange = { email = it }, label = { Text("Email") }, singleLine = true,
        enabled = !codeSent, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    if (usePassword) {
        OutlinedTextField(
            value = password, onValueChange = { password = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { stay = !stay }) {
            Checkbox(checked = stay, onCheckedChange = { stay = it })
            Column {
                Text("Stay signed in")
                Text("Kept encrypted on this phone only; signs you back in if the site logs you out.", style = MaterialTheme.typography.bodySmall, color = Dim)
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(enabled = !b.busy && email.isNotBlank() && password.isNotEmpty(), onClick = {
            run { vm.passwordLogin(email.trim(), password, stay) }
        }) { Text("SIGN IN") }
    } else if (!codeSent) {
        Button(enabled = !b.busy && email.contains('@'), onClick = {
            run { vm.requestCode(email.trim()); codeSent = true }
        }) { Text("EMAIL ME A CODE") }
    } else {
        Text("Enter the 6-digit code sent to ${email.trim()}. Not there? Check spam.", style = MaterialTheme.typography.bodySmall, color = Dim)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) }, label = { Text("Code") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !b.busy && code.length == 6, onClick = { run { vm.verifyCode(email.trim(), code) } }) { Text("SIGN IN") }
            TextButton(onClick = { codeSent = false; code = "" }) { Text("CHANGE EMAIL") }
        }
    }
    if (b.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
    b.message?.let { Text(it, color = Rose, modifier = Modifier.padding(top = 12.dp)) }
    Spacer(Modifier.height(12.dp))
    TextButton(onClick = { usePassword = !usePassword; b.message = null }) {
        Text(if (usePassword) "Use an email code instead" else "Use a password instead")
    }
}

@Composable
private fun DownloadsSection(state: UiState, vm: AppViewModel) {
    val ctx = LocalContext.current
    @Suppress("UNUSED_EXPRESSION") state.offlineVersion
    val store = ctx.applicationContext.let { (it as com.st000ps.powerstone.PowerstoneApp).offline }
    Text("DOWNLOADS", style = MaterialTheme.typography.labelLarge, color = Dim)
    Text(
        "Subscribers can download releases, playlists and favourites (↓) to listen offline. They play offline for 30 days after your " +
            "subscription was last confirmed, and are removed if you sign out or the subscription ends.",
        style = MaterialTheme.typography.bodySmall, color = Dim,
    )
    Text(
        if (store.count == 0) "No downloads yet."
        else "${store.count} tracks · ${store.totalBytes / 1_048_576} MB" + if (store.usable) "" else " · locked until your subscription is confirmed online",
        modifier = Modifier.padding(top = 6.dp),
    )
    if (store.count > 0) OutlinedButton(onClick = { vm.removeAllDownloads() }, modifier = Modifier.padding(top = 6.dp)) { Text("REMOVE ALL DOWNLOADS") }
}
