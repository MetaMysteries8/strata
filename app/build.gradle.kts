import groovy.json.JsonSlurper
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val releaseVersion=JsonSlurper().parse(rootProject.file("release.json")) as Map<*,*>
android {
    namespace = "dev.doxi.strata"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.doxi.strata.preview"
        minSdk = 31
        targetSdk = 35
        versionCode = (releaseVersion["versionCode"] as Number).toInt()
        versionName = releaseVersion["versionName"] as String
        ndk { abiFilters += "arm64-v8a" }
    }
    // Public AOSP test material: stable development identity for the side-by-side preview.
    signingConfigs.getByName("debug") {
        storeFile = rootProject.file("development-signing/preview-test.p12")
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
        storeType = "PKCS12"
    }
    buildTypes { getByName("release") { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true } }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    implementation(files("libs/brush.aar"))
    implementation("com.google.ar:core:1.48.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
