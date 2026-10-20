package com.github.halfdeadgames.hdgife.ktx

import com.badlogic.gdx.files.FileHandle
import com.bladecoder.ink.runtime.HdgifeStory
import com.github.halfdeadgames.hdgife.compiler.InkCompilerService
import com.github.halfdeadgames.hdgife.compiler.InkSource
import com.github.halfdeadgames.hdgife.compiler.compile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

public suspend fun InkCompilerService.compileAsync(
    sources: List<InkSource>,
    dispatcher: CoroutineContext = Dispatchers.Default,
): HdgifeStory = withContext(dispatcher) { compile(sources) }

public suspend fun InkCompilerService.compileAsync(
    main: FileHandle,
    extras: List<FileHandle> = emptyList(),
    dispatcher: CoroutineContext = Dispatchers.Default,
): HdgifeStory = withContext(dispatcher) { compile(main, extras) }

public suspend fun InkCompilerService.loadCompiledAsync(
    json: String,
    dispatcher: CoroutineContext = Dispatchers.Default,
): HdgifeStory = withContext(dispatcher) { loadCompiled(json) }
