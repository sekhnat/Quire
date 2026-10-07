package android.net

/**
 * A plain [Uri] for JVM tests, where the stubbed `Uri.parse` and `Uri.EMPTY` are null. It lives in `android.net` because
 * that is the only package that can reach Uri's constructor; it carries its text and nothing else.
 */
class TestUri(private val text: String) : Uri() {
  override fun buildUpon(): Builder = throw UnsupportedOperationException()
  override fun getAuthority(): String? = null
  override fun getEncodedAuthority(): String? = null
  override fun getEncodedFragment(): String? = null
  override fun getEncodedPath(): String? = null
  override fun getEncodedQuery(): String? = null
  override fun getEncodedSchemeSpecificPart(): String = text
  override fun getEncodedUserInfo(): String? = null
  override fun getFragment(): String? = null
  override fun getHost(): String? = null
  override fun getLastPathSegment(): String? = null
  override fun getPath(): String? = null
  override fun getPathSegments(): List<String> = emptyList()
  override fun getPort(): Int = -1
  override fun getQuery(): String? = null
  override fun getScheme(): String? = null
  override fun getSchemeSpecificPart(): String = text
  override fun getUserInfo(): String? = null
  override fun isHierarchical(): Boolean = false
  override fun isRelative(): Boolean = true
  override fun toString(): String = text
  override fun equals(other: Any?): Boolean = other is TestUri && other.text == text
  override fun hashCode(): Int = text.hashCode()
  override fun describeContents(): Int = 0
  override fun writeToParcel(dest: android.os.Parcel, flags: Int) = throw UnsupportedOperationException()
}
