# ZebChat Android SDK

Live chat with your support team inside your Android app. `com.zebchat:chat` opens the full
ZebChat chat (the same features as the website widget) in a full-screen screen, keeps your user's
visitor across app restarts, sends screen views your agents see live, and shows agent replies as
push notifications through **your own Firebase project**.

- minSdk 23, compileSdk 35+, Kotlin 1.9.20+ or Java 17
- Dependencies: `androidx.core`, `androidx.activity`, `androidx.webkit`. No Firebase dependency.
- Docs: https://www.zebchat.com/docs/mobile-sdk/

## Install

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.zebchat:chat:1.0.0")
}
```

Then, in the ZebChat dashboard → **Websites** → your website → **Mobile apps**, add your app's
package name (`applicationId`) to the allowed Android apps. An app that is not listed gets
`403 app_not_allowed`: the chat then says "This chat is not available in this app right now"
(any permanent 4xx from `/widget/sessions`, such as an unknown site key) instead of the
"Check your connection" screen it shows for network and server errors, and logcat (tag
`ZebChat`) names the status and your app id.

## Quick start

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ZebChat.configure(this, siteKey = "zc_…", options = ZebChatOptions(locale = "auto"))
    }
}

// Anywhere, e.g. a "Help" button:
ZebChat.show(activity)
```

Call `configure` in `Application.onCreate`: after process death Android may recreate the chat
screen before any of your activities, and the SDK restores the last configuration then. Every
other call made before `configure` logs a warning and does nothing.

### Your signed-in user

```kotlin
ZebChat.setUser(ZebChatUser(id = "42", email = "ana@example.com", name = "Ana", hash = hashFromYourServer))
ZebChat.logout()   // on sign-out: removes this device's push registration, then forgets the visitor
```

`hash` is hex HMAC-SHA256 of `id` keyed with the website's identity secret (dashboard → website →
Identity verification). Compute it **on your server** and return it with your user's profile;
never put the secret in the app. Without `id`/`hash`, name, email and phone are saved unverified.
A verified user is the same visitor on every device (their conversations follow them).

### Screens, unread count, language

```kotlin
ZebChat.trackScreen("Checkout")            // agents see app://<your app id>/Checkout (buffered offline, max 20)
ZebChat.track("added_to_cart", mapOf("sku" to "A1")) // custom event, like ZebChat.track() on the web

val listener = ZebChat.addUnreadListener { count -> badge.text = count.toString() } // main thread, called at once
ZebChat.removeUnreadListener(listener)

ZebChat.setLocale("fr")                    // applies on the next show(); "auto" = device language
```

`track(name, properties)` records a custom event agents see in the visitor's journey: `name` is
1–64 of `A-Z a-z 0-9 _ . : -`, `properties` (optional) at most 2 KB as JSON. Invalid events are
logged and ignored. Events are buffered offline like screens (max 20) and sent after them.

The offline screen and event buffers live in memory only: it is lost when the process dies, and a
`configure` call with different values starts a new, empty buffer. `setLocale` is remembered
across process death until you call `configure` with a different `locale` option.
The unread count is refreshed when the app comes to the foreground, when a ZebChat push arrives,
and after the chat closes.

### Java

```java
ZebChat.configure(context, "zc_…", new ZebChatOptions());
ZebChat.show(activity);
ZebChat.addUnreadListener(count -> badge.setText(String.valueOf(count)));
ZebChat.handleNotificationIntent(this, getIntent());   // launcher Activity onCreate / onNewIntent
```

`handleNotificationTap` and `isZebChatNotification` have `Map` and `Bundle` overloads, so a
literal `null` is ambiguous in Java (and Kotlin); pass a typed value, or use
`handleNotificationIntent(activity, intent)` / `isZebChatIntent(intent)`.

## Push notifications (Firebase)

ZebChat sends a push when an agent replies while the visitor is not in the chat (app in the
background or closed). It uses **your** Firebase project:

1. Dashboard → website → **Mobile apps** → upload a Firebase service account JSON for the project
   your app uses (Firebase console → Project settings → Service accounts → Generate new private key).
   Use **Send test push** with a device token to check it.
2. Add Firebase Cloud Messaging to your app as usual (`google-services.json`,
   `com.google.firebase:firebase-messaging`).
3. Forward tokens and messages to ZebChat from **your** `FirebaseMessagingService` (an app can only
   have one, so the SDK never declares its own):

```kotlin
class MyMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        ZebChat.setPushToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (ZebChat.isZebChatNotification(message.data)) {
            // App in the foreground: FCM does not display it, so the SDK does (channel zebchat_chat).
            ZebChat.showNotification(this, message.data)
            return
        }
        // … your own messages
    }
}
```

```kotlin
// At start (e.g. Application.onCreate after configure), as onNewToken only fires on changes:
FirebaseMessaging.getInstance().token.addOnSuccessListener { ZebChat.setPushToken(it) }

// In your launcher Activity: a tap on a notification FCM displayed in the background.
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    ZebChat.handleNotificationTap(this, intent.extras)
}
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    ZebChat.handleNotificationTap(this, intent.extras)
}
```

Pushes carry only `{zebchat: "1", conversationId, siteKey, title, body}` (no visitor data), use the
Android channel `zebchat_chat` (created by `configure`; rename it by overriding the
`zebchat_channel_name` string) and collapse per conversation. Turn message previews off with
"Show message text in notifications" in the dashboard. Set a monochrome icon with
`ZebChatOptions(notificationIcon = R.drawable.ic_notification)`.

