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

@file:OptIn(ExperimentalWorkflowApi::class)

package com.google.adk.kt.examples.springboot

import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.models.Model
import com.google.adk.kt.models.VertexCredentials
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.runners.Runner
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.workflow.Workflow
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Spring beans for the ADK: the Gemini model, the session service, and the app and runner for the
 * workflow that [SupportWorkflowConfiguration] defines.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdkProperties::class)
class AdkConfiguration {

  /** Creates the model every agent uses, calling Vertex AI when a project is set. */
  @Bean
  fun geminiModel(properties: AdkProperties): Model {
    val vertex = properties.vertex
    if (vertex == null || vertex.project.isNullOrBlank()) {
      // Reads the key from the GOOGLE_API_KEY or GEMINI_API_KEY environment variable.
      return Gemini(name = properties.model)
    }
    return Gemini(
      name = properties.model,
      vertexCredentials = VertexCredentials(project = vertex.project, location = vertex.location),
    )
  }

  /** Creates the session store shared by the runner and the service. */
  @Bean fun sessionService(): SessionService = InMemorySessionService()

  /** Creates the ADK app, which runs the support workflow as its root node. */
  @Bean
  fun supportApp(supportWorkflow: Workflow): App =
    App(appName = "support", rootNode = supportWorkflow)

  /** Creates the runner for [supportApp]. Spring closes the runner on shutdown. */
  @Bean
  fun supportRunner(supportApp: App, sessionService: SessionService): Runner =
    InMemoryRunner(app = supportApp, sessionService = sessionService)
}
