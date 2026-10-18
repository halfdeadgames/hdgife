package com.github.halfdeadgames.hdgife.harness

import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.utils.reflect.ClassReflection
import com.badlogic.gdx.utils.reflect.ReflectionException
import com.strongjoshua.console.GUIConsole
import com.strongjoshua.console.LogLevel

/**
 * The console for the browser build. sjInGameConsole runs a command with `Method.invoke(exec, null)` when it has no
 * arguments. A JVM accepts that, but TeaVM's `Method.invoke` throws a NullPointerException on a null argument array,
 * so every command without arguments (`vars`, `help`, `restart`) would crash the page. This subclass runs the same
 * lookup (libGDX `ClassReflection`, name ignoring case, matching argument count) and always passes an array.
 * The desktop and Android builds keep the library's own [GUIConsole].
 */
class BrowserConsole(skin: Skin, keyId: Int) : GUIConsole(skin, false, keyId) {

    override fun execCommand(command: String) {
        if (disabled) return
        log(command, LogLevel.COMMAND)
        val parts = command.split(" ")
        val name = parts[0]
        val values = parts.drop(1)
        val candidates = ClassReflection.getMethods(exec.javaClass).filter { it.name.equals(name, ignoreCase = true) }
        if (candidates.isEmpty()) {
            log("No such method found.", LogLevel.ERROR)
            return
        }
        for (method in candidates) {
            val types = method.parameterTypes
            if (types.size != values.size) continue
            val args = arrayOfNulls<Any>(types.size)
            try {
                for (i in types.indices) args[i] = convert(types[i], values[i])
            } catch (e: NumberFormatException) {
                continue // not this overload, try the next one
            }
            try {
                method.setAccessible(true)
                method.invoke(exec, *args)
            } catch (e: ReflectionException) {
                log(e.message?.takeIf { it.isNotEmpty() } ?: "Unknown Error", LogLevel.ERROR)
            }
            return
        }
        log("Bad parameters. Check your code.", LogLevel.ERROR)
    }

    /** The parameter types the library supports: String and the primitives (and their boxes). */
    private fun convert(type: Class<*>, text: String): Any? = when (type) {
        String::class.java -> text
        Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> text.toBoolean()
        Int::class.javaPrimitiveType, Int::class.javaObjectType -> text.toInt()
        Long::class.javaPrimitiveType, Long::class.javaObjectType -> text.toLong()
        Float::class.javaPrimitiveType, Float::class.javaObjectType -> text.toFloat()
        Double::class.javaPrimitiveType, Double::class.javaObjectType -> text.toDouble()
        Short::class.javaPrimitiveType, Short::class.javaObjectType -> text.toShort()
        Byte::class.javaPrimitiveType, Byte::class.javaObjectType -> text.toByte()
        else -> null
    }
}
