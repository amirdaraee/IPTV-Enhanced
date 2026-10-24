import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    androidLibrary {
        namespace = "app.tuner.core"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
        withHostTestBuilder {}
    }

    jvm()

    val xcf = XCFramework("TunerCore")
    listOf(tvosArm64(), tvosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "TunerCore"
            isStatic = true
            xcf.add(this)
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        val jvmAndroidMain by creating { dependsOn(commonMain.get()) }
        androidMain.get().dependsOn(jvmAndroidMain)
        jvmMain.get().dependsOn(jvmAndroidMain)

        commonMain.dependencies {
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
            implementation(libs.datetime)
            implementation(libs.ktor.core)
            implementation(libs.ktor.encoding)
            implementation(libs.sqldelight.coroutines)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.mock)
        }
        androidMain.dependencies {
            implementation(libs.coroutines.android)
            implementation(libs.ktor.okhttp)
            implementation(libs.sqldelight.android)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.okhttp)
            implementation(libs.sqldelight.sqlite)
        }
        appleMain.dependencies {
            implementation(libs.ktor.darwin)
            implementation(libs.sqldelight.native)
        }
    }
}

sqldelight {
    databases {
        create("TunerDatabase") {
            packageName.set("app.tuner.core.db")
        }
    }
}
