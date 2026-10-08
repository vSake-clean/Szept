package pl.szept.lan

import android.util.Base64
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Bardzo prosty serwer WebSocket (RFC 6455) — tylko to, czego potrzebuje przekaźnik w sieci lokalnej:
 * ramki tekstowe, fragmentacja, ping/pong i zamknięcie. Jeden wątek na połączenie (w sieci lokalnej to kilkadziesiąt osób).
 */
class WsServer(private val wantPort: Int, private val handler: Handler) {
    interface Handler {
        fun onOpen(c: Conn) {}
        fun onText(c: Conn, text: String)
        fun onClose(c: Conn) {}
        /** Odpowiedź na zwykłe zapytanie HTTP (bez WebSocket), np. NIP-11. */
        fun onHttp(path: String, accept: String?): Pair<String, String>? = null
    }

    class Conn internal constructor(private val socket: Socket, val path: String) {
        val id = ids.incrementAndGet()
        val remote: InetAddress? = socket.inetAddress
        val isLoopback get() = remote?.isLoopbackAddress == true
        internal val out = BufferedOutputStream(socket.getOutputStream(), 32 * 1024)
        @Volatile var open = true
            internal set

        fun send(text: String): Boolean = sendFrame(OP_TEXT, text.toByteArray(Charsets.UTF_8))

        internal fun sendFrame(op: Int, data: ByteArray): Boolean = synchronized(out) {
            if (!open) return false
            try { writeFrame(out, op, data); out.flush(); true } catch (e: IOException) { close(); false }
        }

        fun close() {
            if (!open) return
            open = false
            runCatching { synchronized(out) { writeFrame(out, OP_CLOSE, byteArrayOf(0x03, 0xE8.toByte())); out.flush() } }
            runCatching { socket.close() }
        }
    }

    private var server: ServerSocket? = null
    private val conns = ConcurrentHashMap<Int, Conn>()
    @Volatile private var running = false

    val port: Int get() = server?.localPort ?: 0
    val connections: Collection<Conn> get() = conns.values

    /** Uruchamia serwer; gdy port jest zajęty, wybiera dowolny wolny. Zwraca numer portu. */
    @Synchronized
    fun start(): Int {
        if (running) return port
        val s = ServerSocket()
        s.reuseAddress = true
        try { s.bind(InetSocketAddress(wantPort)) } catch (e: IOException) { s.bind(InetSocketAddress(0)) }
        server = s
        running = true
        thread(name = "szept-ws-accept", isDaemon = true) {
            while (running) {
                val sock = try { s.accept() } catch (e: IOException) { break }
                if (conns.size >= MAX_CONNS) { runCatching { sock.close() }; continue }
                thread(name = "szept-ws", isDaemon = true) { serve(sock) }
            }
        }
        return s.localPort
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        conns.values.forEach { it.close() }
        conns.clear()
    }

