package com.github.halfdeadgames.hdgife

@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
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
