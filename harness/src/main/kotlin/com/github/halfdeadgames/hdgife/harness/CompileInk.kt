@file:JvmName("CompileInk")

package com.github.halfdeadgames.hdgife.harness

import com.badlogic.gdx.files.FileHandle
import com.github.halfdeadgames.hdgife.compiler.InkCompilerService
import com.github.halfdeadgames.hdgife.compiler.compile
import java.io.File

/**
 * Build-time tool behind `:harness:compileInk`: compiles `<inkDir>/main.ink` and writes the story JSON to the given file.
 * It runs on a plain JVM without libGDX started. A `FileHandle` made from a `java.io.File` needs no `Gdx.files`.
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "usage: CompileInk <inkDir> <outFile>" }
    val story = InkCompilerService().compile(FileHandle(File(args[0], "main.ink")))
    val out = File(args[1])
    out.parentFile.mkdirs()
    out.writeText(story.toJson(), Charsets.UTF_8)
    println("Wrote ${out.path} (${out.length()} bytes)")
}