**How the SDK knows the app is in the background:** the chat page disconnects its live connection
when its WebView is hidden (`visibilitychange`), so ZebChat treats the visitor as offline and sends
pushes. The chat screen makes the WebView invisible in `onStop` and pauses it in `onPause`; when
the chat is closed there is no page at all. A visitor who also has your website open in a browser
tab counts as online and gets no push while that tab is open.

## Permissions

The SDK declares only `INTERNET`. Everything else is your app's decision:

| Permission                         | When                                     |                                                                                                                 |
| ---------------------------------- | ---------------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| `POST_NOTIFICATIONS` (Android 13+) | to show chat notifications               | declare and request it yourself; without it `showNotification` returns false                                    |
| `CAMERA`                           | "take a photo" in the chat's file picker | offered only when your app declares **and** holds it; otherwise visitors pick files and photos from the gallery |

File downloads use `DownloadManager` (no permission on Android 10+; on Android 6–9 without
`WRITE_EXTERNAL_STORAGE` the file opens in the browser instead).

## Backups

The visitor key and session live in private SharedPreferences (`com.zebchat.chat.xml`), wrapped
with an AES-GCM key from the Android Keystore **when the Keystore works**. Wrapping is best
effort: on a device whose Keystore fails the values are stored unwrapped (still app-private), so
excluding the file from backups is what really keeps the visitor key off backups and other
devices. Keystore keys never leave the device, so a wrapped copy restored onto another install
cannot be read and is discarded (that install starts as a new visitor); a Keystore error that may
pass keeps the stored value and tries again later. Exclude the file from backups:

- **No backup rules of your own:** use the SDK's files

  ```xml
  <application
      android:dataExtractionRules="@xml/zebchat_data_extraction_rules"
      android:fullBackupContent="@xml/zebchat_backup_rules" … >
  ```

- **Your own rules:** add `<exclude domain="sharedpref" path="com.zebchat.chat.xml" />` to
  `<full-backup-content>` and to both `<cloud-backup>` and `<device-transfer>` of your
  data-extraction rules.

(A library cannot set these attributes itself without clashing with yours in the manifest merger.)

## Data safety (Google Play)

What the SDK sends to ZebChat, for your Data safety form:

- **Personal info** (name, email, phone, user id) — only what you pass to `setUser`; linked to the
  user; app functionality (customer support).
- **Messages** (chat messages, photos and files the user sends) — app functionality.
- **App activity** (screen names from `trackScreen`) — analytics shown to your agents.
- **Device or other IDs** (a random visitor key, the FCM token) — app functionality.
- **App info and performance**: app id and version, SDK version, OS version, device model and type.

All traffic is HTTPS; nothing is shared with third parties beyond your own Firebase project for
pushes; no advertising ID is read.

## Behaviour notes

- The chat page comes from `https://cdn.zebchat.com/widget/v1/mobile.html`; only that origin loads
  in the WebView. Other links open in the browser, chat files download, `target=_blank` links open
  outside. The page talks to the SDK through a `WebMessageListener` limited to the CDN origin's
  main frame (a JavaScript interface on old WebViews, where frames are then limited to the CDN
  and Cloudflare Turnstile). The visitor key never enters the WebView or a URL.
- One chat screen at a time: it is `singleTop`, and `show()` or a notification tap while it is
  open brings that screen back instead of stacking a second one. Chat notifications are not shown
  while it is open, even behind a permission prompt or a share sheet.
- `configure` does no disk or Keystore work on the calling thread; it is safe on the main thread.
- Switching to another site key removes this device's push registration from the previous
  site's visitor, then starts a new visitor. A rotated FCM token replaces the old one.
- Edge to edge: the chat applies system bar, display cutout and keyboard insets (`adjustResize`).
  Back closes the chat.
- Sessions are cached until shortly before they expire (about 12 h), so cold starts rarely create one.
- R8/ProGuard: rules ship with the library; nothing to add.

## Local development and staging

```kotlin
ZebChatOptions(
    apiUrl = "http://10.0.2.2:3000",                       // API on your machine (emulator)
    widgetUrl = "http://10.0.2.2:5173/mobile.html",        // widget dev server
    debugLogging = true,
)
```

Cleartext HTTP needs a network security config in the app (see `sample/`).

## This repository

```bash
cd sdks/android
./gradlew :zebchat:check :sample:assembleDebug publishToMavenLocal   # lint, tests, sample, ~/.m2
```

Needs JDK 17+ (`JAVA_HOME`) and the Android SDK (`ANDROID_HOME`, or `sdk.dir` in an untracked
`local.properties`). If `./gradlew` is not executable after a checkout, run `bash ./gradlew …` or
`git update-index --chmod=+x sdks/android/gradlew`. `publishToMavenLocal` is how the Flutter and
React Native wrappers resolve `com.zebchat:chat` before it is on Maven Central (`mavenLocal()`).

The sample app (`sample/`) configures ZebChat, opens the chat, sets a user with a hash field,
tracks screens and shows the unread count. It has no Firebase so it builds without
`google-services.json`; wire push as above. Pass your site key with
`./gradlew :sample:installDebug -PzebchatSiteKey=zc_…` and add `com.zebchat.sample` to the
website's allowed Android apps.

Publishing: [PUBLISHING.md](PUBLISHING.md).
