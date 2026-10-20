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

@file:OptIn(ExperimentalWorkflowApi::class, FrameworkInternalApi::class)
// typeOf<T>() is a compiler intrinsic and does not require kotlin-reflect.
@file:Suppress("KotlinReflectNeeded")

package com.google.adk.kt.workflow

import com.google.adk.kt.SchemaUtils
import com.google.adk.kt.agents.Context
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.ToolConfirmation
import com.google.adk.kt.events.parseToolConfirmation
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.serialization.jsonElementToAny
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Schema
import kotlin.reflect.KClass
import kotlin.reflect.KClassifier
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.descriptors.nullable

/**
 * A workflow [Node] backed by a suspending function that may emit progress [Event]s and returns an
 * output of type [O]. Its schemas inferred from [I] and [O] only describe it; an explicit schema
 * passed to [node] is also enforced.
 *
 * Inline workflow steps use [node] or [parallelNode].
 *
 * @param I Expected type of `nodeInput` from predecessor nodes.
 * @param O Type of the value the body returns, which is the node's output. `Unit` or `null` is no
 *   output, an [Event] passes through as it is, and a [RequestInput] fails. Any other value, a
 *   [Content] included, is output as its JSON form under [O]'s serializer.
 */
@ExperimentalWorkflowApi
class FunctionNode<I, O>
private constructor(
  name: String,
  description: String,
  rerunOnResume: Boolean,
  waitForOutput: Boolean,
  config: NodeConfig,
  private val explicitInputSchema: Schema?,
  private val explicitOutputSchema: Schema?,
  stateSchema: Schema?,
  internal val inputCodec: ValueCodec<I>,
  internal val outputCodec: ValueCodec<O>,
  internal val itemNode: FunctionNode<*, *>?,
  /** Maximum number of items a parallel node runs at a time, or `null` for no limit. */
  internal val maxParallelWorkers: Int?,
  /** Whether each run asks the user to approve it before the body runs. */
  internal val requireConfirmation: Boolean,
  /** Decides per input whether a run asks the user to approve it, or `null` to not decide. */
  private val confirmationPredicate: (suspend (Context, I) -> Boolean)?,
  private val body: suspend FlowCollector<Event>.(Context, I) -> O,
) :
  BaseNode(
    name = name,
    description = description,
    rerunOnResume = rerunOnResume || itemNode != null,
    waitForOutput = waitForOutput,
    config = config,
    // A parallel node declares no port schemas, as in adk-python; its item node keeps its own.
    inputSchema =
      if (itemNode != null) null else explicitInputSchema ?: inferredPortSchema(inputCodec),
    outputSchema =
      if (itemNode != null) null else explicitOutputSchema ?: inferredPortSchema(outputCodec),
    stateSchema = stateSchema,
  ) {

  @PublishedApi
  internal constructor(
    name: String,
    description: String = "",
    rerunOnResume: Boolean = false,
    waitForOutput: Boolean = false,
    config: NodeConfig = NodeConfig(),
    inputSchema: Schema? = null,
    outputSchema: Schema? = null,
    stateSchema: Schema? = null,
    inputType: KType,
    outputType: KType,
    itemNode: FunctionNode<*, *>? = null,
    maxParallelWorkers: Int? = null,
    requireConfirmation: Boolean = false,
    confirmationPredicate: (suspend (Context, I) -> Boolean)? = null,
    body: suspend FlowCollector<Event>.(Context, I) -> O,
  ) : this(
    name = name,
    description = description,
    rerunOnResume = rerunOnResume,
    waitForOutput = waitForOutput,
    config = config,
    explicitInputSchema = inputSchema,
    explicitOutputSchema = outputSchema,
    stateSchema = stateSchema,
    inputCodec = ValueCodec(inputType),
    outputCodec = ValueCodec(outputType),
    itemNode = itemNode,
    maxParallelWorkers = maxParallelWorkers,
    requireConfirmation = requireConfirmation,
    confirmationPredicate = confirmationPredicate,
    body = body,
  )

  internal val inputType: KType
    get() = inputCodec.kType

  /** Whether runs may ask the user to approve them, always or per [confirmationPredicate]. */
  private val asksForConfirmation: Boolean
    get() = requireConfirmation || confirmationPredicate != null

  internal val outputType: KType
    get() = outputCodec.kType

  init {
    validateNodeName(name)
    require(maxParallelWorkers == null || itemNode != null) {
      "maxParallelWorkers requires an item node."
    }
    checkMaxParallelWorkers(maxParallelWorkers)
    if (asksForConfirmation && !this.rerunOnResume) {
      throw WorkflowConfigurationException(
        "node '$name' requires confirmation, so it needs rerunOnResume = true to run again once " +
          "the user answers."
      )
    }
  }

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    if (itemNode != null) {
      emit(ParallelWorker(itemNode, maxParallelWorkers).mapOverItems(context, nodeInput))
      return@flow
    }
    // A no-op after validateInput, and it covers a direct call.
    val input = inputCodec.coerce(nodeInput, "input of node '$name'")
    val confirmation = if (asksForConfirmation) confirmation(context, input) else null
    if (confirmation?.confirmed == false) {
      // The rejection is the run's output.
      emit(REJECTED_OUTPUT)
      return@flow
    }
    // An emitted event carries messages and actions only; the output is returned or delegated.
    val events =
      FlowCollector<Event> { event ->
        require(event.output == null) {
          "node '$name' emitted an Event with an output; return the value instead."
        }
        require(event.nodeInfo?.messageAsOutput != true) {
          "node '$name' emitted an Event marked messageAsOutput; return the value instead."
        }
        emit(event)
      }
    val result = events.body(context, input)
    val delegated = context.output
    val encoded = delegated?.let { encodeDelegatedOutput(it) }
    when (result) {
      is RequestInput ->
        throw IllegalArgumentException(
          "node '$name' returned a RequestInput; call requestInput(...) to ask for input."
        )
      else -> emit(result)
    }
    // A delegated output that encoding changed goes out again as this node's own output event.
    if (encoded != null && encoded != delegated) {
      context.requireNodeState().replaceDelegatedOutput(encoded)
    }
    // An event marks a confirmed run that output nothing as finished, so a later resume skips it.
    if (confirmation != null && delegated == null && (result == null || result == Unit))
      emit(Event())
  }

  /**
   * Returns the user's answer to this run's confirmation request and sets it as the context's
   * [Context.toolConfirmation], or null when [confirmationPredicate] decides not to ask. Without an
   * answer yet, asks for one and ends the run.
   */
  private suspend fun FlowCollector<Nothing>.confirmation(
    context: Context,
    input: I,
  ): ToolConfirmation? {
    val interruptId = "$CONFIRMATION_INTERRUPT_PREFIX${context.nodePath}"
    val answer = context.resumeInputs[interruptId]
    if (answer == null) {
      // A present but null answer asks again without deciding again.
      val decide = interruptId !in context.resumeInputs && !requireConfirmation
      if (decide && confirmationPredicate?.invoke(context, input) != true) return null
      requestInput(
        interruptId = interruptId,
        message = "Please approve or reject the tool call $name().",
        payload =
          mapOf("tool_name" to name, "args" to inputCodec.encode(input, "input of node '$name'")),
        responseSchema = TOOL_CONFIRMATION_SCHEMA,
      )
    }
    val confirmation =
      (answer as? Map<*, *>)?.let(::parseToolConfirmation)
        ?: throw IllegalArgumentException(
          "node '$name' got an answer to its confirmation request that is not a ToolConfirmation."
        )
    context.toolConfirmation = confirmation
    return confirmation
  }

  /**
   * Makes [nodeInput] an [I], then checks its JSON form against an explicit [inputSchema]. A
   * parallel node passes it through, and its item node checks each item.
   */
  override fun validateInput(nodeInput: Any?): Any? {
    if (itemNode != null) return nodeInput
    val what = "input of node '$name'"
    val input = inputCodec.coerce(nodeInput, what)
    if (explicitInputSchema != null) {
      val checked =
        try {
          inputCodec.schemaForm(input, what)
        } catch (e: IllegalArgumentException) {
          throw NodeInputValidationException("validation error: ${e.message}", e)
        }
      val unused =
        SchemaUtils.validateValue(checked, explicitInputSchema, what).getOrElse {
          throw NodeInputValidationException(it.message.orEmpty(), it)
        }
    }
    return input
  }

  /**
   * Returns [value], the output of a child this node gave its output to with `useAsOutput`, as if
   * this node had returned it. Throws [IllegalArgumentException] if it is not an [O].
   */
  private fun encodeDelegatedOutput(value: Any): Any? {
    val classifier = outputCodec.kType.classifier
    require(classifier != Unit::class) {
      "node '$name' declares no output (Unit) but gives a child its output; declare its output type."
    }
    // A node typed Event passes the child's value on as it is, since a value is not an Event.
    if (classifier == Event::class) return validateOutput(value)
    val output =
      try {
        outputCodec.coerce(value, "output of node '$name'")
      } catch (e: NodeInputValidationException) {
        throw IllegalArgumentException(e.message, e)
      }
    return validateOutput(output)
  }

  /**
   * Returns a returned value, or a returned event's output, as successors receive it: a [Content]
   * as its JSON form, an [Event] as it is, and any other value as its JSON form under [O]'s
   * serializer. An explicit [outputSchema] then checks the result.
   */
  override fun validateOutput(output: Any?): Any? {
    // A parallel node outputs its items' outputs, which each item node already validated.
    if (itemNode != null) return output
    if (asksForConfirmation && output == REJECTED_OUTPUT) return output
    // Assigning null produces no output.
    if (output == null) return null
    val what = "output of node '$name'"
    // Only a node whose O is Content, Event or Any returns a Content, which has one JSON form.
    val json = if (output is Content) contentJson(output) else outputCodec.schemaForm(output, what)
    if (explicitOutputSchema != null) {
      val unused = SchemaUtils.validateValue(json, explicitOutputSchema, what).getOrThrow()
    }
    return json
  }
}

