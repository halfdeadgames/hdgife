package com.github.halfdeadgames.hdgife.ktx

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.assets.loaders.FileHandleResolver
import com.badlogic.gdx.files.FileHandle
import com.bladecoder.ink.runtime.HdgifeStory
import com.github.halfdeadgames.hdgife.compiler.InkCompilerService
import com.github.halfdeadgames.hdgife.compiler.InkSource
import com.github.halfdeadgames.hdgife.story.StoryManager
import java.lang.reflect.Proxy
import java.nio.file.Files

class RecordedApplication {
    val logged = ArrayList<String>()

    private val application: Application = Proxy.newProxyInstance(
        Application::class.java.classLoader,
        arrayOf(Application::class.java),
    ) { _, method, arguments ->
        when (method.name) {
            "getLogLevel" -> Application.LOG_INFO
            "log" -> {
                logged += arguments[0].toString() + ": " + arguments[1]
                null
            }
            "postRunnable" -> {
                (arguments[0] as Runnable).run()
                null
            }
            else -> null
        }
    } as Application

    fun install() {
        Gdx.app = application
    }

    fun uninstall() {
        Gdx.app = null
    }
}

const val eventStoryText = """
INCLUDE hdgife.ink
VAR hp = 10
Start
~ emit1("giveItem", "sword")
~ emit("ping")
-> END
"""

fun compileStory(text: String = eventStoryText): HdgifeStory = InkCompilerService().compile(InkSource("main.ink", text))

fun managerOf(text: String = eventStoryText): StoryManager = StoryManager(compileStory(text))

fun inkFile(name: String, text: String): FileHandle {
    val directory = Files.createTempDirectory("hdgife-ktx")
    val path = directory.resolve(name)
    Files.writeString(path, text)
    path.toFile().deleteOnExit()
    directory.toFile().deleteOnExit()
    return FileHandle(path.toFile())
}

val pathResolver = FileHandleResolver { path -> FileHandle(path) }
