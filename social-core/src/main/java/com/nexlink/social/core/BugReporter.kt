package com.nexlink.social.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Filing a bug report — §38.
 *
 * **The field list here IS §38.3's allowlist**, and that is the whole point of
 * this class existing rather than the screen posting a map. §2.8 #7 forbids
 * room IDs, user IDs, event IDs and key material in anything resembling a
 * crash report; [Report] has nowhere to put one, so no future edit to the
 * screen can smuggle one in without changing this file and tripping review.
 *
 * The service rejects unknown keys with a 400 rather than dropping them, so the
 * two ends cannot silently drift either — a field added here and not there
 * fails loudly on the first report.
 *
 * It talks to the service through the public hostname's `/bugs/api/` path
 * (§38.6.1), not a port of its own: public routes live in the Cloudflare
 * dashboard and adding one needs the account owner, so everything is fanned out
 * from one hostname. §26.2.3's reasoning, reused for the third time.
 */
class BugReporter(baseUrl: String) {

    /**
     * Everything a report may carry. Nothing here identifies a conversation.
     *
     * [mxid] is null unless the reporter ticked an unticked box (§38.3.1), and
     * it is the only field that can identify anyone.
     */
    data class Report(
        val kind: Kind,
        val whatHappened: String,
        val expected: String? = null,
        val appVersion: String? = null,
        val androidVersion: String? = null,
        val deviceModel: String? = null,
        /** A stable symbolic screen name — `"call"`, `"settings"`. Never an ID. */
        val screen: String? = null,
        val mxid: String? = null,
    )

    enum class Kind(val wire: String) { BUG("bug"), FEATURE("feature") }

    private val base = baseUrl.trimEnd('/')

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    /**
     * File it. Returns the reference code (§38.5) on success.
     *
     * **A failure must reach the user as a failure.** §38.10 #1: the sibling
     * product showed a green tick over a 404 for months, so every non-2xx here
     * returns the server's own sentence where it sent one — the rate-limit
     * message in particular is advice, not an error code, and paraphrasing it
     * into "something went wrong" would waste it.
     */
    fun submit(report: Report): Result<String> = runCatching {
        val body = JSONObject().apply {
            put("source", "app")
            put("kind", report.kind.wire)
            put("what_happened", report.whatHappened)
            report.expected?.let { put("expected", it) }
            report.appVersion?.let { put("app_version", it) }
            report.androidVersion?.let { put("android_version", it) }
            report.deviceModel?.let { put("device_model", it) }
            report.screen?.let { put("screen", it) }
            report.mxid?.let { put("mxid", it) }
        }

        val req = Request.Builder()
            .url("$base/bugs/api/report")
            .post(body.toString().toRequestBody(json))
            .build()

        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                throw BugReportError(serverMessage(text) ?: defaultFor(r.code))
            }
            JSONObject(text).optString("id").ifEmpty {
                // 2xx with no reference is a service bug, not a user error, but
                // the user still must not be told it worked.
                throw BugReportError("The report was sent but no reference came back.")
            }
        }
    }

    private fun serverMessage(text: String): String? = runCatching {
        JSONObject(text).optString("error").takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun defaultFor(code: Int): String = when (code) {
        in 500..599 -> "The server could not save it. Nothing was recorded — please try again."
        else -> "That did not save (error $code). Nothing was recorded."
    }

    class BugReportError(message: String) : Exception(message)
}
