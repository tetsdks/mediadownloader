plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    `maven-publish`
}

// The coordinates a consumer writes. Kept identical to what JitPack serves for a module in a
// multi-module repo (com.github.<user>.<repo>:<module>:<tag>), so the dependency line is the same
// whether it resolves from mavenLocal or from JitPack. JitPack passes its own -Pgroup/-Pversion;
// those win, and these are what a local publish uses. Bump `version` and the git tag together.
group = (findProperty("group") as? String)?.takeIf { it.contains('.') }
    ?: "com.github.Dev-Husnain.MediaDownloaderLibrary"
version = (findProperty("version") as? String)?.takeIf { it != Project.DEFAULT_VERSION }
    ?: "0.1.0"

android {
    namespace = "com.markhoor.mediadownloader"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    // No buildTypes block: a library is not minified, so the release block only ever declared
    // rules that nothing applied. What a consumer needs is in consumer-rules.pro.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests {
            // Library code logs through android.util.Log; tests should fail on assertions, not on that.
            isReturnDefaultValues = true
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            // No javadoc jar: it is empty for Kotlin-only sources.
        }
    }
}

dependencies {
    // api: named in the module's own public signatures, so a consumer compiles against them.
    //   Flow/StateFlow - every observe* and every ViewModel's uiState/events
    //   ViewModel, LifecycleOwner - the three public ViewModels and attach()/createBrowser()
    api(libs.kotlinx.coroutines.core)
    api(libs.androidx.lifecycle.viewmodel.ktx)
    api(libs.androidx.lifecycle.runtime.ktx)

    // implementation: never named in a public signature. The database, the worker, the scrapers and
    // every @Serializable type are internal, so none of this reaches a consumer's compile classpath.
    implementation(libs.bundles.ktor.app)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.activity)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            // groupId/artifactId/version default to project.group / the module name / project.version.
            // The module name is the artifactId JitPack serves, so it stays "media_downloader".
            // AGP registers the release component late, hence afterEvaluate.
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("media_downloader")
                description.set(
                    "Link parsing, in-browser media detection and background downloads for Android.",
                )
                url.set("https://github.com/Dev-Husnain/MediaDownloaderLibrary")
                developers {
                    developer {
                        id.set("Dev-Husnain")
                        name.set("Hussnain Mehdi")
                    }
                }
            }
        }
    }
}
