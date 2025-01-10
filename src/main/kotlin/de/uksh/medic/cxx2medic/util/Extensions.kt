package de.uksh.medic.cxx2medic.util

import org.apache.http.entity.ContentType

fun ContentType.isEqualTo(other: ContentType?): Boolean =
    when (other) {
        null -> false
        else -> this.mimeType.equals(other.mimeType) && this.charset.equals(other.charset)
    }

fun String.replaceAll(replacements: List<Pair<String, String>>): String
{
    var result = this
    for (pair in replacements) result = result.replace(pair.first, pair.second)
    return result
}

fun String.replaceAll(replacements: Map<String, String>): String
{
    var result = this
    for ((k, v) in replacements) result = result.replace(k, v)
    return result
}