/** The JSON form of [content], as a session saves it. */
private fun contentJson(content: Content): Any? =
  jsonElementToAny(adkJson.encodeToJsonElement(Content.serializer(), content))

/** The interrupt id prefix of a node run's confirmation request, followed by the node path. */
private const val CONFIRMATION_INTERRUPT_PREFIX = "wf_tool_confirmation:"

/** The output of a run the user rejected. */
private val REJECTED_OUTPUT: Map<String, Any?> = mapOf("error" to "This tool call is rejected.")

/** The schema of a confirmation answer. */
private val TOOL_CONFIRMATION_SCHEMA: Schema? by lazy {
  SchemaUtils.inferSchema(typeOf<ToolConfirmation>())
}

/** Types a node handles by their meaning rather than as data. */
private val FRAMEWORK_TYPES: Set<KClass<*>> = setOf(Content::class, Event::class)

/**
 * Port types that need no serializer: a framework value passes as it is, `Any` is a JSON value, and
 * `Unit` carries nothing.
 */
private val SERIALIZER_FREE_TYPES: Set<KClass<*>> = FRAMEWORK_TYPES + setOf(Any::class, Unit::class)

/** Infers a port's schema, except for framework types. */
private fun inferredPortSchema(codec: ValueCodec<*>): Schema? =
  if (codec.kType.classifier in FRAMEWORK_TYPES) null else codec.schema

