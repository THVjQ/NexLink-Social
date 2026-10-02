package com.nexlink.social

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.nexlink.social.core.session.SessionState
import com.nexlink.social.ui.chrome.Chrome
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * §14.16 — one settings screen.
 *
 * The overflow menu had grown to eleven items, which is the point at which a
 * menu stops being a shortcut and becomes a list you have to read. Worse, it
 * was a FLAT list: "Recovery key" and "Buy me a coffee" sat next to each other
 * with nothing to say that one of them is the difference between keeping your
 * message history and losing it.
 *
 * Three things stay in the menu — inviting someone, opening this screen, and
 * signing out — because those are the only ones with a reason to be one tap
 * from the inbox. Everything else is grouped here under headings that say what
 * the group is for.
 *
 * Nothing was removed. A settings screen that quietly drops a destination is
 * worse than a long menu, because the destination is still reachable in the
 * user's memory and no longer on their screen.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var chrome: Chrome
    private lateinit var page: Chrome.Page
    private var state: SessionState = SessionState.SignedOut

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chrome = Chrome(this)
        page = chrome.page("Settings", onBack = { finish() })
        setContentView(page.root)

        lifecycleScope.launch {
            SessionProvider.manager(this@SettingsActivity).state.collectLatest {
                state = it; render()
            }
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from Recovery key or Devices can change what this screen
        // should say about them.
        render()
    }

    private fun render() {
        val c = page.content
        c.removeAllViews()
        val signedIn = state as? SessionState.SignedIn

        if (signedIn == null) {
            c.addView(chrome.emptyState(
                "Not signed in",
                "Sign in to NexLink Social to reach your account settings."))
            return
        }

        // ── who you are ────────────────────────────────────────────────────
        c.addView(chrome.sectionHeader("Account"))
        card(c) {
            // The id is the LABEL, not the value. infoRow puts label and value
            // on one line, and a Matrix id is long enough to squeeze "Signed in
            // as" onto two lines beside it — which reads as a layout accident.
            add(chrome.infoRow(
                label = signedIn.userId.value,
                detail = "Signed in. Your username is permanent and can't be changed."))
        }

        // ── the things that decide whether history survives ────────────────
        c.addView(chrome.sectionHeader("Security"))
        card(c) {
            add(chrome.infoRow(
                label = "Recovery key",
                value = if (signedIn.keyBackupHealthy) "Set up" else "Not set up",
                detail = if (signedIn.keyBackupHealthy)
                    "Restores your message history on a new device."
                else
                    "Without one, losing this phone loses your message history.",
                onClick = { startActivity(RecoverySetupActivity.intent(this@SettingsActivity)) }))
            add(chrome.rowDivider(insetStartDp = 16))
            add(chrome.infoRow(
                label = "Verify this device",
                detail = "Confirm this device is yours, so your other devices trust it.",
                onClick = { startActivity(VerifyActivity.intent(this@SettingsActivity)) }))
            add(chrome.rowDivider(insetStartDp = 16))
            add(chrome.infoRow(
                label = "Your devices",
                detail = "See everywhere you're signed in, and remove anything you don't recognise.",
                onClick = { startActivity(DevicesActivity.intent(this@SettingsActivity)) }))
        }

        // ── how it looks and what it gives away ────────────────────────────
        c.addView(chrome.sectionHeader("Appearance"))
        card(c) {
            add(chrome.infoRow(
                label = "Theme",
                value = SocialPrefs.themeLabel(this@SettingsActivity),
                detail = "Follow system, or pin it to light or dark.",
                onClick = { chooseTheme() }))
        }

        c.addView(chrome.sectionHeader("Notifications"))
        card(c) {
            val on = SocialPrefs.showNotificationContent(this@SettingsActivity)
            add(chrome.infoRow(
                label = "Show message content",
                value = if (on) "On" else "Off",
                detail = if (on)
                    "Notifications show who sent what. Anyone who can see your "
                        + "screen can read them without unlocking."
                else
                    "Notifications say only that a message arrived.",
                onClick = {
                    SocialPrefs.setShowNotificationContent(this@SettingsActivity, !on)
                    render()
                }))
        }

        // ── people ─────────────────────────────────────────────────────────
        c.addView(chrome.sectionHeader("People"))
        card(c) {
            add(chrome.infoRow(
                label = "Invite someone",
                detail = "Create a code so someone can join.",
                onClick = { startActivity(InviteActivity.intent(this@SettingsActivity)) }))
            add(chrome.rowDivider(insetStartDp = 16))
            add(chrome.infoRow(
                label = "Blocked people",
                detail = "Blocking is immediate and doesn't tell the other person.",
                onClick = { startActivity(BlockedActivity.intent(this@SettingsActivity)) }))
        }

        // ── your data ──────────────────────────────────────────────────────
        c.addView(chrome.sectionHeader("Your data"))
        card(c) {
            add(chrome.infoRow(
                label = "Export my messages",
                detail = "Your history lives on this device. Only you can export it.",
                onClick = { startActivity(ExportActivity.intent(this@SettingsActivity)) }))
            add(chrome.rowDivider(insetStartDp = 16))
            add(chrome.infoRow(
                label = "Storage",
                detail = "How much space messages and media use, and what's safe to clear.",
                onClick = { startActivity(StorageActivity.intent(this@SettingsActivity)) }))
        }

        // ── the documents, and the tip jar ─────────────────────────────────
        c.addView(chrome.sectionHeader("About"))
        card(c) {
            add(chrome.infoRow(
                label = "Terms of Service",
                onClick = {
                    startActivity(PolicyActivity.intent(this@SettingsActivity, PolicyActivity.DOC_TERMS))
                }))
            add(chrome.rowDivider(insetStartDp = 16))
            add(chrome.infoRow(
                label = "Privacy Policy",
                onClick = {
                    startActivity(PolicyActivity.intent(this@SettingsActivity, PolicyActivity.DOC_PRIVACY))
                }))
            add(chrome.rowDivider(insetStartDp = 16))
            add(chrome.infoRow(
                label = "Buy me a coffee",
                detail = "Entirely optional, and it buys nothing — the app is the same either way.",
                onClick = { openUrl(COFFEE_URL) }))
        }

        c.addView(chrome.note("NexLink Social ${versionLabel()}"))
    }

    private fun chooseTheme() {
        val labels = arrayOf("Follow system", "Light", "Dark")
        val values = arrayOf(SocialPrefs.THEME_SYSTEM, SocialPrefs.THEME_LIGHT, SocialPrefs.THEME_DARK)
        val current = values.indexOf(SocialPrefs.theme(this)).coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Theme")
            .setSingleChoiceItems(labels, current) { d, which ->
                SocialPrefs.setTheme(this, values[which])
                d.dismiss()
                // setDefaultNightMode recreates the activity itself; render()
                // afterwards would run against a dead view tree.
                recreate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private inline fun card(parent: LinearLayout, build: LinearLayout.() -> Unit) {
        val card = chrome.card()
        card.build()
        parent.addView(card)
    }

    private fun LinearLayout.add(v: View) = addView(v)

    private fun openUrl(url: String) {
        val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(i) }.onFailure {
            Snackbar.make(findViewById(android.R.id.content),
                "No app on this phone can open a link", Snackbar.LENGTH_LONG).show()
        }
    }

    /**
     * The version, read from the installed package rather than BuildConfig.
     *
     * BuildConfig is not generated for this module (AGP 8 defaults
     * `buildFeatures.buildConfig` to false), and turning it on to print one
     * string is a worse trade than asking the package manager, which is also
     * the version the user's Play listing and bug report will show.
     */
    @Suppress("DEPRECATION")
    private fun versionLabel(): String = runCatching {
        val pi = packageManager.getPackageInfo(packageName, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode
                   else pi.versionCode.toLong()
        "${pi.versionName} ($code)"
    }.getOrDefault("")

    companion object {
        fun intent(ctx: Context): Intent = Intent(ctx, SettingsActivity::class.java)

        /** §10.5 — the same link NexLink's own settings screen carries. */
        const val COFFEE_URL = "https://buymeacoffee.com/THVjQ"
    }
}
