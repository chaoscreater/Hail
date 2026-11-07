import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    val signingProps = file("../signing.properties")
    // Edit this by hand for a new release; the date is added to the version shown in the app on every build.
    // The APK file name only uses this part, so it stays the same between builds.
    val versionBase = "1.10.0"
    val buildDateTime = LocalDateTime.now()

    namespace = "com.aistra.hail"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aistra.hail"
        minSdk = 23
        targetSdk = 36
        // yyMMddHH, e.g. 26110715 (must rise with every build; stays under Android's 2.1 billion limit)
        versionCode = buildDateTime.format(DateTimeFormatter.ofPattern("yyMMddHH")).toInt()
        versionName = "$versionBase.${buildDateTime.format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))}"
        buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
    }

    buildTypes {
        debug {
            // applicationIdSuffix removed so debug builds use com.aistra.hail
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (signingProps.exists()) {
                val props = `java.util`.Properties().apply { load(signingProps.reader()) }
                signingConfigs.create("release") {
                    storeFile = file(props.getProperty("storeFile"))
                    storePassword = props.getProperty("storePassword")
                    keyAlias = props.getProperty("keyAlias")
                    keyPassword = props.getProperty("keyPassword")
                }
            } else signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
        }
    }
    applicationVariants.configureEach {
        outputs.configureEach {
            (this as? com.android.build.gradle.internal.api.ApkVariantOutputImpl)?.outputFileName =
                "Hail-v$versionBase.apk"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
    }
    androidResources {
        generateLocaleConfig = true
        // Do not compress the dex files, so the apk can be imported as a privileged app
        noCompress += "dex"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.biometric.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.pinyin4j)
    implementation(libs.material)
    implementation(libs.insetter)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.dhizuku.api)
    implementation(libs.appiconloader)
    implementation(libs.compose.preference)
    implementation(libs.commons.text)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hiddenapibypass)
    compileOnly(libs.xposed)
}
