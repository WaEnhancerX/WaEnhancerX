package android.net

data class Uri(val value: String) {
    val lastPathSegment: String? get() = value.substringAfterLast('/')
}
