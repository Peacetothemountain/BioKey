package com.android.BioKey.core.data

/**
 * Google Password Manager: one app to the owner, but reachable through two packages, the
 * standalone Credential Manager app and its screens inside Play services. BioKey gives the latter
 * a virtual package name, [VIRTUAL_PACKAGE], and locks, sessions and profiles treat the two as one
 * ([aliasesOf]).
 *
 * Both packages also host sign-in and passkey sheets that float over other apps, which must never
 * be locked: [resolve] tells them apart, for both detection engines.
 */
object PasswordManager {

    /** Google Password Manager's screens inside Play services. Not a real package. */
    const val VIRTUAL_PACKAGE = "com.google.android.gms.passwordmanager"
    const val CREDENTIAL_MANAGER = "com.google.android.apps.credentialmanager"
    const val GMS = "com.google.android.gms"

    val ALIASES: Set<String> = setOf(VIRTUAL_PACKAGE, CREDENTIAL_MANAGER)

    fun aliasesOf(pkg: String): Set<String> = if (pkg in ALIASES) ALIASES else setOf(pkg)

    fun expand(packages: Collection<String>): Set<String> = packages.flatMapTo(HashSet(), ::aliasesOf)

    /** Activities of Play services that are the password manager itself. */
    private val MANAGER_ACTIVITY_HINTS = listOf(
        "PasswordManagerProxyActivity", "PasswordManagerActivity", "AutofillManagePasswordsActivity",
        "GooglePasswordManager", "ManagePasswords"
    )

    /** Sign-in, passkey and account sheets that float over whichever app asked for them. */
    private val SHEET_HINTS = listOf(
        "Picker", "Chooser", "Selector", "Dialog", "Sheet", "SignIn", "Fido", "Passkey", "WebAuthn",
        "OneTap", "AccountPicker", "ChooseAccount", "Consent", "ConfirmUser", "Authenticator",
        "Authorization", "AuthAccount", "AuthActivity", "OAuth", "TwoStep", "SecondFactor",
        "CreatePassword", "ShareCredentials", "Autofill", "SmartLock", "FederatedIdentity", "TokenActivity"
    )

    private val MANAGER_TITLES = setOf("password manager", "google password manager", "saved passwords")

    /**
     * What a window of [pkg] with activity class [cls] (and window [title], when known) is:
     * - [VIRTUAL_PACKAGE] when it is Google Password Manager;
     * - null when it is a sign-in or passkey sheet over another app (an overlay, not an app switch);
     * - [pkg] otherwise, including Play services in its own right.
     *
     * Only the simple class name is matched: every class of Credential Manager has "credential" in
     * its package. Credential Manager windows that aren't a known sheet count as the password
     * manager, so an unknown screen stays covered (fail closed).
     */
    fun resolve(pkg: String, cls: String?, title: CharSequence? = null): String? {
        if (pkg != GMS && pkg != CREDENTIAL_MANAGER) return pkg
        val simple = cls.orEmpty().substringAfterLast('.')
        val isManagerActivity = MANAGER_ACTIVITY_HINTS.any { simple.contains(it, ignoreCase = true) }
        if (pkg == CREDENTIAL_MANAGER) {
            return if (!isManagerActivity && SHEET_HINTS.any { simple.contains(it, ignoreCase = true) }) null else VIRTUAL_PACKAGE
        }
        if (isManagerActivity) return VIRTUAL_PACKAGE
        if (SHEET_HINTS.any { simple.contains(it, ignoreCase = true) } || simple.contains("Settings", ignoreCase = true)) return GMS
        val heading = title?.toString()?.trim()?.lowercase()
        return if (heading != null && heading in MANAGER_TITLES) VIRTUAL_PACKAGE else GMS
    }
}