/**
 * Returns [value] as a node outputs it and a schema checks it: a framework value as it is, and
 * anything else as JSON.
 */
internal fun ValueCodec<*>.schemaForm(value: Any?, what: String): Any? =
  if (kType.classifier in FRAMEWORK_TYPES || value is Content || value is Event) {
    value
  } else {
    encode(value, what)
  }

/**
 * Creates a [FunctionNode] that runs [block] on an input of type [I] and returns an output of type
 * [O], emitting progress [Event]s through its [FlowCollector] receiver. Schemas inferred from [I]
 * and [O] describe the node, while an explicit [inputSchema] or [outputSchema] is also enforced on
 * every run. Unlike adk-python, which dumps only models, an output travels as its JSON form under
 * [O]'s serializer, so a successor typed `Any?` reads a `Long` for an `Int`, and an input decodes
 * from that form, a quoted number included.
 *
 * The block asks the user for input with [requestInput]. Its return value, a [Content] included, is
 * the node's output; emitting an event with an output or calling `Context.setOutput` fails the
 * node. A node that talks to an agent node sends the message with `emit(content)`.
 *
 * @param rerunOnResume Whether a resume runs the node again rather than using the resuming answer;
 *   unset, it is true when [requireConfirmation] is true.
 * @param requireConfirmation Whether each run first asks the user to approve it: pass `true`, or a
 *   predicate that decides for each input. The request carries the input's JSON form as `args`; a
 *   rejected run skips the block and outputs `{"error": "This tool call is rejected."}`, and the
 *   answer is available as [Context.toolConfirmation].
 * @throws WorkflowConfigurationException if [I] or [O] has no usable serializer and is not
 *   `Content`, `Event`, `Any`, or `Unit`, or if [requireConfirmation] is true with `rerunOnResume =
 *   false`.
 */
