package android.net

/**
 * Test-only [Uri] implementation.
 *
 * `Uri` is abstract with a package-private constructor, so a subclass can only be created from
 * inside `android.net`. That makes it the only way to build tens of thousands of distinct URIs on
 * the JVM: a relaxed MockK `Uri` spends about 20 microseconds per `toString()` call and records
 * every invocation, which would both dominate and destabilise any benchmark that reads a URI per
 * track. Library grouping only ever reads [toString], so nothing else is supported.
 *
 * This lives in the unit-test source set only and is never part of the app.
 */
open class TestUri(private val raw: String) : Uri() {

    override fun toString(): String = raw

    override fun hashCode(): Int = raw.hashCode()

    override fun equals(other: Any?): Boolean = other is Uri && other.toString() == raw

    override fun compareTo(other: Uri): Int = raw.compareTo(other.toString())

    override fun getScheme(): String = raw.substringBefore(':')

    override fun isHierarchical(): Boolean = false

    override fun isRelative(): Boolean = false

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: android.os.Parcel, flags: Int): Unit = unsupported()

    override fun buildUpon(): Builder = unsupported()

    override fun getSchemeSpecificPart(): String = raw.substringAfter(':')

    override fun getEncodedSchemeSpecificPart(): String = getSchemeSpecificPart()

    override fun getAuthority(): String = raw.removePrefix("content://").substringBefore('/')

    override fun getEncodedAuthority(): String = getAuthority()

    override fun getUserInfo(): String = ""

    override fun getEncodedUserInfo(): String = getUserInfo()

    override fun getHost(): String = getAuthority().substringBefore(':')

    override fun getPort(): Int {
        val candidate = getAuthority().substringAfter(':', "")
        return candidate.toIntOrNull() ?: -1
    }

    override fun getPath(): String = "/" + raw.substringAfterLast('/', missingDelimiterValue = "")

    override fun getEncodedPath(): String = getPath()

    override fun getQuery(): String {
        val question = raw.indexOf('?')
        return if (question < 0) "" else raw.substring(question + 1)
    }

    override fun getEncodedQuery(): String = getQuery()

    override fun getFragment(): String {
        val hash = raw.indexOf('#')
        return if (hash < 0) "" else raw.substring(hash + 1)
    }

    override fun getEncodedFragment(): String = getFragment()

    override fun getLastPathSegment(): String = raw.substringAfterLast('/', missingDelimiterValue = "")

    override fun getPathSegments(): List<String> =
        raw.substringAfterLast('/', missingDelimiterValue = "").split('/').filter { it.isNotEmpty() }

    override fun getQueryParameter(key: String): String =
        queryPairs().firstOrNull { it.startsWith("$key=") }?.removePrefix("$key=") ?: ""

    override fun getBooleanQueryParameter(key: String, allowRecursive: Boolean): Boolean =
        getQueryParameter(key).toBooleanStrictOrNull() ?: false

    override fun getQueryParameters(key: String): List<String> = listOf(getQueryParameter(key))

    override fun getQueryParameterNames(): Set<String> =
        queryPairs().map { it.substringBefore('=') }.filter { it.isNotEmpty() }.toSet()

    private fun queryPairs(): List<String> =
        getQuery().split('&').filter { it.isNotEmpty() }

    /** Test-only helper; the full URI surface is not needed by any test that builds one. */
    private fun <T> unsupported(): T =
        throw UnsupportedOperationException("TestUri('$raw') does not support this operation")
}
