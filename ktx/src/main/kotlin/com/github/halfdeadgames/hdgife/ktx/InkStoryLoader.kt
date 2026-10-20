package com.github.halfdeadgames.hdgife.ktx

import com.badlogic.gdx.assets.AssetDescriptor
import com.badlogic.gdx.assets.AssetLoaderParameters
import com.badlogic.gdx.assets.AssetManager
import com.badlogic.gdx.assets.loaders.AsynchronousAssetLoader
import com.badlogic.gdx.assets.loaders.FileHandleResolver
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.utils.Array
import com.bladecoder.ink.runtime.HdgifeStory
import com.github.halfdeadgames.hdgife.compiler.InkCompilerService
import com.github.halfdeadgames.hdgife.compiler.InkSource
import com.github.halfdeadgames.hdgife.compiler.of
import java.util.concurrent.ConcurrentHashMap

public const val inkSourceSuffix: String = ".ink"

public const val inkCompiledSuffix: String = ".ink.json"

public class InkStoryLoader @JvmOverloads constructor(
    private val resolver: FileHandleResolver,
    private val compiler: InkCompilerService = InkCompilerService(),
) : AsynchronousAssetLoader<HdgifeStory, AssetLoaderParameters<HdgifeStory>>(resolver) {
    private val loadedAsync = ConcurrentHashMap<String, HdgifeStory>()

    override fun loadAsync(manager: AssetManager, fileName: String, file: FileHandle, parameter: AssetLoaderParameters<HdgifeStory>?) {
        loadedAsync.remove(fileName)
        loadedAsync[fileName] = if (fileName.endsWith(inkCompiledSuffix)) {
            compiler.loadCompiled(file.readString("UTF-8"))
        } else {
            compiler.compile(InkSource.of(fileName, resolver))
        }
    }

    // AssetStorage never calls this hook; if it cancels between loadAsync and loadSync, the entry stays until the next load of that file replaces it.
    override fun unloadAsync(manager: AssetManager, fileName: String, file: FileHandle, parameter: AssetLoaderParameters<HdgifeStory>?) {
        loadedAsync.remove(fileName)
    }

    override fun loadSync(manager: AssetManager, fileName: String, file: FileHandle, parameter: AssetLoaderParameters<HdgifeStory>?): HdgifeStory =
        loadedAsync.remove(fileName) ?: error("'$fileName' was not loaded before loadSync")

    override fun getDependencies(
        fileName: String,
        file: FileHandle,
        parameter: AssetLoaderParameters<HdgifeStory>?,
    ): Array<AssetDescriptor<*>>? = null
}

public fun AssetManager.registerInkLoaders(compiler: InkCompilerService = InkCompilerService()) {
    val loader = InkStoryLoader(fileHandleResolver, compiler)
    setLoader(HdgifeStory::class.java, inkSourceSuffix, loader)
    setLoader(HdgifeStory::class.java, inkCompiledSuffix, loader)
}
