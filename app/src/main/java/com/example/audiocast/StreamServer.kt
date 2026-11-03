package com.example.audiocast

import android.content.Context
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Server HTTP + WebSocket minimal tanpa library.
 * GET /   -> halaman player (assets/index.html)
 * GET /ws -> WebSocket, kirim PCM 16-bit interleaved sebagai frame binary
 */
class StreamServer(
    private val ctx: Context,
    private val port: Int,
    private val rate: Int,
    private val channels: Int
) {
    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<WsClient>()
    @Volatile private var alive = false

    fun start() {
        alive = true
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        serverSocket = ss
        thread(name = "accept") {
            while (alive) {
                try {
                    val s = ss.accept()
                    thread(name = "conn") { handle(s) }
                } catch (_: IOException) {
                    break
                }
            }
        }
    }

    fun stop() {
        alive = false
        try { serverSocket?.close() } catch (_: Exception) {}
        clients.forEach { it.close() }
        clients.clear()
        CastService.clientCount = 0
    }

    fun broadcast(data: ByteArray) {
        for (c in clients) c.offer(data)
    }

    private fun handle(s: Socket) {
        var keep = false
        try {
            s.tcpNoDelay = true
            val input = s.getInputStream()
            val out = s.getOutputStream()
            val requestLine = readLine(input) ?: return
            val headers = HashMap<String, String>()
            while (true) {
                val l = readLine(input) ?: break
                if (l.isEmpty()) break
                val i = l.indexOf(':')
                if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            }
            val path = requestLine.split(" ").getOrNull(1) ?: "/"

            if (headers["upgrade"]?.lowercase() == "websocket" && headers["sec-websocket-key"] != null) {
                keep = true
                wsSession(s, input, out, headers["sec-websocket-key"]!!)
                return
            }

            if (path == "/" || path.startsWith("/?") || path == "/index.html") {
                val body = ctx.assets.open("index.html").use { it.readBytes() }
                writeHttp(out, "200 OK", "text/html; charset=utf-8", body)
            } else {
                writeHttp(out, "404 Not Found", "text/plain; charset=utf-8", "Not found".toByteArray())
            }
        } catch (_: Exception) {
        } finally {
            if (!keep) try { s.close() } catch (_: Exception) {}
        }
    }

    private fun wsSession(s: Socket, input: InputStream, out: OutputStream, key: String) {
        val sha = MessageDigest.getInstance("SHA-1")
            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
        val accept = Base64.getEncoder().encodeToString(sha)
        out.write(
            ("HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray()
        )
        out.flush()

        val client = WsClient(s, out)
        client.sendNow(0x1, """{"rate":$rate,"ch":$channels}""".toByteArray())
        client.startWriter()
        clients.add(client)
        CastService.clientCount = clients.size
        try {
            // Baca frame dari browser hanya buat deteksi close.
            while (true) {
                val b0 = input.read()
                if (b0 < 0) break
                val b1 = input.read()
                if (b1 < 0) break
                val opcode = b0 and 0x0F
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) {
                    len = ((input.read() shl 8) or input.read()).toLong()
                } else if (len == 127L) {
                    len = 0
                    repeat(8) { len = (len shl 8) or input.read().toLong() }
                }
                if (b1 and 0x80 != 0) skipFully(input, 4)
                skipFully(input, len)
                if (opcode == 0x8) break
            }
        } catch (_: Exception) {
        } finally {
            clients.remove(client)
            client.close()
            CastService.clientCount = clients.size
        }
    }

    private fun skipFully(i: InputStream, n: Long) {
        var left = n
        val tmp = ByteArray(1024)
        while (left > 0) {
            val r = i.read(tmp, 0, minOf(left, tmp.size.toLong()).toInt())
            if (r < 0) throw IOException("eof")
            left -= r
        }
    }

    private fun readLine(i: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
        return sb.toString()
    }

    private fun writeHttp(out: OutputStream, status: String, type: String, body: ByteArray) {
        out.write(
            ("HTTP/1.1 $status\r\n" +
                "Content-Type: $type\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n").toByteArray()
        )
        out.write(body)
        out.flush()
    }

    /** Satu koneksi WebSocket dengan antrean kecil. Kalau iPhone lambat, chunk lama dibuang biar delay tidak numpuk. */
    private class WsClient(private val socket: Socket, private val out: OutputStream) {
        private val queue = ArrayBlockingQueue<ByteArray>(32)
        @Volatile private var open = true
        private var writer: Thread? = null

        fun offer(d: ByteArray) {
            if (!open) return
            if (!queue.offer(d)) {
                queue.poll()
                queue.offer(d)
            }
        }

        fun startWriter() {
            writer = thread(name = "ws-writer") {
                try {
                    while (open) {
                        val d = queue.take()
                        sendNow(0x2, d)
                    }
                } catch (_: Exception) {
                    close()
                }
            }
        }

        @Synchronized
        fun sendNow(opcode: Int, payload: ByteArray) {
            val n = payload.size
            val header: ByteArray = when {
                n < 126 -> byteArrayOf((0x80 or opcode).toByte(), n.toByte())
                n < 65536 -> byteArrayOf(
                    (0x80 or opcode).toByte(), 126.toByte(), (n shr 8).toByte(), n.toByte()
                )
                else -> {
                    val h = ByteArray(10)
                    h[0] = (0x80 or opcode).toByte()
                    h[1] = 127.toByte()
                    for (k in 0 until 8) h[2 + k] = ((n.toLong() shr (8 * (7 - k))) and 0xFF).toByte()
                    h
                }
            }
            val frame = ByteArray(header.size + n)
            System.arraycopy(header, 0, frame, 0, header.size)
            System.arraycopy(payload, 0, frame, header.size, n)
            out.write(frame)
            out.flush()
        }

        fun close() {
            open = false
            try { socket.close() } catch (_: Exception) {}
            writer?.interrupt()
        }
    }
}
