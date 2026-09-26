package com.madtreasures.faceclaw.tools

import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "bake-fonts" -> {
            require(args.size == 4) { "usage: bake-fonts <fontSrcDir> <outDir> <iconsKt>" }
            FontBaker(File(args[1]), File(args[2]), File(args[3])).run()
        }
        else -> {
            System.err.println("usage: tools bake-fonts <fontSrcDir> <outDir> <iconsKt>")
            exitProcess(2)
        }
    }
}
