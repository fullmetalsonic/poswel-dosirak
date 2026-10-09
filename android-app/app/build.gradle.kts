plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }
val appVersion = "0.1.10"
val releaseStore = providers.environmentVariable("POSWEL_RELEASE_KEYSTORE").orNull
val releaseStorePassword = providers.environmentVariable("POSWEL_RELEASE_STORE_PASSWORD").orNull
val releaseAlias = providers.environmentVariable("POSWEL_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("POSWEL_RELEASE_KEY_PASSWORD").orNull
val releaseSigningInputs = listOf(releaseStore, releaseStorePassword, releaseAlias, releaseKeyPassword)
val releaseSigningReady = releaseSigningInputs.all { !it.isNullOrBlank() }
require(releaseSigningInputs.all { it.isNullOrBlank() } || releaseSigningReady) {
    "Set all four POSWEL_RELEASE signing environment variables, or leave all unset."
}
android {
    namespace = "com.fullmetalsonic.dosirak"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.fullmetalsonic.dosirak"
        minSdk = 26
        targetSdk = 37
        versionCode = 11
        versionName = appVersion
        testInstrumentationRunner = if (providers.gradleProperty("isolatedProbes").orNull == "true")
            "com.fullmetalsonic.dosirak.platform.IsolatedWarmupProbeRunner"
        else "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    signingConfigs {
        if (releaseSigningReady) create("publicRelease") {
            storeFile = file(releaseStore!!)
            storePassword = releaseStorePassword
            keyAlias = releaseAlias
            keyPassword = releaseKeyPassword
        }
    }
    buildTypes {
        debug { versionNameSuffix = "-test" }
        release {
            isMinifyEnabled = false
            isDebuggable = false
            if (releaseSigningReady) signingConfig = signingConfigs.getByName("publicRelease")
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}
tasks.register("verifyPublicSigning") {
    doLast {
        check(releaseSigningReady) { "Public release requires the POSWEL_RELEASE signing environment variables." }
        check(file(releaseStore!!).isFile) { "Public release keystore does not exist." }
    }
}
tasks.matching { it.name == "packageRelease" }.configureEach {
    dependsOn("verifyPublicSigning")
}
dependencies {
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui:1.7.6")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.6")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-extended:1.7.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.google.code.gson:gson:2.11.0")
    debugImplementation("androidx.compose.ui:ui-tooling:1.7.6")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.7.6")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.7.6")
}
