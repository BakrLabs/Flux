package dev.bakrlabs.flux

object FluxCore {
    init {
        System.loadLibrary("flux_android")
    }

    external fun localIp(): String
    external fun bindReceiver(): Int
    external fun receiveOne(outDir: String): String
    external fun sendFile(addr: String, path: String): Long
    external fun progressDone(channel: Int): Long
    external fun progressTotal(channel: Int): Long
    external fun progressName(channel: Int): String
    external fun cancel(channel: Int)
    external fun setPaused(channel: Int, paused: Boolean)
    external fun reset(channel: Int)
}
