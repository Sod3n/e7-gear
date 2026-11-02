plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// one place for the version: appVersion=major.minor.patch in gradle.properties; the code counts up with it
val appVersion = project.property("appVersion").toString()
val versionParts = appVersion.split('.').map { it.toInt() }

base {
    archivesName.set("e7.gearscan-$appVersion")
}

val releaseStore: String? = System.getenv("E7_RELEASE_STORE")?.takeIf { it.isNotEmpty() }
val testBuildTypeEnv: String? = System.getenv("E7_TEST_BUILD_TYPE")?.takeIf { it.isNotEmpty() }

android {
    namespace = "e7.gearscan"
    compileSdk = 34
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "e7.gearscan"
        minSdk = 29
        targetSdk = 34
        versionCode = versionParts[0] * 10000 + versionParts[1] * 100 + versionParts[2]
        versionName = appVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
        externalNativeBuild {
            ndkBuild {
                arguments("APP_CFLAGS+=-DPKGNAME=e7/gearscan -ffile-prefix-map=${rootDir}=.")
                arguments("APP_LDFLAGS+=-Wl,--build-id=none")
            }
        }
    }

    // the release key lives outside the repo; tools/release.sh passes its path and password (from the Keychain).
    // Without them a release build comes out unsigned rather than signed with some other key.
    signingConfigs {
        create("release") {
            if (releaseStore != null) {
                storeFile = file(releaseStore)
                storePassword = System.getenv("E7_RELEASE_PASSWORD")
                keyAlias = System.getenv("E7_RELEASE_ALIAS")?.takeIf { it.isNotEmpty() } ?: "e7gear"
                keyPassword = System.getenv("E7_RELEASE_PASSWORD")
            }
        }
    }

    // device timings (SearchSpeedTest, PoolSpeedTest) need E7_TEST_BUILD_TYPE=release: debuggable builds run ~3x slower
    testBuildType = testBuildTypeEnv ?: "debug"

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseStore != null) {
                signingConfigs.getByName("release")
            } else if (testBuildTypeEnv == "release") {
                signingConfigs.getByName("debug")
            } else {
                null
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }

    // the audit's box lives outside the repo (it is someone's real account): E7_FIXTURES=<dir with gear.txt>
    System.getenv("E7_FIXTURES")?.takeIf { it.isNotEmpty() }?.let { fixtures ->
        sourceSets.getByName("androidTest").assets.srcDir(fixtures)
    }

    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }
}

// screens read the model's in-place state through rev; skipping by argument identity would hide those edits
composeCompiler {
    enableStrongSkippingMode = false
}

dependencies {
    implementation(project(":core"))
    implementation(project(":kit"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coil.compose)
    implementation(libs.coroutines.android)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.json)
    testImplementation(testFixtures(project(":core")))
}

// lint errors (e.g. an API used without its permission) block release APKs
afterEvaluate { tasks.named("assembleRelease") { dependsOn("lintRelease") } }
