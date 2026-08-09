package com.nexus.wechat.bridge.http

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.IOException

class BridgeHttpServer(
    private val router: BridgeHttpRouter,
    hostname: String = "0.0.0.0",
    port: Int = DEFAULT_PORT,
) : NanoHTTPD(hostname, port) {

    @Throws(IOException::class)
    override fun start() {
        start(SOCKET_READ_TIMEOUT, false)
        Log.i(TAG, "HTTP listening on $hostname:$listeningPort")
    }

    override fun serve(session: IHTTPSession): Response {
        val body = readBody(session)
        val query = HashMap<String, String>()
        session.parms?.let { query.putAll(it) }
        val result = router.handle(session.method.name, session.uri, query, body)
        return newFixedLengthResponse(
            Response.Status.lookup(result.status) ?: Response.Status.INTERNAL_ERROR,
            result.contentType,
            result.json.toString(),
        )
    }

    private fun readBody(session: IHTTPSession): ByteArray? {
        val len = session.headers["content-length"]?.toIntOrNull() ?: return null
        if (len <= 0) return null
        val buf = ByteArray(len)
        var off = 0
        val input = session.inputStream
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) break
            off += n
        }
        return buf.copyOf(off)
    }

    companion object {
        const val DEFAULT_PORT = 8787
        private const val TAG = "WeChatBridgeHttp"
    }
}
