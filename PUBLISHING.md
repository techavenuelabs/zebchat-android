# Publishing `com.zebchat:chat`

The library is published to **Maven Central** through the **Central Portal**
(central.sonatype.com) under the `com.zebchat` namespace. Publish the Android SDK **before** the
Flutter and React Native wrappers that depend on it.

## One-time setup

1. **Namespace:** sign in to central.sonatype.com with the ZebChat account and verify the
   `com.zebchat` namespace (DNS TXT record on `zebchat.com`).
2. **User token:** Account → Generate User Token (a username/password pair for uploads).
3. **Signing key:** Maven Central requires PGP signatures.

   ```bash
   gpg --full-generate-key                          # RSA 4096, "ZebChat <dev@zebchat.com>"
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
   gpg --armor --export-secret-keys <KEY_ID> | base64 -w0 > signing-key.b64   # keep secret
   ```

   The build reads the key from `ZEBCHAT_SIGNING_KEY` (armored or base64 of armored) and
   `ZEBCHAT_SIGNING_PASSWORD`, or the Gradle properties `zebchatSigningKey` /
   `zebchatSigningPassword` in `~/.gradle/gradle.properties`. Without a key nothing is signed
   (fine for local use).

## Release

1. Set `VERSION_NAME` in `gradle.properties` (semantic versioning), update the README install
   snippet and the wrappers' dependency, and make sure CI (`sdk-android.yml`) is green.
2. Build the signed bundle:

   ```bash
   cd sdks/android
   export ZEBCHAT_SIGNING_KEY="$(cat signing-key.b64)" ZEBCHAT_SIGNING_PASSWORD=…
   ./gradlew clean :zebchat:centralBundle      # → zebchat/build/chat-<version>.zip
   ```

   The bundle holds the `.aar`, `-sources.jar`, `-javadoc.jar`, `.pom`, `.module`, their `.asc`
   signatures and checksums under `com/zebchat/chat/<version>/`. The task stages the release in
   `zebchat/build/central-bundle/` (emptied first, so no stale version slips in) and leaves out
   Gradle's `maven-metadata.xml` files, which the portal does not accept in a bundle.

3. Upload: central.sonatype.com → Publish → Upload a bundle → `chat-<version>.zip`, or with the API:

   ```bash
   curl -u "$TOKEN_USER:$TOKEN_PASS" -F bundle=@zebchat/build/chat-$VERSION.zip \
     "https://central.sonatype.com/api/v1/publisher/upload?publishingType=USER_MANAGED"
   ```

4. Wait for validation (POM metadata, signatures, javadoc/sources), then **Publish**. The artifact
   appears on Maven Central within about 30 minutes (search can take longer).
5. Tag the release `android-sdk-v<version>`.

## Local use (no publishing)

```bash
cd sdks/android
./gradlew publishToMavenLocal        # → ~/.m2/repository/com/zebchat/chat/<version>/
```

Consumers add `mavenLocal()` to their repositories. The Flutter and React Native wrappers and
their CI resolve the SDK this way until it is on Maven Central.

## Public mirror

The POM's SCM points at the public repo https://github.com/techavenuelabs/zebchat-android, a copy
of `sdks/android` (MIT, `LICENSE`). Refresh it on each release and tag it `v<version>`.