@ExperimentalWorkflowApi
inline fun <reified I, reified O> node(
  name: String,
  description: String = "",
  rerunOnResume: Boolean? = null,
  waitForOutput: Boolean = false,
  config: NodeConfig = NodeConfig(),
  inputSchema: Schema? = null,
  outputSchema: Schema? = null,
  stateSchema: Schema? = null,
  requireConfirmation: Boolean = false,
  crossinline block: suspend FlowCollector<Event>.(context: Context, input: I) -> O,
): FunctionNode<I, O> =
  buildFunctionNode(
    name = name,
    description = description,
    rerunOnResume = rerunOnResume,
    waitForOutput = waitForOutput,
    config = config,
    inputSchema = inputSchema,
    outputSchema = outputSchema,
    stateSchema = stateSchema,
    requireConfirmation = requireConfirmation,
    confirmationPredicate = null,
    block = block,
  )

/**
 * Creates a [FunctionNode] as the other [node] overload does, asking the user to approve a run only
 * when [requireConfirmation] returns true for its decoded input.
 *
 * @param rerunOnResume Whether a resume runs the node again rather than using the resuming answer;
 *   unset, it is true.
 * @param requireConfirmation Decides for each input whether a run asks the user to approve it; it
 *   runs on each run that has no answer yet, and once the node has asked, the answer decides.
 * @throws WorkflowConfigurationException if [I] or [O] has no usable serializer and is not
 *   `Content`, `Event`, `Any`, or `Unit`, or with `rerunOnResume = false`.
 */
@ExperimentalWorkflowApi
inline fun <reified I, reified O> node(
  name: String,
  description: String = "",
  rerunOnResume: Boolean? = null,
  waitForOutput: Boolean = false,
  config: NodeConfig = NodeConfig(),
  inputSchema: Schema? = null,
  outputSchema: Schema? = null,
  stateSchema: Schema? = null,
  noinline requireConfirmation: suspend (context: Context, input: I) -> Boolean,
  crossinline block: suspend FlowCollector<Event>.(context: Context, input: I) -> O,
): FunctionNode<I, O> =
  buildFunctionNode(
    name = name,
    description = description,
    rerunOnResume = rerunOnResume,
    waitForOutput = waitForOutput,
    config = config,
    inputSchema = inputSchema,
    outputSchema = outputSchema,
    stateSchema = stateSchema,
    requireConfirmation = false,
    confirmationPredicate = requireConfirmation,
    block = block,
  )

