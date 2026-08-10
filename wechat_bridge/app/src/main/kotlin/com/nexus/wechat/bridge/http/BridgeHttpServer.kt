package com.nexus.wechat.bridge.http

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.File
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
        val contentType = session.headers["content-type"].orEmpty()
        val form = HashMap<String, String>()
        val files = HashMap<String, File>()
        var body: ByteArray? = null

        if (contentType.contains("multipart/form-data", ignoreCase = true)) {
            val fileMap = HashMap<String, String>()
            try {
                session.parseBody(fileMap)
            } catch (e: Exception) {
                Log.w(TAG, "multipart parse failed", e)
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json; charset=utf-8",
                    """{"ok":false,"error":"multipart_parse_failed"}""",
                )
            }
            session.parms?.let { form.putAll(it) }
            for ((key, path) in fileMap) {
                files[key] = File(path)
            }
        } else {
            body = readBody(session)
            session.parms?.let { form.putAll(it) }
        }

        val query = HashMap<String, String>()
        session.parms?.let { query.putAll(it) }
        val headers = HashMap<String, String>()
        session.headers?.let { headers.putAll(it) }
        val result = router.handle(
            method = session.method.name,
            path = session.uri,
            query = query,
            body = body,
            form = form,
            files = files,
            headers = headers,
        )
        return toResponse(result)
    }

    private fun toResponse(result: RouterResponse): Response {
        val status = Response.Status.lookup(result.status) ?: Response.Status.INTERNAL_ERROR
        if (result.bytes != null) {
            val stream = ByteArrayInputStream(result.bytes)
            val resp = newFixedLengthResponse(
                status,
                result.contentType,
                stream,
                result.bytes.size.toLong(),
            )
            val name = result.fileName
            if (!name.isNullOrEmpty()) {
                resp.addHeader("Content-Disposition", "attachment; filename=\"$name\"")
            }
            return resp
        }
        return newFixedLengthResponse(
            status,
            result.contentType,
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
        const val DEFAULT_PORT = 8787
        private const val TAG = "WeChatBridgeHttp"
    }
}
