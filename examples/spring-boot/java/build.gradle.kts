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

// An order-support service written in plain Java, with reflective tools and Reactor handlers.
// Like the Java examples, it applies no Kotlin plugin, so javac sees only the Java-facing ADK API.
// Run it with ./gradlew :google-adk-kotlin-examples-spring-boot-java:bootRun; see README.md.
plugins {
  id("java")
  alias(libs.plugins.spring.boot)
}

val jdkVersion = providers.gradleProperty("jdkVersion").getOrElse("17").toInt()

java { toolchain { languageVersion = JavaLanguageVersion.of(jdkVersion) } }

dependencies {
  // Unlike the dependency-management plugin, platform() cannot downgrade the ADK's dependencies.
  implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
  implementation(project(":google-adk-kotlin-core"))
  implementation(libs.spring.boot.starter.webflux)
  // MDCContext, which carries the caller's SLF4J MDC into the run's model and tool calls.
  implementation(libs.kotlinx.coroutines.slf4j)

  testImplementation(libs.spring.boot.starter.webflux.test)
  testImplementation(libs.google.truth)
  testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test { useJUnitPlatform() }
