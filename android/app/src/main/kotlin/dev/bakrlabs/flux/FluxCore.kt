package dev.bakrlabs.flux

object FluxCore {
    init {
        System.loadLibrary("flux_android")
    }

    external fun localIp(): String
    external fun generateKey(): String
    external fun setIdentity(key: String, name: String)
    external fun bindReceiver(): Int
    external fun receiveAccept(): String
    external fun receiveHello(): String
    external fun receiveBody(fd: Int): Long
    external fun receiveReject(code: Int)
    external fun senderConnect(addr: String): String
    external fun senderAbort()
    external fun sendFd(name: String, size: Long, fd: Int): Long
    external fun progressDone(channel: Int): Long
    external fun progressTotal(channel: Int): Long
    external fun progressName(channel: Int): String
    external fun cancel(channel: Int)
    external fun setPaused(channel: Int, paused: Boolean)
    external fun reset(channel: Int)
}
