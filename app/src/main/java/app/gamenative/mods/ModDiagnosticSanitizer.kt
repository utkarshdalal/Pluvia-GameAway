package app.gamenative.mods

object ModDiagnosticSanitizer {
    private val urlUserInfo = Regex("(?i)(https?://)[^\\s/@]+@")
    private val urlQuery = Regex("(?i)(https?://[^\\s?#]+)\\?[^\\s#]+")
    private val signedQuery = Regex("(?i)([?&](?:token|key|auth|authorization|signature|sig|expires)\\s*=)[^&#\\s]+")
    private val secretAssignment = Regex(
        "(?i)\\b((?:api[-_ ]?key|token|authorization|auth|signature|secret)\\s*[:=]\\s*(?:bearer\\s+)?)[^&#\\s,;]+",
    )
    private val windowsPath = Regex("(?i)(?:[A-Z]:[\\\\/])(?:[^\\s\\r\\n]+)")
    private val androidPath = Regex("(?<![A-Za-z0-9])/(?:data|storage|sdcard|mnt)/[^\\s\\r\\n]+")

    fun text(value: String): String = value
        .replace(urlUserInfo) { match -> "${match.groupValues[1]}<redacted>@" }
        .replace(urlQuery) { match -> "${match.groupValues[1]}?<redacted>" }
        .replace(signedQuery) { match -> "${match.groupValues[1]}<redacted>" }
        .replace(secretAssignment) { match -> "${match.groupValues[1]}<redacted>" }
        .replace(windowsPath, "<path>")
        .replace(androidPath, "<path>")
        .replace('\n', ' ')
        .replace('\r', ' ')

    fun relativePath(value: String): String =
        text(value.substringBefore('?')).trim().replace('\\', '/').trimStart('/')
}
