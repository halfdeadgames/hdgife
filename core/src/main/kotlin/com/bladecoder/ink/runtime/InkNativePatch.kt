package com.bladecoder.ink.runtime

/**
 * Fixes FLOOR, CEILING and INT on floats in Blade Ink 1.3.2.
 *
 * Cause: the float versions in NativeFunctionCall cast the boxed argument with `(double) val` and `(int) val`.
 * Ink floats are boxed `Float`, and an `Object` holding a Float can't be cast to Double or Integer, so every
 * call throws ClassCastException (even `FLOOR(2.5)`).
 *
 * Fix: ink looks these operations up in one shared table and the table has a package-private registration
 * method meant for exactly this. This file sits in Blade Ink's own package to call it and registers correct
 * versions. No reflection. Stories that are already compiled use the table too, so they are fixed as well.
 * Semantics follow ink: FLOOR and CEILING give a float, INT truncates toward zero and gives an int.
 * Safe to call any number of times, and harmless once Blade Ink fixes this itself.
 */
object InkNativePatch {
    private var applied = false

    @JvmStatic
    @Synchronized
    fun apply() {
        if (applied) return
        NativeFunctionCall.generateNativeFunctionsIfNecessary()
        NativeFunctionCall.addFloatUnaryOp(NativeFunctionCall.Floor, object : NativeFunctionCall.UnaryOp {
            override fun invoke(value: Any?): Any = kotlin.math.floor((value as Number).toFloat())
        })
        NativeFunctionCall.addFloatUnaryOp(NativeFunctionCall.Ceiling, object : NativeFunctionCall.UnaryOp {
            override fun invoke(value: Any?): Any = kotlin.math.ceil((value as Number).toFloat())
        })
        NativeFunctionCall.addFloatUnaryOp(NativeFunctionCall.Int, object : NativeFunctionCall.UnaryOp {
            override fun invoke(value: Any?): Any = (value as Number).toInt()
        })
        applied = true
    }
}
