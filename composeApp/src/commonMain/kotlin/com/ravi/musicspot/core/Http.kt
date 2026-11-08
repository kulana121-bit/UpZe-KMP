package com.ravi.musicspot.core

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

/**
 * Shared Ktor HTTP client across Android and iOS.
 * Every request is HTTPS, timeouts are tuned for mobile networks, and JSON serialization is unified.
 */
object Http {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }

    val client: HttpClient = HttpClient {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 6_000
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 20_000
        }
        followRedirects = true
    }

    const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"

    class HttpStatusException(val code: Int, url: String) : Exception("HTTP $code from ${url.substringBefore('?').take(80)}")

    suspend fun getString(url: String, headers: Map<String, String> = emptyMap()): String {
        val response = client.get(url) {
            headers.forEach { (k, v) -> header(k, v) }
        }
        if (!response.status.isSuccess()) {
            throw HttpStatusException(response.status.value, url)
        }
        return response.bodyAsText()
    }

    suspend fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): String {
        val response = client.post(url) {
            setBody(body)
            contentType(ContentType.Application.Json)
            headers.forEach { (k, v) -> header(k, v) }
        }
        if (!response.status.isSuccess()) {
            throw HttpStatusException(response.status.value, url)
        }
        return response.bodyAsText()
    }
}
