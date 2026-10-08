import java.util.Base64
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
    signing
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

android {
    namespace = "com.zebchat.chat"
    compileSdk = 35

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"${project.version}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        targetSdk = 35
        warningsAsErrors = true
        abortOnError = true
        // Newer dependency versions need compileSdk 36, which would force it on every host app.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }

    testOptions {
        targetSdk = 35
        unitTests {
            isIncludeAndroidResources = true
            all { it.systemProperty("robolectric.logging.enabled", "false") }
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

kotlin {
    explicitApi()
    // Readable by host apps on Kotlin 1.9.20+ (React Native 0.74), with a matching stdlib floor.
    coreLibrariesVersion = "2.0.21"
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.webkit)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.androidx.test.core)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = project.group.toString()
            artifactId = "chat"
            version = project.version.toString()
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("ZebChat Android SDK")
                description.set("ZebChat live chat for Android apps: full-screen chat, visitor session, screen views and push.")
                url.set("https://www.zebchat.com/docs/mobile-sdk")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("zebchat")
                        name.set("ZebChat")
                        email.set("hello@zebchat.com")
                    }
                }
                scm {
                    url.set("https://github.com/techavenuelabs/zebchat-android")
                    connection.set("scm:git:https://github.com/techavenuelabs/zebchat-android.git")
                    developerConnection.set("scm:git:ssh://git@github.com/techavenuelabs/zebchat-android.git")
                }
            }
        }
    }
    repositories {
        // Staging directory zipped and uploaded to the Central Portal (PUBLISHING.md).
        maven {
            name = "centralBundle"
            url = uri(layout.buildDirectory.dir("central-bundle"))
        }
    }
}

// The Central Portal upload: the staged publication without Gradle's maven-metadata files (the
// portal wants only the artifacts, their signatures and checksums), and no stale versions.
val centralBundleDir = layout.buildDirectory.dir("central-bundle")
val cleanCentralBundle = tasks.register<Delete>("cleanCentralBundle") {
    delete(centralBundleDir)
}
tasks.matching { it.name == "publishReleasePublicationToCentralBundleRepository" }.configureEach {
    dependsOn(cleanCentralBundle)
}
tasks.register<Zip>("centralBundle") {
    group = "publishing"
    description = "Zips the signed release for an upload to the Central Portal (PUBLISHING.md)."
    dependsOn("publishReleasePublicationToCentralBundleRepository")
    from(centralBundleDir)
    exclude("**/maven-metadata*")
    archiveFileName.set("chat-${project.version}.zip")
    destinationDirectory.set(layout.buildDirectory)
}

// Signs only when a key is provided (CI / release machine); local publishToMavenLocal needs none.
val signingKey = providers.environmentVariable("ZEBCHAT_SIGNING_KEY")
    .orElse(providers.gradleProperty("zebchatSigningKey"))
val signingPassword = providers.environmentVariable("ZEBCHAT_SIGNING_PASSWORD")
    .orElse(providers.gradleProperty("zebchatSigningPassword"))
if (signingKey.isPresent) {
    signing {
        val key = signingKey.get().let { raw ->
            if (raw.contains("BEGIN PGP")) raw else String(Base64.getDecoder().decode(raw))
        }
        useInMemoryPgpKeys(key, signingPassword.orNull ?: "")
        sign(publishing.publications)
    }
}
