import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Your website's values: ./gradlew :sample:installDebug -PzebchatSiteKey=zc_… (or gradle.properties).
fun prop(name: String, default: String): String = providers.gradleProperty(name).getOrElse(default)

android {
    namespace = "com.zebchat.sample"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zebchat.sample"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "ZEBCHAT_SITE_KEY", "\"${prop("zebchatSiteKey", "zc_demo_site")}\"")
        // Local development: -PzebchatApiUrl=http://10.0.2.2:3000 -PzebchatWidgetUrl=http://10.0.2.2:5173/mobile.html
        buildConfigField("String", "ZEBCHAT_API_URL", "\"${prop("zebchatApiUrl", "https://api.zebchat.com")}\"")
        buildConfigField(
            "String",
            "ZEBCHAT_WIDGET_URL",
            "\"${prop("zebchatWidgetUrl", "https://cdn.zebchat.com/widget/v1/mobile.html")}\"",
        )
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Inside this repo; apps use implementation("com.zebchat:chat:1.0.0").
    implementation(project(":zebchat"))
}
