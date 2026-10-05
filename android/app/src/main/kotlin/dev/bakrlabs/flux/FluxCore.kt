package dev.bakrlabs.flux

object FluxCore {
    init {
        System.loadLibrary("flux_android")
    }

    external fun localIp(): String
    external fun bindReceiver(): Int
    external fun receiveOne(outDir: String): String
    external fun sendFile(addr: String, path: String): Long
}
