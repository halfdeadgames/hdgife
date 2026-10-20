package com.github.halfdeadgames.hdgife

import com.bladecoder.ink.runtime.HdgifeStory
import com.bladecoder.ink.runtime.Story
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UpstreamShapeTest {
    private val pkg = "com.bladecoder.ink.runtime"

    private fun type(name: String): Class<*> = Class.forName("$pkg.$name")

    private fun Class<*>.declared(name: String, vararg params: Class<*>): Method =
        assertNotNull(
            runCatching { getDeclaredMethod(name, *params) }.getOrNull(),
            "${this.simpleName}.$name(${params.joinToString { it.simpleName }}) is gone: revisit HdgifeStory/InkNativePatch",
        )

    @Test
    fun `the blade ink on the classpath is the pinned version`() {
        val expected = System.getProperty("hdgife.bladeInkVersion")
        assertNotNull(expected, "the build passes hdgife.bladeInkVersion to the tests")
        val location = Story::class.java.protectionDomain.codeSource.location.toString()
        assertTrue("blade-ink-$expected" in location, "classpath has $location, the build pins $expected")
    }

    @Test
    fun `Story callExternalFunction is still package private with the signature HdgifeStory overrides`() {
        val upstream = Story::class.java.declared("callExternalFunction", String::class.java, Int::class.javaPrimitiveType!!)
        val modifiers = upstream.modifiers
        assertFalse(Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers) || Modifier.isPrivate(modifiers), "no longer package private")
        assertFalse(Modifier.isFinal(modifiers) || Modifier.isStatic(modifiers), "no longer overridable")
        assertEquals(Void.TYPE, upstream.returnType)
        val ours = HdgifeStory::class.java.declared("callExternalFunction", String::class.java, Int::class.javaPrimitiveType!!)
        assertEquals(Void.TYPE, ours.returnType)
        assertEquals(Story::class.java.`package`, HdgifeStory::class.java.`package`)
    }

    @Test
    fun `the public methods HdgifeStory overrides are still overridable`() {
        val bind = Story::class.java.declared("bindExternalFunction", String::class.java, Story.ExternalFunction::class.java, Boolean::class.javaPrimitiveType!!)
        val unbind = Story::class.java.declared("unbindExternalFunction", String::class.java)
        for (method in listOf(bind, unbind)) {
            assertTrue(Modifier.isPublic(method.modifiers) && !Modifier.isFinal(method.modifiers), "${method.name} is not overridable")
        }
        Story::class.java.declared("getExternalFunction", String::class.java)
    }

    @Test
    fun `the story state members HdgifeStory reads are still there`() {
        val state = type("StoryState")
        assertEquals(java.lang.Boolean.TYPE, state.declared("inStringEvaluation").returnType)
        assertEquals(String::class.java, state.declared("getCurrentText").returnType)
        assertEquals(type("RTObject"), state.declared("popEvaluationStack").returnType)
        state.declared("pushEvaluationStack", type("RTObject"))
    }

    @Test
    fun `the native function table InkNativePatch registers into is still there`() {
        val native = type("NativeFunctionCall")
        val unaryOp = type("NativeFunctionCall\$UnaryOp")
        for (name in listOf("addFloatUnaryOp")) {
            val method = native.declared(name, String::class.java, unaryOp)
            assertTrue(Modifier.isStatic(method.modifiers))
            assertFalse(Modifier.isPublic(method.modifiers) || Modifier.isPrivate(method.modifiers), "$name changed visibility")
        }
        val generate = native.declared("generateNativeFunctionsIfNecessary")
        assertTrue(Modifier.isStatic(generate.modifiers))
        for (field in listOf("Floor", "Ceiling", "Int")) {
            val nativeField = native.getDeclaredField(field)
            assertTrue(Modifier.isStatic(nativeField.modifiers) && nativeField.type == String::class.java, "$field changed")
        }
        assertTrue(unaryOp.isInterface)
        assertEquals(Any::class.java, unaryOp.declared("invoke", Any::class.java).returnType)
    }
}