    private fun serve(sock: Socket) {
        var conn: Conn? = null
        try {
            sock.tcpNoDelay = true
            sock.soTimeout = 15_000
            val input = BufferedInputStream(sock.getInputStream(), 32 * 1024)
            val head = readHead(input) ?: return
            val lines = head.split("\r\n")
            val path = lines.firstOrNull()?.split(' ')?.getOrNull(1) ?: "/"
            val headers = lines.drop(1).mapNotNull { l ->
                val i = l.indexOf(':'); if (i <= 0) null else l.substring(0, i).trim().lowercase() to l.substring(i + 1).trim()
            }.toMap()
            val key = headers["sec-websocket-key"]
            if (key == null || headers["upgrade"]?.lowercase() != "websocket") {
                val (type, body) = handler.onHttp(path, headers["accept"]) ?: ("text/plain; charset=utf-8" to "Szept — przekaźnik w sieci lokalnej\n")
                val bytes = body.toByteArray(Charsets.UTF_8)
                val o = sock.getOutputStream()
                o.write(("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
                    "Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                o.write(bytes); o.flush()
                return
            }
            val accept = Base64.encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII)), Base64.NO_WRAP,
            )
            val c = Conn(sock, path)
            conn = c
            synchronized(c.out) {
                c.out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(Charsets.US_ASCII))
                c.out.flush()
            }
            // klient (OkHttp) pinguje co minutę — po 3 min ciszy uznajemy połączenie za martwe
            sock.soTimeout = 180_000
            conns[c.id] = c
            handler.onOpen(c)
            readLoop(input, c)
        } catch (e: SocketException) {
        } catch (e: EOFException) {
        } catch (e: java.net.SocketTimeoutException) {
        } catch (e: Exception) {
            Log.w("Szept", "lan ws: ${e.message}")
        } finally {
            conn?.let { conns.remove(it.id); it.close(); handler.onClose(it) }
            runCatching { sock.close() }
        }
    }

    private fun readLoop(input: InputStream, c: Conn) {
        val msg = ByteArrayOutputStream()
        var msgOp = -1
        while (c.open) {
            val b0 = input.read(); if (b0 < 0) return
            val b1 = input.read(); if (b1 < 0) return
            val fin = b0 and 0x80 != 0
            val op = b0 and 0x0f
            val masked = b1 and 0x80 != 0
            var len = (b1 and 0x7f).toLong()
            if (len == 126L) len = ((readByte(input) shl 8) or readByte(input)).toLong()
            else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or readByte(input).toLong() } }
            if (len < 0 || len > MAX_MESSAGE) return
            val mask = if (masked) ByteArray(4).also { readFully(input, it) } else null
            val data = ByteArray(len.toInt())
            readFully(input, data)
            if (mask != null) for (i in data.indices) data[i] = (data[i].toInt() xor mask[i and 3].toInt()).toByte()
            when (op) {
                OP_CLOSE -> return
                OP_PING -> c.sendFrame(OP_PONG, data)
                OP_PONG -> {}
                OP_TEXT, OP_BINARY, OP_CONT -> {
                    if (op != OP_CONT) { msg.reset(); msgOp = op }
                    if (msgOp < 0) return
                    msg.write(data)
                    if (msg.size() > MAX_MESSAGE) return
                    if (fin) {
                        if (msgOp == OP_TEXT) handler.onText(c, msg.toString("UTF-8"))
                        msg.reset(); msgOp = -1
                    }
                }
                else -> return
            }
        }
    }

    companion object {
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val OP_CONT = 0x0
        const val OP_TEXT = 0x1
        const val OP_BINARY = 0x2
        const val OP_CLOSE = 0x8
        const val OP_PING = 0x9
        const val OP_PONG = 0xA
        const val MAX_MESSAGE = 1L shl 20
        const val MAX_CONNS = 64
        private val ids = AtomicInteger()

        private fun readByte(input: InputStream): Int { val b = input.read(); if (b < 0) throw EOFException(); return b }

        private fun readFully(input: InputStream, buf: ByteArray) {
            var off = 0
            while (off < buf.size) { val n = input.read(buf, off, buf.size - off); if (n < 0) throw EOFException(); off += n }
        }

        private fun readHead(input: InputStream): String? {
            val sb = StringBuilder()
            while (sb.length < 8192) {
                val b = input.read(); if (b < 0) return null
                sb.append(b.toChar())
                if (sb.endsWith("\r\n\r\n")) return sb.substring(0, sb.length - 4)
            }
            return null
        }

        internal fun writeFrame(out: OutputStream, op: Int, data: ByteArray) {
            out.write(0x80 or op)
            when {
                data.size < 126 -> out.write(data.size)
                data.size < 65536 -> { out.write(126); out.write(data.size ushr 8); out.write(data.size and 0xff) }
                else -> { out.write(127); for (i in 7 downTo 0) out.write(((data.size.toLong() ushr (8 * i)) and 0xff).toInt()) }
            }
            out.write(data)
        }
    }
}
