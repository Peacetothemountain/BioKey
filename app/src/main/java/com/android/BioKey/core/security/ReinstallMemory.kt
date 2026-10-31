package com.android.BioKey.core.security

import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import com.android.BioKey.core.data.PasswordManager
import com.android.BioKey.core.diagnostics.DiagnosticLog
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * A locked app uninstalled with its data and installed again is locked again. Uninstalling drops
 * an app from BioKey ([SecurityVault.onPackageUninstalled]): without this, uninstalling and
 * reinstalling a locked app would be a way round its lock (its data is gone, but its account
 * may sign straight back in).
 *
 * For each app whose lock switch is on, the SHA-256 of its signing certificates is noted while
 * it's installed (Android can't be asked once it's gone). When one is uninstalled for good, its
 * package name, those certificates, its profile and the time are remembered for [KEEP_MS]. When
 * it comes back signed with the same certificate (the same developer), it's locked again, in its
 * old profile, and that's logged; signed by someone else (or with no certificate noted), it's
 * never locked silently: it's offered, as a new app is ([NewAppSuggestions]), even with those
 * offers off. Unlike Android's own App lock, this survives a restart.
 *
 * Kept in BioKey's private storage, apart from the vault: not in a backup (it's about this phone's
 * apps), and forgotten when a backup is restored, which says afresh what's locked.
 */
object ReinstallMemory {

    /** How long an uninstalled app is remembered. */
    const val KEEP_MS = 30L * 24 * 60 * 60_000L
    /** At most this many remembered at once; the oldest go first. */
    private const val MAX_REMEMBERED = 200

    private const val PREFS = "biokey_reinstall"
    /** Package → its certificates' SHA-256s, for apps whose lock switch is on. */
    private const val KEY_SIGNERS = "signers"
    /** Uninstalled locked apps: package, certificates, profile, when. */
    private const val KEY_REMEMBERED = "remembered"

    /** What a reinstall came to. */
    enum class Outcome {
        /** Not a locked app uninstalled within [KEEP_MS] (or already locked again some other way). */
        NOTHING,
        /** Same certificate: locked again. */
        LOCKED_AGAIN,
        /** Another (or no known) certificate: to be offered, never locked silently. */
        OFFER
    }

    private class Remembered(val pkg: String, val signers: Set<String>, val profileId: String?, val at: Long)

    @VisibleForTesting
    internal var now: () -> Long = System::currentTimeMillis

    /** An installed app's certificates' SHA-256s (current and past, as Android vouches for them); null if it can't be read. */
    @VisibleForTesting
    internal var signersOf: (Context, String) -> Set<String>? = ::readSigners

