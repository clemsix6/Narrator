package io.github.clemsix6.narrator2.tool

import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.ToolUseBlock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A tool for the tests of the tool framework: [body] is its run, [available] its [isAvailable]. */
class TestTool<A : Any>(
    name: String,
    args: KSerializer<A>,
    effect: ToolEffect = ToolEffect.READ,
    timeoutMs: Long = 5_000,
    description: String = "A test tool.",
    private val available: (ToolEnv) -> Boolean = { true },
    private val body: suspend (ToolContext, A) -> ToolResult = { _, _ -> ToolResult.ok("ok") },
) : NarratorTool<A>(name, description, args, effect, timeoutMs) {
    override fun isAvailable(env: ToolEnv): Boolean = available(env)

    override suspend fun run(ctx: ToolContext, args: A): ToolResult = body(ctx, args)
}

/** A tool without arguments. */
fun noArgsTool(name: String, effect: ToolEffect = ToolEffect.READ, available: (ToolEnv) -> Boolean = { true }) =
    TestTool(name, NoArgs.serializer(), effect, available = available)

/** A `tool_use` block as the SDK reads it from the gateway's stream; [input] is its JSON object. */
fun toolUse(id: String, name: String, input: String = "{}"): ToolUseBlock =
    jsonMapper().readValue("""{"type":"tool_use","id":"$id","name":"$name","input":$input}""", ToolUseBlock::class.java)

@Serializable
enum class Shape {
    @SerialName("small_ball") SMALL_BALL,
    @SerialName("star") STAR,
}

@Serializable
data class Enchant(
    @Doc("Enchantment id, e.g. sharpness.") val id: String,
    @Range(1.0, 10.0) val level: Int = 1,
)

@Serializable
data class Gear(val head: String? = null, val mainhand: String? = null)

/** Every supported argument type and annotation. */
@Serializable
data class EveryTypeArgs(
    @Doc("What to say.") @MaxLen(300) val text: String,
    @Doc("Player names; everyone when left out.") @Items(1, 8) @MaxLen(16) val to: List<String>? = null,
    @Doc("How long, in seconds.") @Range(1.0, 900.0) val seconds: Int,
    @SerialName("task_id") val taskId: Long? = null,
    @Range(0.1, 2.0) val volume: Double = 1.0,
    val particles: Boolean = true,
    val shape: Shape = Shape.STAR,
    @Items(0, 3) val enchantments: List<Enchant> = emptyList(),
    val gear: Gear? = null,
)

fun everyTypeTool(effect: ToolEffect = ToolEffect.WORLD) =
    TestTool("every_type", EveryTypeArgs.serializer(), effect, description = "A tool with every supported argument type.")
