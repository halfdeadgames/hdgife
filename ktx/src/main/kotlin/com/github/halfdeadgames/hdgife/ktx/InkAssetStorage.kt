package com.github.halfdeadgames.hdgife.ktx

import com.bladecoder.ink.runtime.HdgifeStory
import com.github.halfdeadgames.hdgife.compiler.InkCompilerService
import ktx.assets.async.AssetStorage

public fun AssetStorage.registerInkLoaders(compiler: InkCompilerService = InkCompilerService()) {
    setLoader<HdgifeStory>(inkSourceSuffix) { InkStoryLoader(fileResolver, compiler) }
    setLoader<HdgifeStory>(inkCompiledSuffix) { InkStoryLoader(fileResolver, compiler) }
}

public suspend fun AssetStorage.loadInkStory(path: String): HdgifeStory {
    if (getLoader<HdgifeStory>(path) == null) registerInkLoaders()
    return load<HdgifeStory>(path)
}
