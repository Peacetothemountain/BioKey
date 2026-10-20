package com.android.BioKey.core.data

import android.content.pm.ApplicationInfo

enum class AppCategory(val label: String) {
    FINANCE("Banking & Crypto"),
    AUTH("2FA & Passwords"),
    SOCIAL("Social & Chat"),
    WORK("Work & Productivity"),
    PRIVACY("Photos, Files & Health"),
    SYSTEM("System"),
    GENERAL("App")
}

/**
 * Guesses what kind of app something is from its package name, label and Play category. Used to
 * label apps in the list and by profile auto-sort, so both always agree.
 */
object AppClassifier {

    private val FINANCE = listOf(
        "bank", "crypto", "wallet", "pay", "cash", "venmo", "paypal", "zelle", "chime", "wise",
        "revolut", "robinhood", "coinbase", "binance", "kraken", "metamask", "trustwallet",
        "phantom", "ledger", "fidelity", "vanguard", "schwab", "etrade", "webull", "acorns",
        "stash", "ynab", "credit", "karma", "experian", "equifax", "transunion",
        "amex", "chase", "wellsfargo", "citi", "capitalone", "discover", "barclays", "hsbc",
        "santander", "ally", "sofi", "affirm", "klarna", "afterpay", "stripe", "square",
        "turbotax", "irs", "finance", "invest", "trading", "stock", "money"
    )
    private val AUTH = listOf(
        "authenticator", "authy", "2fas", "totp", "bitwarden", "1password", "dashlane",
        "lastpass", "keepass", "enpass", "nordpass", "protonpass", "roboform", "keeper",
        "yubico", "okta", "duo", "passwordmanager", "credentialmanager", "password"
    )
    private val SOCIAL = listOf(
        "whatsapp", "telegram", "signal", "messenger", "instagram", "facebook", "threads",
        "twitter", "snapchat", "tiktok", "musically", "discord", "reddit",
        "pinterest", "tumblr", "linkedin", "mastodon", "bluesky", "wechat", "line", "viber",
        "kakaotalk", "hinge", "tinder", "bumble", "grindr", "badoo", "okcupid", "match",
        "messages", "messaging", "chat"
    )
    private val WORK = listOf(
        "slack", "teams", "zoom", "webex", "meet", "gmail", "outlook", "protonmail",
        "fastmail", "spark", "superhuman", "thunderbird", "mail", "email", "calendar",
        "drive", "docs", "sheets", "slides", "onedrive", "dropbox", "notion", "obsidian",
        "evernote", "onenote", "trello", "asana", "jira", "confluence", "linear", "monday",
        "clickup", "basecamp", "github", "gitlab", "bitbucket", "salesforce", "hubspot",
        "zendesk", "workday", "adp", "gusto", "quickbooks", "xero", "freshbooks",
        "docusign", "acrobat", "camscanner", "office", "word", "excel", "powerpoint"
    )
    private val PRIVACY = listOf(
        "photo", "gallery", "camera", "album", "files", "filemanager", "documents",
        "downloads", "explorer", "solidexplorer", "mixplorer", "vault", "private", "secret",
        "locker", "notes", "keep", "diary", "journal", "dayone", "health", "garmin", "strava",
        "flo", "clue", "myfitnesspal", "medical", "patient", "mychart", "pharmacy",
        "walgreens", "cvs", "chrome", "firefox", "brave", "duckduckgo", "opera", "vivaldi",
        "edge", "tor", "vpn", "protonvpn", "nordvpn", "expressvpn", "mullvad", "tailscale", "wireguard"
    )

    /**
     * Keywords that count only as a whole word of the package name or label: short or common
     * ones that turn up inside unrelated names ("tor" in "calculator" and "store", "line" in
     * "lineageos", "duo" in "duolingo", "chrome" in "chromecast", "keeper" in "goalkeeper").
     * Every other keyword is a distinctive name that may sit inside a word ("wellsfargo" in
     * "wellsfargomobile", "photo" in "photos", "authenticator" in "authenticator2").
     */
    private val WHOLE_WORDS = setOf(
        "pay", "cash", "wise", "ynab", "amex", "chase", "citi", "hsbc", "ally", "sofi", "irs", "discover",
        "2fas", "totp", "okta", "duo", "keeper",
        "line", "chat", "match",
        "zoom", "meet", "mail", "drive", "docs", "sheets", "slides", "word", "adp", "xero",
        "files", "notes", "keep", "flo", "clue", "cvs", "chrome", "brave", "opera", "edge", "tor"
    )

    private val LABEL_SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

    fun categorize(packageName: String, label: String, playCategory: Int = ApplicationInfo.CATEGORY_UNDEFINED): AppCategory {
        val words = (packageName.lowercase().split('.', '_', '-') + label.lowercase().split(LABEL_SEPARATORS))
            .filterTo(HashSet()) { it.isNotEmpty() }
        fun matches(keys: List<String>) = keys.any { key -> if (key in WHOLE_WORDS) key in words else words.any { it.contains(key) } }
        return when {
            matches(FINANCE) -> AppCategory.FINANCE
            matches(AUTH) -> AppCategory.AUTH
            playCategory == ApplicationInfo.CATEGORY_SOCIAL || matches(SOCIAL) -> AppCategory.SOCIAL
            playCategory == ApplicationInfo.CATEGORY_PRODUCTIVITY || matches(WORK) -> AppCategory.WORK
            playCategory == ApplicationInfo.CATEGORY_IMAGE || matches(PRIVACY) -> AppCategory.PRIVACY
            packageName.startsWith("com.android.") || packageName.startsWith("com.google.android.") -> AppCategory.SYSTEM
            else -> AppCategory.GENERAL
        }
    }

    /** The built-in profile auto-sort puts this kind of app in, if any. */
    fun presetProfileFor(category: AppCategory): String? = when (category) {
        AppCategory.FINANCE -> SecurityProfile.PRESET_FINANCE
        AppCategory.SOCIAL -> SecurityProfile.PRESET_SOCIAL
        AppCategory.AUTH, AppCategory.WORK -> SecurityProfile.PRESET_WORK
        AppCategory.PRIVACY -> SecurityProfile.PRESET_PERSONAL
        AppCategory.SYSTEM, AppCategory.GENERAL -> null
    }
}
