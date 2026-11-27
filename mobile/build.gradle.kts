buildscript {
    dependencies {
        // mesma versão do Kotlin usada pelas extensões do Keiyoushi (a stdlib vai no APK e é usada por elas)
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    id("com.android.application") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10" apply false
}
