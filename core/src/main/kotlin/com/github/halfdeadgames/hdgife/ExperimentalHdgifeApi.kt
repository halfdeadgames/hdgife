package com.github.halfdeadgames.hdgife

/**
 * Marks API that may still change in a minor release: text styles and markers, choice requirements and story
 * validation. Using it needs an opt-in, either `@OptIn(ExperimentalHdgifeApi::class)` at the use site or the compiler
 * option `-opt-in=com.github.halfdeadgames.hdgife.ExperimentalHdgifeApi` for the whole module.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This hdgife API is experimental and may change. Opt in with @OptIn(ExperimentalHdgifeApi::class).",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
)
public annotation class ExperimentalHdgifeApi
