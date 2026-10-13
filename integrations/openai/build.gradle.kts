/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
  kotlin("multiplatform")
  kotlin("plugin.serialization")
  id("com.android.kotlin.multiplatform.library")
  id("maven-publish")
}

kotlin {
  // AGP 9 KMP Android library target (replaces com.android.library + androidTarget).
  android {
    namespace = "com.google.adk.kt.models.openai"
    compileSdk = rootProject.extra["androidCompileSdk"] as Int
    minSdk = rootProject.extra["androidMinSdk"] as Int
  }
  jvm()

  sourceSets {
    val commonMain =
      getByName("commonMain") { dependencies { api(project(":google-adk-kotlin-core")) } }
    // The code runs on the JVM and Android alike, through Ktor's OkHttp engine.
    val commonJvmAndroidMain =
      create("commonJvmAndroidMain") {
        dependsOn(commonMain)
        dependencies {
          implementation(libs.kotlinx.coroutines.core)
          implementation(libs.kotlinx.serialization)
          implementation(libs.ktor.client.core)
          implementation(libs.ktor.client.okhttp)
        }
      }
    getByName("jvmMain") { dependsOn(commonJvmAndroidMain) }
    getByName("androidMain") { dependsOn(commonJvmAndroidMain) }
    getByName("jvmTest") {
      dependencies {
        implementation(kotlin("test"))
        implementation(libs.junit)
        implementation(libs.google.truth)
        // Drives the real Ktor client against canned HTTP responses.
        implementation(libs.okhttp.mockwebserver)
      }
    }
  }
}

// Coordinates the Kotlin Multiplatform plugin uses for the publications it auto-creates:
//   - `kotlinMultiplatform` -> google-adk-kotlin-integrations-openai         (root metadata)
//   - `jvm`                 -> google-adk-kotlin-integrations-openai-jvm     (KMP target)
//   - `androidRelease`      -> google-adk-kotlin-integrations-openai-android (KMP target)
// Only the root publication's artifactId is set here; the KMP plugin appends the per-target
// suffixes. POM metadata, Dokka javadoc, and GPG signing are configured in the root
// build.gradle.kts.
publishing {
  publications.withType<MavenPublication>().configureEach {
    if (name == "kotlinMultiplatform") {
      artifactId = "google-adk-kotlin-integrations-openai"
    }
  }
}
