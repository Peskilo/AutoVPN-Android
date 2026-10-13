import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val vpnCoreVersion = libs.versions.vpnCore.get()
val requestedAbi = providers.gradleProperty("autovpnAbi").orNull

android {
    namespace = "dev.typenil.vpnclient"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.typenil.vpnclient"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "VPN_CORE_VERSION", "\"$vpnCoreVersion\"")

        // Hilt tests need an Application annotated @HiltAndroidApp in the
        // test APK — VpnTestRunner instantiates VpnTestApp instead of the
        // production VpnClientApp so @TestInstallIn fakes can plug in.
        testInstrumentationRunner = "dev.typenil.vpnclient.VpnTestRunner"
        if (requestedAbi != null) {
            require(requestedAbi in setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")) {
                "Unsupported AutoVPN ABI: $requestedAbi"
            }
            ndk {
                abiFilters += requestedAbi
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    // CI can build one device-specific APK at a time with -PautovpnAbi=...
    // This avoids accidentally shipping all four large native libbox binaries.
    bundle {
        language {
            enableSplit = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        // Let JVM unit tests construct lightweight android.jar stubs
        // (e.g. an empty Intent) without Robolectric.
        unitTests.isReturnDefaultValues = true
    }
    sourceSets {
        // MigrationTestHelper reads exported schema JSONs from test assets.
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

ksp {
    // Exported schema JSON is committed under app/schemas/ — migration history.
    arg("room.schemaLocation", "$projectDir/schemas")
}

// libbox AAR is fetched from the singbox-android/libbox GitHub release and
// verified against the pinned SHA-256 (see docs/adr/ADR-0001-vpn-core.md).
val libboxSha256 = "93b2596c4e90df32463a9ade5c89d85f83a16aacd94928ba26fc4bce41eaf2a9"
val libboxFile = rootProject.file("core-native/libbox-$vpnCoreVersion.aar")

val downloadLibbox by tasks.registering {
    // Locals — doLast closures must not capture the build script (config cache).
    val version = vpnCoreVersion
    val sha256 = libboxSha256
    val aar = libboxFile
    group = "vpn core"
    outputs.file(aar)
    onlyIf { !aar.exists() }
    doLast {
        val url = "https://github.com/singbox-android/libbox/releases/download/$version/libbox.aar"
        aar.parentFile.mkdirs()
        val tmp = File.createTempFile("libbox", ".aar", aar.parentFile)
        try {
            URI(url).toURL().openStream().use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            tmp.inputStream().use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == sha256) {
                "libbox.aar checksum mismatch: expected $sha256, got $actual"
            }
            check(tmp.renameTo(aar)) { "failed to move ${tmp.name} into place" }
        } finally {
            tmp.delete()
        }
    }
}

// The download-time checksum only guards fresh fetches — a corrupted cached
// AAR would ship silently. Re-verify on every build; on mismatch delete the
// file so the next build re-downloads (self-healing).
val verifyLibbox by tasks.registering {
    val sha256 = libboxSha256
    val aar = libboxFile
    group = "vpn core"
    dependsOn(downloadLibbox)
    inputs.file(aar)
    doLast {
        val digest = MessageDigest.getInstance("SHA-256")
        aar.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != sha256) {
            aar.delete()
            throw GradleException(
                "libbox.aar checksum mismatch: expected $sha256, got $actual — deleted, re-run to re-download",
            )
        }
    }
}

tasks.named("preBuild") { dependsOn(verifyLibbox) }

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.hilt.android)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.okhttp)
    implementation(libs.snakeyaml)
    implementation(files(libboxFile))

    ksp(libs.androidx.room.compiler)
    ksp(libs.hilt.android.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.turbine)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    // @HiltAndroidTest + @BindValue for the VPN lifecycle harness — the
    // generated test component swaps VpnEngineFactory/TunProvider for fakes.
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.android.compiler)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
