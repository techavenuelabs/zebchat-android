package com.zebchat.chat.internal

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject

/** Shared fixtures for the HTTP tests. */
internal object Fixtures {
    const val SITE_KEY = "zc_test_site"
    const val APP_ID = "com.example.shop"

    /** 2026-10-08T12:00:00Z */
    const val NOW = 1_791_460_800_000L
    /** Far ahead: the Robolectric tests run on the real clock. */
    const val EXPIRES = "2099-10-09T00:00:00.000Z"

    val client = ClientInfo(
        appId = APP_ID,
        appVersion = "2.1.0",
        sdk = "android/1.0.0",
        osVersion = "15",
        deviceModel = "Google Pixel 9",
        deviceType = "phone",
    )

    fun session(
        token: String,
        visitorId: String,
        visitorKey: String? = null,
        expiresAt: String = EXPIRES,
        verified: Boolean = false,
    ): MockResponse {
        val body = JSONObject()
            .put("visitorToken", token)
            .put("visitorTokenExpiresAt", expiresAt)
            .put(
                "visitor",
                JSONObject().put("id", visitorId).put("name", "Visitor").put("email", JSONObject.NULL)
                    .put("phone", JSONObject.NULL).put("verified", verified),
            )
            .put("availability", JSONObject().put("online", true))
        visitorKey?.let { body.put("visitorKey", it) }
        return MockResponse().setResponseCode(if (verified) 200 else 201).setBody(body.toString())
    }

    fun noContent(): MockResponse = MockResponse().setResponseCode(204)

    fun error(status: Int, message: String = "nope"): MockResponse =
        MockResponse().setResponseCode(status).setBody(JSONObject().put("statusCode", status).put("message", message).toString())

    fun json(request: RecordedRequest): JSONObject = JSONObject(request.body.readUtf8())

    fun MockWebServer.apiUrl(): String = url("/").toString().trimEnd('/')
}
