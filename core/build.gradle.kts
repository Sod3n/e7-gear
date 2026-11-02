plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    `java-test-fixtures`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ktlint {
    version.set(libs.versions.ktlint.get())
}

dependencies {
    // Android provides org.json at run time; tests use the Maven copy
    compileOnly(libs.json)
    testImplementation(libs.json)
    testImplementation(libs.junit)
    testFixturesCompileOnly(libs.json)
    testFixturesCompileOnly(libs.junit)
}
