import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.dagger.hilt)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.jetbrains.serialization)
    alias(libs.plugins.kotlinter)
    alias(libs.plugins.ksp)
    alias(libs.plugins.secrets.gradle)
    alias(libs.plugins.room)
}

val keystorePropertiesFile = rootProject.file("app/keystores/keystore.properties")
val keystoreProperties: Properties? = if (keystorePropertiesFile.exists()) {
    Properties().apply {
        load(FileInputStream(keystorePropertiesFile))
    }
} else null

// Add PostHog API key and host as build-time variables
val posthogApiKey: String = project.findProperty("POSTHOG_API_KEY") as String? ?: System.getenv("POSTHOG_API_KEY") ?: ""
val posthogHost: String = project.findProperty("POSTHOG_HOST") as String? ?: System.getenv("POSTHOG_HOST") ?: "https://us.i.posthog.com"

val metaAppId: String = project.findProperty("META_APP_ID") as String? ?: System.getenv("META_APP_ID") ?: ""
val productSku: String = project.findProperty("PRODUCT_SKU") as String? ?: System.getenv("PRODUCT_SKU") ?: ""

room {
    schemaDirectory("$projectDir/schemas")
}

// Debug-only: package the repo's manifest.json so debug builds read it locally (never in release).
val copyDebugManifest by tasks.registering(Copy::class) {
    from(rootProject.file("manifest.json"))
    into(layout.buildDirectory.dir("generated/debugManifest"))
}

