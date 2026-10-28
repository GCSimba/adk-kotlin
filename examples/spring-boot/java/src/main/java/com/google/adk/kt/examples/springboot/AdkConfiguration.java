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

package com.google.adk.kt.examples.springboot;

import com.google.adk.kt.apps.App;
import com.google.adk.kt.interop.CompletableFutureSessionService;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.models.Model;
import com.google.adk.kt.models.VertexCredentials;
import com.google.adk.kt.runners.InMemoryRunner;
import com.google.adk.kt.sessions.InMemorySessionService;
import com.google.adk.kt.workflow.Workflow;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring beans for the ADK: the Gemini model, the session service, and the app and runner for the
 * workflow that {@link SupportWorkflowConfiguration} defines.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdkProperties.class)
class AdkConfiguration {

  /** Creates the model every agent uses, calling Vertex AI when a project is set. */
  @Bean
  Model geminiModel(AdkProperties properties) {
    AdkProperties.Vertex vertex = properties.vertex();
    if (vertex == null || vertex.project() == null || vertex.project().isBlank()) {
      // Reads the key from the GOOGLE_API_KEY or GEMINI_API_KEY environment variable.
      return new Gemini(properties.model());
    }
    return new Gemini(
        properties.model(),
        VertexCredentials.builder().project(vertex.project()).location(vertex.location()).build());
  }

  /**
   * Creates the session store shared by the runner and the service, wrapped for Java callers.
   * Spring closes it on shutdown.
   */
  @Bean
  CompletableFutureSessionService sessionService() {
    return CompletableFutureSessionService.of(new InMemorySessionService());
  }

  /** Creates the ADK app, which runs the support workflow as its root node. */
  @Bean
  App supportApp(Workflow supportWorkflow) {
    return App.builder().appName("support").rootNode(supportWorkflow).build();
  }

  /** Creates the runner for {@code supportApp}. Spring closes the runner on shutdown. */
  @Bean
  PublisherRunner supportRunner(App supportApp, CompletableFutureSessionService sessionService) {
    return PublisherRunner.of(
        InMemoryRunner.builder()
            .app(supportApp)
            .sessionService(sessionService.asKotlinSessionService())
            .build());
  }
}
