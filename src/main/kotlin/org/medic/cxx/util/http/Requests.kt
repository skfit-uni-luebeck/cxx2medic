package org.medic.cxx.util.http

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration

// TODO: Add varargs for configuring connection
fun POST(url: String, data: String, responseHandler: HttpResponse.BodyHandler<*> = BodyHandlers.discarding()): HttpResponse<out Any>
{
    val request = HttpRequest.newBuilder(URI(url)).POST(BodyPublishers.ofString(data)).apply {
            timeout(Duration.ofMillis(10000))
            header("Charset", "utf-8")
            header("Content-Type", "application/fhir+json")
    }.build()

    val client = HttpClient.newHttpClient()

    return client.send(request, responseHandler)
}