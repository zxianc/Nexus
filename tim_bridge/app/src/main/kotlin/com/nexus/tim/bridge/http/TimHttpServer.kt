package com.nexus.tim.bridge.http

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.IOException

class TimHttpServer(
    private val router: TimHttpRouter,
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
        val result = router.handle(
            method = session.method.name,
            path = session.uri,
            query = query,
            body = body,
        )
        val status = Response.Status.lookup(result.status) ?: Response.Status.INTERNAL_ERROR
        return newFixedLengthResponse(
            status,
            "application/json; charset=utf-8",
            result.json?.toString() ?: """{"ok":false,"error":"empty"}""",
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
        const val DEFAULT_PORT = 8788
        private const val TAG = "TimBridgeHttp"
    }
}
