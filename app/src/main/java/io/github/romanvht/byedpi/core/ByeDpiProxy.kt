package io.github.romanvht.byedpi.core
/** JNI class name is fixed in upstream ByeByeDPI GPLv3 native code. */
class ByeDpiProxy {
    companion object { init { System.loadLibrary("byedpi") } }
    fun startProxy(args: Array<String>): Int = jniStartProxy(args)
    fun stopProxy(): Int = jniStopProxy()
    private external fun jniStartProxy(args: Array<String>): Int
    private external fun jniStopProxy(): Int
}
