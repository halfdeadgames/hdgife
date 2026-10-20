package com.bladecoder.ink.runtime

// Blade Ink 1.3.2 casts the boxed Float argument of FLOOR, CEILING and INT with (double) and (int), which always throws
// ClassCastException. NativeFunctionCall has a package-private registration for these ops, so this file re-registers
// correct ones from inside Blade Ink's package. FLOOR and CEILING give a float, INT truncates toward zero.
internal object InkNativePatch {
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