/** Builds the [FunctionNode] that both [node] overloads create. */
@PublishedApi
internal inline fun <reified I, reified O> buildFunctionNode(
  name: String,
  description: String,
  rerunOnResume: Boolean?,
  waitForOutput: Boolean,
  config: NodeConfig,
  inputSchema: Schema?,
  outputSchema: Schema?,
  stateSchema: Schema?,
  requireConfirmation: Boolean,
  noinline confirmationPredicate: (suspend (Context, I) -> Boolean)?,
  crossinline block: suspend FlowCollector<Event>.(context: Context, input: I) -> O,
): FunctionNode<I, O> {
  requirePortSerializer(name, "input", typeOf<I>())
  requirePortSerializer(name, "output", typeOf<O>())
  return FunctionNode(
    name = name,
    description = description,
    rerunOnResume = rerunOnResume ?: (requireConfirmation || confirmationPredicate != null),
    waitForOutput = waitForOutput,
    config = config,
    inputSchema = inputSchema,
    outputSchema = outputSchema,
    stateSchema = stateSchema,
    inputType = typeOf<I>(),
    outputType = typeOf<O>(),
    requireConfirmation = requireConfirmation,
    confirmationPredicate = confirmationPredicate,
    body = { context, input -> block(context, input) },
  )
}

/**
 * Emits [content] from a [node] body as a message, which is not the node's output. In a custom
 * [Node]'s `flow {}`, `emit(content)` emits a raw value instead, which becomes the output.
 */
@ExperimentalWorkflowApi
suspend fun FlowCollector<Event>.emit(content: Content) {
  emit(Event(content = content))
}

/**
 * Throws [WorkflowConfigurationException] when node [name]'s [port] type [type], or a type argument
 * inside it, cannot travel as JSON, so the node fails when it is built rather than on its first
 * value.
 */
@PublishedApi
internal fun requirePortSerializer(name: String, port: String, type: KType) {
  val problem = portTypeProblem(type, nested = false) ?: return
  throw WorkflowConfigurationException("node '$name' has $port type $type, $problem")
}

/** Returns why [type] or one of its type arguments cannot travel as JSON, or null if it can. */
private fun portTypeProblem(type: KType, nested: Boolean): String? {
  val classifier = type.classifier
  if (classifier in SERIALIZER_FREE_TYPES) return null
  if (nested && classifier == ByteArray::class) {
    return "which has a ByteArray type argument; only a top-level ByteArray is supported for now."
  }
  val serializer = SchemaUtils.serializerFor(type)
  if (serializer == null || isOpenPolymorphic(serializer, classifier)) {
    return "which has no usable serializer. Mark it @Serializable, or use Any for a JSON-native " +
      "value, such as Map<String, Any?> for Map<String, *>."
  }
  return type.arguments.firstNotNullOfOrNull { argument ->
    argument.type?.let { portTypeProblem(it, nested = true) }
  }
}

/**
 * Whether [serializer] is an interface's polymorphic serializer, nullable or not, which adkJson
 * cannot use because it registers no subclasses.
 */
private fun isOpenPolymorphic(serializer: KSerializer<*>, classifier: KClassifier?): Boolean {
  if (serializer is PolymorphicSerializer<*>) return true
  @Suppress("UNCHECKED_CAST") val kClass = classifier as? KClass<Any> ?: return false
  return serializer.descriptor == PolymorphicSerializer(kClass).descriptor.nullable
}

/**
 * Creates a [FunctionNode] that runs [block] concurrently on each item of a list input and returns
 * the results as a [List] of [O] in input order. A text-only [Content] input, unless [I] is
 * [Content], is read as JSON so a JSON list fans out. Any other non-list input runs as one item.
 *
 * [config], the schemas, [rerunOnResume] and [requireConfirmation] apply to each item's run, so
 * each item asks for its own approval; the fan-out node shares [waitForOutput] and [stateSchema],
 * declares no input or output schema, has no retry or timeout, and always reruns on resume.
 *
 * @param maxParallelWorkers Maximum number of items run at a time, or `null` for no limit.
 * @param requireConfirmation Whether each item's run first asks the user to approve it: pass
 *   `true`, or a predicate that decides for each item, as in [node].
 * @throws WorkflowConfigurationException if [maxParallelWorkers] is less than 1, or as in [node].
 */
