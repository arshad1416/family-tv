plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Portal URL is baked in at build time: ./gradlew assembleRelease -PportalUrl=https://family-tv-portal.<you>.workers.dev
val portalUrl: String = (findProperty("portalUrl") as String?) ?: "http://10.0.2.2:8787"
// Debug builds (and so the unit tests) never take portalUrl, so a release run's tests can't register
// "robolectric" devices on the real portal; -PdebugPortalUrl points a debug install somewhere on purpose.
val debugPortalUrl: String = (findProperty("debugPortalUrl") as String?) ?: "http://10.0.2.2:8787"

android {
    namespace = "app.familytv"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.familytv"
        minSdk = 23 // Fire TV Stick 2nd gen (Fire OS 6) and every Google TV / Android TV box since 2016
        targetSdk = 35
        // CI passes -PversionCode/-PversionName for each published build.
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            buildConfigField("String", "PORTAL_URL", "\"$debugPortalUrl\"")
        }
        release {
            buildConfigField("String", "PORTAL_URL", "\"$portalUrl\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signing: set FAMILYTV_KEYSTORE etc. in CI; debug signing is used if absent so local builds work.
            val ks = System.getenv("FAMILYTV_KEYSTORE")
            signingConfig = if (ks != null) {
                signingConfigs.create("release") {
                    storeFile = file(ks)
                    storePassword = System.getenv("FAMILYTV_KEYSTORE_PASSWORD")
                    keyAlias = System.getenv("FAMILYTV_KEY_ALIAS")
                    keyPassword = System.getenv("FAMILYTV_KEY_PASSWORD")
                }
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        // Robolectric's Android jar comes through Gradle (and its proxy settings), not Robolectric's own downloader.
        unitTests.all { test ->
            test.systemProperty("robolectric.offline", "true")
            test.systemProperty("robolectric.dependency.dir", robolectricJars.singleFile.parent)
            // CI keeps no reports, so a failure's message and stack go to the log.
            test.testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
        }
    }
}

val robolectricJars: Configuration by configurations.creating
dependencies { robolectricJars("org.robolectric:android-all-instrumented:14-robolectric-10818077-i6") { isTransitive = false } }

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    // Installs the baseline profile (src/main/baseline-prof.txt) so the code is compiled ahead of
    // time, not left to the JIT after each sideloaded update.
    implementation("androidx.profileinstaller:profileinstaller:1.4.0")

    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.zxing:core:3.5.3")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // D-pad focus tests run the real Compose UI on the JVM.
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    // Player tests on an emulator or device: ./gradlew connectedDebugAndroidTest
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
