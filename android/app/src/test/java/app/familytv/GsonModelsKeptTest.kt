package app.familytv

import java.io.File
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 20: release builds turned SharedProgressPage, ScoreBoard and the Rust app's handover into other classes, so
 * Gson threw on every progress pull. Every class handed to Gson by `X::class.java`, and every app class its fields
 * hold, must be kept whole by proguard-rules.pro.
 */
class GsonModelsKeptTest {
    private val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
    private val keeps = File("proguard-rules.pro").readLines()
        .mapNotNull { Regex("""^-keep class (\S+) \{ \*; \}""").find(it.trim())?.groupValues?.get(1) }
        .map { p -> Regex(p.split("**").joinToString(".*") { part -> part.split("*").joinToString("[^.]*") { Regex.escape(it) } }) }

    @Test
    fun everyGsonModelIsKeptWhole() {
        val roots = sources.filter { "fromJson(" in it.readText() }.flatMap(::modelsIn).toSet()
        assertTrue("no Gson models found", roots.isNotEmpty())
        val all = mutableSetOf<Class<*>>()
        roots.forEach { collect(it, all) }
        val loose = all.map { it.name }.filter { name -> keeps.none { it.matches(name) } }.sorted()
        assertTrue("Add a -keep rule to proguard-rules.pro for: $loose", loose.isEmpty())
    }

    /** The app's classes [file] names as `X::class.java` or `Array<X>::class.java`. */
    private fun modelsIn(file: File): List<Class<*>> {
        val text = file.readText()
        val pkg = Regex("""^package (\S+)""", RegexOption.MULTILINE).find(text)!!.groupValues[1]
        val imports = Regex("""^import (\S+)""", RegexOption.MULTILINE).findAll(text).map { it.groupValues[1] }.toList()
        val outers = Regex("""^(?:\w+ )*(?:class|object|interface) (\w+)""", RegexOption.MULTILINE).findAll(text).map { it.groupValues[1] }.toList()
        return Regex("""(?:Array<)?([A-Z][\w.]*)>?::class\.java""").findAll(text).mapNotNull { m ->
            val name = m.groupValues[1]
            val head = name.substringBefore('.')
            val nested = name.replace('.', '$')
            val candidates = imports.filter { it.endsWith(".$head") }.map { it.removeSuffix(head) + nested } +
                "$pkg.$nested" + outers.map { "$pkg.$it\$$nested" }
            candidates.firstNotNullOfOrNull { runCatching { Class.forName(it) }.getOrNull() }
        }.filter { it.name.startsWith("app.familytv.") }.toList()
    }

    private fun collect(type: Type, into: MutableSet<Class<*>>) {
        when (type) {
            is ParameterizedType -> { collect(type.rawType, into); type.actualTypeArguments.forEach { collect(it, into) } }
            is GenericArrayType -> collect(type.genericComponentType, into)
            is WildcardType -> type.upperBounds.forEach { collect(it, into) }
            is Class<*> -> when {
                type.isArray -> collect(type.componentType!!, into)
                type.name.startsWith("app.familytv.") && into.add(type) ->
                    type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.forEach { collect(it.genericType, into) }
            }
        }
    }
}