android {
    namespace = "app.gamenative"
    compileSdk = 36

    // https://developer.android.com/ndk/downloads
    ndkVersion = "27.3.13750724"

    signingConfigs {
        create("pluvia") {
            if (keystoreProperties != null) {
                storeFile = file(keystoreProperties["storeFile"].toString())
                storePassword = keystoreProperties["storePassword"].toString()
                keyAlias = keystoreProperties["keyAlias"].toString()
                keyPassword = keystoreProperties["keyPassword"].toString()
            }
        }
    }

    defaultConfig {
        applicationId = "app.gamenative"

        minSdk = 26

        manifestPlaceholders["screenOrientation"] = "unspecified"
        buildConfigField("boolean", "XR_BUILD", "false")
        buildConfigField("boolean", "MODERN_XR", "false")

        versionCode = 23
        versionName = "1.2.1"

        buildConfigField("boolean", "GOLD", "false")
        fun secret(name: String) =
            project.findProperty(name) as String? ?: System.getenv(name) ?: ""

        buildConfigField("String", "POSTHOG_API_KEY", "\"${secret("POSTHOG_API_KEY")}\"")
        buildConfigField("String", "POSTHOG_HOST",  "\"${secret("POSTHOG_HOST")}\"")
        buildConfigField("String", "STEAMGRIDDB_API_KEY", "\"${secret("STEAMGRIDDB_API_KEY")}\"")
        buildConfigField("String", "CLOUD_PROJECT_NUMBER", "\"${secret("CLOUD_PROJECT_NUMBER")}\"")
        val iconValue = "@mipmap/ic_launcher"
        val iconRoundValue = "@mipmap/ic_launcher_round"
        manifestPlaceholders.putAll(
            mapOf(
                "icon" to iconValue,
                "roundIcon" to iconRoundValue,
            ),
        )

        ndk {
            //abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }

        // Localization support - specify which languages to include
        resourceConfigurations += listOf(
            "en",      // English (default)
            "es",      // Spanish
            "da",      // Danish
            "pt-rBR",  // Portuguese (Brazilian)
            "zh-rTW",  // Traditional Chinese
            "zh-rCN",  // Simplified Chinese
            "fr",      // French
            "de",      // German
            "uk",      // Ukrainian
            "it",      // Italian
            "ro",      // Română
            "pl",      // Polish
            "ru",      // Russian
            "ko",      // Korean
            "ja",      // Japanese
            // TODO: Add more languages here using the ISO 639-1 locale code with regional qualifiers (e.g., "pt-rPT" for European Portuguese)
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        proguardFiles(
            // getDefaultProguardFile("proguard-android-optimize.txt"),
            getDefaultProguardFile("proguard-android.txt"),
            "proguard-rules.pro",
        )
    }

    flavorDimensions += "androidApi"
    productFlavors {
        create("legacy") {
            dimension = "androidApi"
            targetSdk = 28
            ndk.abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            buildConfigField("boolean", "MODERN_ANDROID", "false")
            buildConfigField("String", "PRELOAD_BIONIC_SO", "\"libredirect-bionic.so\"")
        }
        create("legacyXr") {
            dimension = "androidApi"
            targetSdk = 28
            ndk.abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            buildConfigField("boolean", "MODERN_ANDROID", "false")
            buildConfigField("String", "PRELOAD_BIONIC_SO", "\"libredirect-bionic.so\"")
            buildConfigField("boolean", "XR_BUILD", "true")
            manifestPlaceholders["screenOrientation"] = "landscape"
        }
        create("modern") {
            dimension = "androidApi"
            minSdk = 29
            targetSdk = 36
            ndk.abiFilters += listOf("arm64-v8a")
            buildConfigField("boolean", "MODERN_ANDROID", "true")
            buildConfigField("String", "PRELOAD_BIONIC_SO", "\"libredirect-bionic-wx.so\"")
        }
        create("modernXr") {
            dimension = "androidApi"
            minSdk = 29
            targetSdk = 36
            ndk.abiFilters += listOf("arm64-v8a")
            buildConfigField("boolean", "MODERN_ANDROID", "true")
            buildConfigField("String", "PRELOAD_BIONIC_SO", "\"libredirect-bionic-wx.so\"")
            buildConfigField("boolean", "XR_BUILD", "true")
            buildConfigField("boolean", "MODERN_XR", "true")
            buildConfigField("String", "META_APP_ID", "\"$metaAppId\"")
            buildConfigField("String", "PRODUCT_SKU", "\"$productSku\"")
            manifestPlaceholders["screenOrientation"] = "landscape"
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
        }
        create("release-signed") {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("pluvia")
        }
        create("release-gold") {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("pluvia")
            applicationIdSuffix = ".gold"
            buildConfigField("boolean", "GOLD", "true")
            val iconValue = "@mipmap/ic_launcher_gold"
            val iconRoundValue = "@mipmap/ic_launcher_gold_round"
            manifestPlaceholders.putAll(
                mapOf(
                    "icon" to iconValue,
                    "roundIcon" to iconRoundValue,
                ),
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // Exposes the openxr_loader_for_android AAR's native headers/lib to CMake, for the
        // (not yet wired into the default build — see xrimmersive/CMakeLists.txt) immersive
        // VR native module.
        prefab = true
    }

    packaging {
        resources {
            excludes += "/DebugProbesKt.bin"
            excludes += "/junit/runner/smalllogo.gif"
            excludes += "/junit/runner/logo.gif"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
        jniLibs {
            // 'extractNativeLibs' was not enough to keep the jniLibs and
            // the libs went missing after adding on-demand feature delivery
            useLegacyPackaging = true
        }
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "4g"
                it.testLogging { events("started", "failed") }
            }
        }
    }

    lint {
        // Locale files ship full AndroidX appcompat (abc_*) translations that aren't in the
        // default locale. These extra translations are harmless and pre-existing; without this
        // the release-only lintVital pass fails on 150+ ExtraTranslation errors.
        disable += "ExtraTranslation"
    }
    dynamicFeatures += setOf(":ubuntufs")

    // Configure Assets to be used in different variants
    sourceSets {
        getByName("legacy") {
            java.srcDir("src/nonXr/java")
            assets {
                srcDirs("src/legacy/assets", "src/main/assets")
            }
        }
        getByName("legacyXr") {
            java.srcDir("src/nonXr/java")
            // Superset of src/legacy/AndroidManifest.xml plus the immersive VR entries —
            // keep the shared parts in sync with that file.
            manifest.srcFile("src/legacyXr/AndroidManifest.xml")
            assets {
                srcDirs("src/legacy/assets", "src/main/assets")
            }
            jniLibs {
                srcDirs("src/legacy/jniLibs", "src/legacyXr/jniLibs")
            }
        }
        getByName("modern") {
            java.srcDir("src/nonXr/java")
            assets {
                srcDirs("src/modern/assets", "src/main/assets")
            }
        }
        getByName("modernXr") {
            assets {
                srcDirs("src/modern/assets", "src/main/assets")
            }
            jniLibs {
                setSrcDirs(listOf("src/modern/jniLibs", "src/modernXr/jniLibs"))
            }
        }
        getByName("debug") {
            assets.srcDir(copyDebugManifest)
        }
    }

    kotlinter {
        ignoreFormatFailures  = false
    }

    val hostCanRunXrPayloadScripts = System.getProperty("os.name").startsWith("Windows")

    tasks.register<Exec>("buildModernXrNative") {
        enabled = hostCanRunXrPayloadScripts
        commandLine(
            "powershell",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            rootProject.file("tools/build-xr-native.ps1").absolutePath,
        )
    }

    tasks.register<Exec>("buildWindowsXrRuntime") {
        enabled = hostCanRunXrPayloadScripts
        commandLine(
            "powershell",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            rootProject.file("tools/build-windows-xr-runtime.ps1").absolutePath,
        )
    }

    tasks.register<Exec>("stageWineXrBridge") {
        enabled = hostCanRunXrPayloadScripts
        dependsOn("buildModernXrNative")
        val companion = providers.environmentVariable("GAMENATIVE_WINE_XR_BRIDGE")
        doFirst {
            check(companion.isPresent) { "GAMENATIVE_WINE_XR_BRIDGE must point to the ARM64X Wine builtin companion" }
        }
        commandLine(
            "powershell",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            rootProject.file("tools/stage-wine-xr-bridge.ps1").absolutePath,
            "-CompanionPath",
            companion.getOrElse(""),
        )
    }

    tasks.register<Exec>("stageOpenComposite") {
        enabled = hostCanRunXrPayloadScripts
        commandLine(
            "powershell",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            rootProject.file("tools/stage-opencomposite.ps1").absolutePath,
        )
    }

    tasks.register<Exec>("verifyModernXrPayload") {
        enabled = hostCanRunXrPayloadScripts
        dependsOn("buildModernXrNative", "buildWindowsXrRuntime", "stageWineXrBridge", "stageOpenComposite")
        commandLine(
            "powershell",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            rootProject.file("tools/verify-xr-payload.ps1").absolutePath,
        )
    }

    tasks.register("prepareModernXrPayload") {
        dependsOn("verifyModernXrPayload")
    }


    // externalNativeBuild {
    //   cmake {
    //       path = file("src/main/cpp/asurfacerenderer/CMakeLists.txt")
    //   }
    // }

    // externalNativeBuild {
    //    cmake {
    //        path = file("src/main/cpp/evshim/CMakeLists.txt")
    //    }
    // }

    // xconnectorpatch is shipped as a prebuilt jniLib because our APK packaging flow
    // does not rebuild native libraries during release creation.
    // externalNativeBuild {
    //     cmake {
    //         path = file("src/main/cpp/xconnectorpatch/CMakeLists.txt")
    //         version = "3.22.1"
    //     }
    // }

    // build extras needed in libwinlator_bionic.so
    // externalNativeBuild {
    //     cmake {
    //         path = file("src/main/cpp/extras/CMakeLists.txt")   // the file shown above
    //         version = "3.22.1"
    //     }
    // }

    // cmake on release builds a proot that fails to process ld-2.31.so
    // externalNativeBuild {
    //     cmake {
    //         path = file("src/main/cpp/CMakeLists.txt")
    //         version = "3.22.1"
    //     }
    // }

    // Meta Quest immersive launch mode's native OpenXR module. Same convention as the
    // other native modules above: not part of the default build (native libs ship as
    // prebuilt .so files in jniLibs/) — temporarily uncomment to build+test locally,
    // then copy the resulting libxrimmersive.so into jniLibs/arm64-v8a/ and re-comment.
    // externalNativeBuild {
    //     cmake {
    //         path = file("src/main/cpp/xrimmersive/CMakeLists.txt")
    //         version = "3.22.1"
    //     }
    // }

    // (For now) Uncomment for LeakCanary to work.
    // configurations {
    //     debugImplementation {
    //         exclude(group = "junit", module = "junit")
    //     }
    // }
}

dependencies {
    implementation(libs.material)

    // Chrome Custom Tabs for GOG OAuth
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // JavaSteam
    val localBuild = false // Change to 'true' needed when building JavaSteam manually
    if (localBuild) {
        implementation(files("../../JavaSteam/build/libs/javasteam-1.8.0.1-26-SNAPSHOT.jar"))
        implementation(files("../../JavaSteam/javasteam-depotdownloader/build/libs/javasteam-depotdownloader-1.8.0.1-26-SNAPSHOT.jar"))
        implementation(libs.bundles.javasteam.dev)
    } else {
        implementation(libs.javasteam) {
            isChanging = version?.contains("SNAPSHOT") ?: false
        }
        implementation(libs.javasteam.depotdownloader) {
            isChanging = version?.contains("SNAPSHOT") ?: false
        }
    }
    implementation(libs.spongycastle)
    implementation(libs.okhttp.dnsoverhttps)

    // Split Modules
    implementation(libs.bundles.google)

    // Official Khronos OpenXR loader (Apache-2.0) for the Meta Quest immersive launch mode's
    // native module (app/src/main/cpp/xrimmersive) — not a Winlator/GameNativeXR dependency.
    "modernXrImplementation"("org.khronos.openxr:openxr_loader_for_android:1.1.61")

    // Winlator
    implementation(libs.bundles.winlator)
    implementation(libs.libarchive.android)
    implementation(libs.zstd.jni) { artifact { type = "aar" } }
    implementation(libs.xz)

    // Jetpack Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.landscapist.coil)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    debugImplementation(libs.androidx.ui.tooling)

    // Support
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.apng)
    implementation(libs.datastore.preferences)
    implementation(libs.jetbrains.kotlinx.json)
    implementation(libs.kotlin.coroutines)
    implementation(libs.timber)
    implementation(libs.zxing)

    // Google Protobufs
    implementation(libs.protobuf.java)

    // Hilt
    implementation(libs.bundles.hilt)

    // KSP (Hilt, Room)
    ksp(libs.bundles.ksp)

    // Room Database
    implementation(libs.bundles.room)

    // Memory Leak Detection
    // debugImplementation("com.squareup.leakcanary:leakcanary-android:3.0-alpha-8")

    // Testing
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.runner)
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.mockk)
    testImplementation(libs.androidx.ui.test.junit4)
    testImplementation(libs.zstd.jni)
    testImplementation(libs.orgJson)
    testImplementation(libs.mockwebserver)

    // Add PostHog Android SDK dependency
    implementation("com.posthog:posthog-android:3.8.0")

    implementation("com.auth0.android:jwtdecode:2.0.2")

    // Samsung Performance SDK
    implementation(files("src/main/lib/perfsdk-v1.0.0.jar"))

    "modernXrImplementation"("com.meta.horizon.platform.sdk:core-kotlin:0.2.2")
    "modernXrImplementation"("com.meta.horizon.platform.sdk:iap-kotlin:0.2.2")
}
