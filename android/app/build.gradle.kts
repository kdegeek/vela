plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.vela.tv"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.vela.tv"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("boolean", "FIRMWARE", "false")
        buildConfigField("boolean", "FIRMWARE_ENROLL_BY_DEFAULT", "false")
    }

    buildTypes {
        create("firmware") {
            initWith(getByName("release"))
            isDebuggable = false
            signingConfig = null // The image build signs this APK with Vela's private app key.
            buildConfigField("boolean", "FIRMWARE", "true")
            // The owner requested an agent appliance. Enroll once after normal
            // TV setup; an explicit provisioner opt-out or later revocation wins.
            buildConfigField("boolean", "FIRMWARE_ENROLL_BY_DEFAULT", "true")
            matchingFallbacks += listOf("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties", "META-INF/*.kotlin_module")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.ktor.server.cio)
    implementation(libs.zxing)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockwebserver)
}
