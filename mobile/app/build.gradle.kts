plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.mangaverse.mobile"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    defaultConfig {
        applicationId = "com.mangaverse.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "2.3"
        // Releases do GitHub onde o app procura as próprias atualizações (o APK vai como anexo da release)
        buildConfigField("String", "UPDATE_REPO", "\"lucas-mcarvalho/mangaverse\"")
    }
    buildTypes {
        release {
            // Sem R8: as extensões chamam qualquer classe do Kotlin, OkHttp, Jsoup etc. que estiver no app.
            isMinifyEnabled = false
            // assinado com a chave de debug: o APK é para instalar direto (sideload), não para a Play Store
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.11.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")

    // O que as extensões Tachiyomi (Keiyoushi, extensions-lib 1.6) esperam encontrar no app
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json-okio:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.13.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("com.squareup.okhttp3:okhttp-brotli:5.5.1")
    implementation("com.squareup.okhttp3:okhttp-zstd:5.5.1")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:5.5.1")
    implementation("com.squareup.zstd:zstd-kmp-okio:0.4.0")
    implementation("uy.kohesive.injekt:injekt-core:1.16.1")
    implementation("io.reactivex:rxjava:1.3.8")
    implementation("org.jsoup:jsoup:1.24.1")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.webkit:webkit:1.18.0")
    implementation("com.github.zhanghai.quickjs-java:quickjs-android:547f5b1597")
}
