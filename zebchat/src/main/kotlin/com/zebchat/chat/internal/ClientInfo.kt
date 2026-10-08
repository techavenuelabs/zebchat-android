package com.zebchat.chat.internal

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject

/** `client` on pageviews (`MobileClientInfo` in `packages/types/src/mobile.ts`). */
internal data class ClientInfo(
    val appId: String,
    val appVersion: String?,
    val sdk: String,
    val osVersion: String?,
    val deviceModel: String?,
    val deviceType: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("platform", PLATFORM)
        put("appId", appId.take(LIMIT_APP_ID))
        appVersion?.let { put("appVersion", it.take(LIMIT_SHORT)) }
        put("sdk", sdk.take(LIMIT_SHORT))
        osVersion?.let { put("osVersion", it.take(LIMIT_SHORT)) }
        deviceModel?.let { put("deviceModel", it.take(LIMIT_MODEL)) }
        put("deviceType", deviceType)
    }

    companion object {
        const val PLATFORM = "android"
        const val LIMIT_APP_ID = 155
        const val LIMIT_SHORT = 40
        const val LIMIT_MODEL = 80

        fun from(context: Context, sdk: String): ClientInfo {
            val pm = context.packageManager
            val versionName = try {
                if (Build.VERSION.SDK_INT >= 33) {
                    pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).versionName
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(context.packageName, 0).versionName
                }
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
            val manufacturer = Build.MANUFACTURER.orEmpty()
            val model = Build.MODEL.orEmpty()
            val device = when {
                model.startsWith(manufacturer, ignoreCase = true) -> model
                manufacturer.isEmpty() -> model
                else -> "$manufacturer $model"
            }.trim()
            val tablet = context.resources.configuration.smallestScreenWidthDp >= 600
            return ClientInfo(
                appId = context.packageName,
                appVersion = versionName?.takeIf { it.isNotBlank() },
                sdk = sdk,
                osVersion = Build.VERSION.RELEASE?.takeIf { it.isNotBlank() },
                deviceModel = device.ifEmpty { null },
                deviceType = if (tablet) "tablet" else "phone",
            )
        }
    }
}

/** `app://<appId>/<screen>`, exactly like `appScreenUrl` in `packages/types/src/mobile.ts`. */
internal object ScreenUrl {
    private const val HEX = "0123456789ABCDEF"

    fun of(appId: String, screen: String): String =
        "app://$appId/${encodeUriComponent(screen.trim()).replace("%2F", "/")}"

    /** JavaScript's `encodeURIComponent`. */
    fun encodeUriComponent(value: String): String {
        val out = StringBuilder()
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xff
            if (c < 0x80 && isUnreserved(c.toChar())) {
                out.append(c.toChar())
            } else {
                out.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0f])
            }
        }
        return out.toString()
    }

    private fun isUnreserved(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-_.!~*'()"
}