@ExperimentalWorkflowApi
inline fun <reified I, reified O> parallelNode(
  name: String,
  description: String = "",
  rerunOnResume: Boolean? = null,
  waitForOutput: Boolean = false,
  maxParallelWorkers: Int? = null,
  config: NodeConfig = NodeConfig(),
  inputSchema: Schema? = null,
  outputSchema: Schema? = null,
  stateSchema: Schema? = null,
  requireConfirmation: Boolean = false,
  crossinline block: suspend FlowCollector<Event>.(context: Context, input: I) -> O,
): FunctionNode<List<I>, List<O>> =
  parallelNode(
    node =
      node<I, O>(
        name = name,
        description = description,
        rerunOnResume = rerunOnResume,
        waitForOutput = waitForOutput,
        config = config,
        inputSchema = inputSchema,
        outputSchema = outputSchema,
        stateSchema = stateSchema,
        requireConfirmation = requireConfirmation,
        block = block,
      ),
    maxParallelWorkers = maxParallelWorkers,
  )

/**
 * Creates a fan-out [FunctionNode] as the other block-form [parallelNode] overload does, asking the
 * user to approve an item's run only when [requireConfirmation] returns true for that item. The
 * predicate runs on each item's run that has no answer yet; once the item has asked, the answer
 * decides.
 *
 * @param maxParallelWorkers Maximum number of items run at a time, or `null` for no limit.
 * @throws WorkflowConfigurationException if [maxParallelWorkers] is less than 1, or as in [node].
 */
@ExperimentalWorkflowApi
inline fun <reified I, reified O> parallelNode(
  name: String,
  description: String = "",
  rerunOnResume: Boolean? = null,
  waitForOutput: Boolean = false,
  maxParallelWorkers: Int? = null,
  config: NodeConfig = NodeConfig(),
  inputSchema: Schema? = null,
  outputSchema: Schema? = null,
  stateSchema: Schema? = null,
  noinline requireConfirmation: suspend (context: Context, input: I) -> Boolean,
  crossinline block: suspend FlowCollector<Event>.(context: Context, input: I) -> O,
): FunctionNode<List<I>, List<O>> =
  parallelNode(
    node =
      node<I, O>(
        name = name,
        description = description,
        rerunOnResume = rerunOnResume,
        waitForOutput = waitForOutput,
        config = config,
        inputSchema = inputSchema,
        outputSchema = outputSchema,
        stateSchema = stateSchema,
        requireConfirmation = requireConfirmation,
        block = block,
      ),
    maxParallelWorkers = maxParallelWorkers,
  )

/**
 * Wraps [node] so it runs concurrently on each item of a list input and returns the results in
 * input order. [node]'s configuration and schemas apply to each item's run; the fan-out node
 * declares no input or output schema.
 *
 * @param maxParallelWorkers Maximum number of items run at a time, or `null` for no limit.
 * @throws WorkflowConfigurationException if [maxParallelWorkers] is less than 1.
 */
@ExperimentalWorkflowApi
inline fun <reified I, reified O> parallelNode(
  node: FunctionNode<I, O>,
  maxParallelWorkers: Int? = null,
): FunctionNode<List<I>, List<O>> =
  FunctionNode(
    name = node.name,
    description = node.description,
    rerunOnResume = true,
    waitForOutput = node.waitForOutput,
    config = NodeConfig(),
    inputSchema = null,
    outputSchema = null,
    stateSchema = node.stateSchema,
    inputType = typeOf<List<I>>(),
    outputType = typeOf<List<O>>(),
    itemNode = node,
    maxParallelWorkers = maxParallelWorkers,
    body = { _, _ -> error("parallelNode dispatches through itemNode") },
  )
