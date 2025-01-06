package de.uksh.medic.cxx2medic.util

import org.apache.http.entity.ContentType

fun ContentType.isEqualTo(other: ContentType?): Boolean =
    when (other) {
        null -> false
        else -> this.mimeType.equals(other.mimeType) && this.charset.equals(other.charset)
    }