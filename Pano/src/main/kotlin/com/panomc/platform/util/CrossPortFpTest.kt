package com.panomc.platform.util
import java.io.File
fun main(args: Array<String>) {
    val dir = args.firstOrNull() ?: error("usage: CrossPortFpTest <dir>")
    println(HashUtil.computeStableFileFingerprint(File(dir)))
}