    /**
     * Where certificates are read as apps are locked: off the caller's thread (the main one, from a
     * switch), and never on it: the vault tells its listeners as it loads, before it can be asked
     * for, and [note] asks for it.
     */
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "biokey-reinstall").apply { isDaemon = true } }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Registered with the vault (BioKeyApplication): apps locked, at load or newly, have their certificates noted. */
    object VaultListener : SecurityVault.Listener {
        override fun onLocked(context: Context, packages: Collection<String>) {
            val app = context.applicationContext
            val list = packages.toList()
            worker.execute { runCatching { note(app, list) } }
        }
    }

    /**
     * Notes the certificates of [packages] not noted yet, and forgets those of apps whose lock
     * switch is off now: the list stays as long as the locked apps.
     */
    @Synchronized
    fun note(context: Context, packages: Collection<String>) {
        val app = context.applicationContext
        val selected = SecurityVault.getInstance(app).selectedAppsFlow.value
        val signers = readSignerMap(app)
        var changed = signers.keys.retainAll(selected)
        for (pkg in packages) {
            if (pkg in signers || pkg !in selected || pkg in PasswordManager.ALIASES || pkg == app.packageName) continue
            val read = signersOf(app, pkg)?.takeIf { it.isNotEmpty() } ?: continue
            signers[pkg] = read
            changed = true
        }
        if (changed) writeSignerMap(app, signers)
    }

    /** [pkg] was updated: its certificate may have been rotated, so it's read again. */
    @Synchronized
    fun onReplaced(context: Context, pkg: String) {
        val app = context.applicationContext
        val signers = readSignerMap(app)
        if (pkg !in signers) return
        val read = signersOf(app, pkg)?.takeIf { it.isNotEmpty() } ?: return
        // Added to, not replaced: a reinstall of either the old or the rotated build is the same developer.
        signers[pkg] = signers.getValue(pkg) + read
        writeSignerMap(app, signers)
    }

    /**
     * [pkg] is gone from the phone with its data, and is about to be dropped from BioKey: if its
     * lock switch was on, it's remembered (with its profile, read now, before it's dropped).
     */
    @Synchronized
    fun onUninstalled(context: Context, pkg: String) {
        val app = context.applicationContext
        val vault = SecurityVault.getInstance(app)
        if (pkg in PasswordManager.ALIASES || !vault.isSetupCompleted()) return
        val signers = readSignerMap(app)
        val noted = signers.remove(pkg)
        if (noted != null) writeSignerMap(app, signers)
        if (pkg !in vault.selectedAppsFlow.value) return
        val kept = fresh(readRemembered(app)).filterNot { it.pkg == pkg }
        val entry = Remembered(pkg, noted.orEmpty(), vault.profileOf(pkg)?.id, now())
        writeRemembered(app, (kept + entry).takeLast(MAX_REMEMBERED))
        DiagnosticLog.log("Remembering $pkg for 30 days: a locked app uninstalled with its data")
    }

    /**
     * [pkg] was just installed (not updated). Remembered and signed by the same developer, it's
     * locked again in its old profile (or with no profile, if that's gone); otherwise, if it was
     * remembered, [Outcome.OFFER]. Runs before auto-sort and the new-app offer, which then leave
     * an app locked again alone.
     */
    @Synchronized
    fun onInstalled(context: Context, pkg: String): Outcome {
        val app = context.applicationContext
        val all = readRemembered(app)
        val kept = fresh(all)
        val entry = kept.lastOrNull { it.pkg == pkg }
        if (entry == null) {
            if (kept.size != all.size) writeRemembered(app, kept)
            return Outcome.NOTHING
        }
        // Remembered once only, whatever comes of it.
        writeRemembered(app, kept.filterNot { it.pkg == pkg })
        val vault = SecurityVault.getInstance(app)
        // Locked meanwhile (a backup restored, say): what's there stays.
        if (!vault.isSetupCompleted() || pkg in vault.selectedAppsFlow.value) return Outcome.NOTHING
        val current = signersOf(app, pkg).orEmpty()
        if (entry.signers.isEmpty() || current.none { it in entry.signers }) {
            DiagnosticLog.log("$pkg was locked before it was uninstalled; reinstalled with another signature, so it's offered, not locked")
            return Outcome.OFFER
        }
        val profile = entry.profileId?.takeIf { id -> vault.profilesFlow.value.any { it.id == id } }
        if (profile != null) vault.moveAppToProfile(pkg, profile) else vault.setAppLock(pkg, true)
        DiagnosticLog.log("Locked $pkg again: reinstalled with the same signature within 30 days of being uninstalled")
        return Outcome.LOCKED_AGAIN
    }

    /** A backup was restored: it says what's locked now, apps uninstalled before it included. */
    @Synchronized
    fun forget(context: Context) {
        prefs(context).edit { remove(KEY_REMEMBERED) }
    }

    /** The remembered packages, for tests. */
    @VisibleForTesting
    internal fun rememberedPackages(context: Context): List<String> = fresh(readRemembered(context)).map { it.pkg }

    @VisibleForTesting
    internal fun resetForTests(context: Context) {
        // A note an earlier test queued mustn't land in this one's storage.
        worker.submit {}.get()
        prefs(context).edit(commit = true) { clear() }
        now = System::currentTimeMillis
        signersOf = ::readSigners
    }

    /** Within [KEEP_MS]; one dated ahead (the clock set back since) is kept until it's old by the new clock too. */
    private fun fresh(list: List<Remembered>): List<Remembered> {
        val t = now()
        return list.filter { t - it.at < KEEP_MS }
    }

    private fun readSigners(context: Context, pkg: String): Set<String>? = runCatching {
        val info = context.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        val signing = info.signingInfo ?: return@runCatching null
        val certs = signing.apkContentsSigners.orEmpty().toList() + signing.signingCertificateHistory.orEmpty().toList()
        val sha = MessageDigest.getInstance("SHA-256")
        certs.mapTo(HashSet()) { cert -> sha.digest(cert.toByteArray()).joinToString("") { "%02x".format(it) } }
    }.getOrNull()

    private fun readSignerMap(context: Context): MutableMap<String, Set<String>> = runCatching {
        val json = JSONObject(prefs(context).getString(KEY_SIGNERS, null) ?: return@runCatching HashMap())
        json.keys().asSequence().associateWithTo(HashMap()) { key -> json.getJSONArray(key).strings() }
    }.getOrDefault(HashMap())

    private fun writeSignerMap(context: Context, map: Map<String, Set<String>>) {
        val json = JSONObject().apply { for ((pkg, certs) in map) put(pkg, JSONArray(certs.sorted())) }
        prefs(context).edit { putString(KEY_SIGNERS, json.toString()) }
    }

    private fun readRemembered(context: Context): List<Remembered> = runCatching {
        val array = JSONArray(prefs(context).getString(KEY_REMEMBERED, null) ?: return@runCatching emptyList())
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            Remembered(o.getString("p"), o.optJSONArray("c")?.strings().orEmpty(), o.optString("f").ifEmpty { null }, o.getLong("t"))
        }
    }.getOrDefault(emptyList())

    private fun writeRemembered(context: Context, list: List<Remembered>) {
        val array = JSONArray().apply {
            for (r in list) put(JSONObject().put("p", r.pkg).put("c", JSONArray(r.signers.sorted())).put("f", r.profileId ?: "").put("t", r.at))
        }
        prefs(context).edit { putString(KEY_REMEMBERED, array.toString()) }
    }

    private fun JSONArray.strings(): Set<String> = (0 until length()).mapTo(HashSet()) { getString(it) }
}
