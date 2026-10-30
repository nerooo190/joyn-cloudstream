import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
    dependencies {
        // AGP's bundled D8 predates Kotlin 2.4 metadata. Use the current stable dexer.
        classpath("com.android.tools:r8:9.5.28")
        classpath("com.android.tools.build:gradle:8.7.3")
        classpath("com.github.recloudstream:gradle:-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(block: CloudstreamExtension.() -> Unit) =
    extensions.getByName<CloudstreamExtension>("cloudstream").block()

fun Project.android(block: BaseExtension.() -> Unit) =
    extensions.getByName<BaseExtension>("android").block()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo(providers.gradleProperty("repository").orNull
            ?: System.getenv("GITHUB_REPOSITORY") ?: "nerooo190/joyn-cloudstream")
    }
    android {
        namespace = "de.joyncloudstream"
        compileSdkVersion(35)
        buildToolsVersion = "35.0.0"
        defaultConfig {
            minSdk = 21
            targetSdk = 35
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }
        tasks.withType<KotlinJvmCompile>().configureEach {
            compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }
    dependencies {
        add("cloudstream", "com.lagradost:cloudstream3:pre-release")
        add("implementation", kotlin("stdlib"))
        add("implementation", "com.github.Blatzar:NiceHttp:0.4.18")
        add("implementation", "org.jsoup:jsoup:1.18.3")
        // Cloudstream supplies Jackson at runtime; keep its Android-compatible version.
        add("implementation", "com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
        add("implementation", "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        add("testImplementation", "junit:junit:4.13.2")
    }
}
