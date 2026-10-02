package com.nexlink.social

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.nexlink.social.core.BugReporter
import com.nexlink.social.core.rust.RustSocialSession
import com.nexlink.social.core.session.SessionState
import com.nexlink.social.ui.chrome.Chrome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nexlink.social.ui.R as UiR

/**
 * Reporting a fault, from inside the app — §38.
 *
 * **Before this screen there was no way for a user to report anything.**
 * Settings → About had the two policy documents and the tip jar; Element Web's
 * rageshake is off by §20.3 because it uploads logs carrying room and user IDs;
 * and Matrix's report path reports *people*, not faults. So a user who hit a bug
 * had the contact address in the Privacy Policy and nothing else, which in
 * practice meant faults went unreported.
 *
 * What it may send is [BugReporter.Report] and nothing else — §38.3's allowlist
 * lives in that type precisely so this screen cannot widen it. In particular
 * there is **no attachment field**: a screenshot of a messaging app is message
 * content, and accepting it would falsify §9.6.1's acceptance screen (§38.3.2).
 *
 * The MXID box is unticked and stays unticked, matching §31.3.2's report
 * consent. The copy says what declining costs, because "anonymous" sounds free
 * and is not — it also means nobody can come back and ask a question.
 */
class ReportProblemActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        fun col(id: Int) = ContextCompat.getColor(this, id)

        val chrome = Chrome(this)
        val page = chrome.page("Report a problem", onBack = { finish() },
            horizontalPaddingDp = 20)
        val root = page.content
        setContentView(page.root)

        fun text(t: CharSequence, size: Float = 15f, c: Int = UiR.color.social_text,
                 topDp: Int = 0) = TextView(this).apply {
            setText(t)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(col(c))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                .apply { topMargin = dp(topDp) }
        }

        fun gap(h: Int) = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(h))
        }

        root.addView(text("Tell us what went wrong and we will look at it."))
        root.addView(text(
            "Please don't paste message text here. We can't read your messages " +
                "and we'd rather keep it that way — this form is read by a person. " +
                "Describe what happened instead.",
            13f, UiR.color.social_muted, topDp = 8))

        // ── kind ───────────────────────────────────────────────────────────
        // A fault to chase now and a wish for later read completely
        // differently in the operator's list, and telling them apart by
        // reading every one is the thing the sibling product had to fix.
        var kind = BugReporter.Kind.BUG
        root.addView(text("What kind of thing is this?", 13f,
            UiR.color.social_muted, topDp = 20))
        val kindBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(kindBox)

        fun renderKind() {
            kindBox.removeAllViews()
            kindBox.addView(chrome.choiceRow("Something is broken",
                kind == BugReporter.Kind.BUG) { kind = BugReporter.Kind.BUG; renderKind() })
            kindBox.addView(chrome.choiceRow("Something I wish it did",
                kind == BugReporter.Kind.FEATURE) { kind = BugReporter.Kind.FEATURE; renderKind() })
        }
        renderKind()

        // ── the words ──────────────────────────────────────────────────────
        root.addView(text("What happened?", 13f, UiR.color.social_muted, topDp = 20))
        val what = EditText(this).apply {
            hint = "What you were doing, and what the app did."
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 4
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        root.addView(what)

        root.addView(text("What did you expect instead? (optional)", 13f,
            UiR.color.social_muted, topDp = 16))
        val expected = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        root.addView(expected)

        // ── §38.3.1 the MXID box ───────────────────────────────────────────
        // `manager.state` is a Flow, not a StateFlow, so there is no `.value`
        // to read at layout time; and `currentState()` lives on the Rust
        // implementation rather than the SocialSession interface. So this is
        // the cast DevicesActivity:64 already uses for the same need.
        val mxid = (SessionProvider.manager(this).current() as? RustSocialSession)
            ?.currentState()
            ?.let { it as? SessionState.SignedIn }?.userId?.value

        val includeId = CheckBox(this).apply {
            text = "Include my username so you can reply"
            isChecked = false
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                .apply { topMargin = dp(20) }
        }
        if (mxid != null) {
            root.addView(includeId)
            root.addView(text(
                "Without it this report is anonymous — which also means there's " +
                    "no way to reach you if we need more detail. You'll get a " +
                    "reference code either way.",
                13f, UiR.color.social_muted))
        } else {
            // Not signed in — often *why* they are reporting. Say so rather
            // than showing a box that cannot do anything.
            root.addView(text(
                "You're not signed in, so this report will be anonymous. " +
                    "You'll still get a reference code to check it with.",
                13f, UiR.color.social_muted, topDp = 20))
        }

        val status = text("", 14f, UiR.color.social_muted, topDp = 16)
        root.addView(status)

        val go = Button(this).apply {
            text = "Send report"
            tag = Chrome.PRIMARY
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                .apply { topMargin = dp(16) }
        }
        root.addView(go)
        root.addView(gap(24))

        go.setOnClickListener {
            val body = what.text.toString().trim()
            if (body.isEmpty()) {
                status.text = "Please say what happened — that part can't be empty."
                return@setOnClickListener
            }
            go.isEnabled = false
            status.text = "Sending…"

            val report = BugReporter.Report(
                kind = kind,
                whatHappened = body,
                expected = expected.text.toString().trim().ifEmpty { null },
                appVersion = versionLabel(),
                androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                deviceModel = Build.MODEL,
                screen = intent.getStringExtra(EXTRA_SCREEN),
                mxid = if (includeId.isChecked) mxid else null,
            )

            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) {
                    BugReporter(SignInActivity.HOMESERVER).submit(report)
                }
                r.onSuccess { code -> showSaved(root, code, ::text, ::gap) }
                    .onFailure { e ->
                        // §38.10 #1 — the server's own sentence, not a shrug.
                        // Never a success screen over a report that did not save.
                        status.text = e.message
                            ?: "That didn't save. Nothing was recorded — please try again."
                        go.isEnabled = true
                    }
            }
        }
    }

    /** The reference code, and where to check it (§38.5). */
    private fun showSaved(
        root: LinearLayout,
        code: String,
        line: (CharSequence, Float, Int, Int) -> TextView,
        spacer: (Int) -> View,
    ) {
        root.removeAllViews()
        root.addView(line("Saved.", 20f, UiR.color.social_text, 8))
        root.addView(line("Your reference is", 15f, UiR.color.social_muted, 12))
        root.addView(line(code, 26f, UiR.color.social_text, 4))
        root.addView(line(
            "Keep it if you want to check back — nexlink.thvjq.com.au/report/?r=$code " +
                "shows the status and any reply.",
            14f, UiR.color.social_muted, 12))
        root.addView(spacer(16))
        root.addView(Button(this).apply {
            text = "Done"
            tag = Chrome.PRIMARY
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            setOnClickListener { finish() }
        })
    }

    /**
     * The installed version, asked of the package manager rather than
     * BuildConfig — which is not generated for this module (AGP 8 defaults
     * `buildFeatures.buildConfig` to false). Same reasoning as
     * [SettingsActivity.versionLabel]; this is also the string the operator
     * needs to reproduce anything.
     */
    @Suppress("DEPRECATION")
    private fun versionLabel(): String = runCatching {
        val pi = packageManager.getPackageInfo(packageName, 0)
        "${pi.versionName} (${pi.versionCode})"
    }.getOrDefault("unknown")

    companion object {
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        /** A symbolic screen name the caller came from. Never an identifier. */
        const val EXTRA_SCREEN = "screen"

        fun intent(c: Context, screen: String? = null): Intent =
            Intent(c, ReportProblemActivity::class.java).apply {
                if (screen != null) putExtra(EXTRA_SCREEN, screen)
            }
    }
}
