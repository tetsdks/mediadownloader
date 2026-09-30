// Top-level build file where you can add configuration options common to all sub-projects/modules.
//
// Every Kotlin-adjacent plugin is declared here, even the ones only one module applies. AGP 9
// compiles Kotlin with whichever kotlin-gradle-plugin is on the buildscript classpath, and
// declaring them all in one place means that is resolved once, for every module, rather than a
// module quietly ending up on a different Kotlin.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
