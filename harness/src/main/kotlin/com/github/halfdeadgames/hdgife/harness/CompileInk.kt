@file:JvmName("CompileInk")

package com.github.halfdeadgames.hdgife.harness

import com.badlogic.gdx.files.FileHandle
import com.github.halfdeadgames.hdgife.compiler.InkCompilerService
import com.github.halfdeadgames.hdgife.compiler.compile
import java.io.File

fun main(args: Array<String>) {
    require(args.size == 2) { "usage: CompileInk <inkDir> <outFile>" }
    val story = InkCompilerService().compile(FileHandle(File(args[0], "main.ink")))
    val outFile = File(args[1])
    outFile.parentFile.mkdirs()
    outFile.writeText(story.toJson(), Charsets.UTF_8)
    println("Wrote ${outFile.path} (${outFile.length()} bytes)")
}
