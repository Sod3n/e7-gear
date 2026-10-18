package e7.gearscan

import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Minimal SOCKS5 (CONNECT only) server for hev-socks5-tunnel. Game connections to the
 * login ports are copied into Capture as they pass through.
 *
 * upstream: optional "host:port" of another SOCKS5 proxy (e.g. a VPN app in proxy mode).
 */
class CaptureProxy(private val upstream: String) {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    @Volatile private var running = true

    val port: Int get() = server.localPort

    fun start() {
        thread(isDaemon = true, name = "socks-accept") {
            while (running) {
                val client = try { server.accept() } catch (e: IOException) { continue }
                thread(isDaemon = true, name = "socks-conn") { handle(client) }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { server.close() }
    }

    private fun handle(client: Socket) {
        var remote: Socket? = null
        try {
            client.tcpNoDelay = true
            val input = DataInputStream(client.getInputStream())
            val out = client.getOutputStream()

            // greeting: VER NMETHODS METHODS...; we only offer "no auth"
            if (input.readUnsignedByte() != 5) return
            input.skipBytes(input.readUnsignedByte())
            out.write(byteArrayOf(5, 0))

            // request: VER CMD RSV ATYP DST.ADDR DST.PORT
            val head = ByteArray(4).also { input.readFully(it) }
            if (head[1].toInt() != 1) {
                out.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0))
                return
            }
            val atyp = head[3].toInt()
            val addr = when (atyp) {
                1 -> ByteArray(4)
                4 -> ByteArray(16)
                3 -> ByteArray(input.readUnsignedByte())
                else -> return
            }.also { input.readFully(it) }
            val host = if (atyp == 3) String(addr, Charsets.US_ASCII) else InetAddress.getByAddress(addr).hostAddress!!
            val port = input.readUnsignedShort()

            remote = try {
                if (upstream.isEmpty()) Socket().apply { connect(InetSocketAddress(host, port), 15000) }
                else connectUpstream(atyp, addr, port)
            } catch (e: IOException) {
                Capture.log("port $port connect failed${if (upstream.isEmpty()) "" else " via proxy"}: ${e.message}")
                throw e
            }
            remote.tcpNoDelay = true
            out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))

            val rec = if (port in CAPTURE_PORTS) Capture.open() else null
            if (rec != null) Capture.log("port $port opened${if (upstream.isEmpty()) "" else " via proxy"}")
            val up = thread(isDaemon = true, name = "socks-up") { pipe(client, remote, rec, Capture.DIR_C2S) }
            pipe(remote, client, rec, Capture.DIR_S2C)
            up.join()
            if (rec != null) Capture.log("port $port closed, ${rec.bytes()} bytes recorded")
        } catch (e: IOException) {
        } finally {
            runCatching { client.close() }
            runCatching { remote?.close() }
        }
    }

    private fun connectUpstream(atyp: Int, addr: ByteArray, port: Int): Socket {
        val (uHost, uPort) = upstream.substringBeforeLast(':') to upstream.substringAfterLast(':').toInt()
        val s = Socket().apply { connect(InetSocketAddress(uHost, uPort), 15000) }
        val input = DataInputStream(s.getInputStream())
        val out = s.getOutputStream()
        out.write(byteArrayOf(5, 1, 0))
        if (ByteArray(2).also { input.readFully(it) }.contentEquals(byteArrayOf(5, 0)).not())
            throw IOException("upstream refused")
        val lenPrefix = if (atyp == 3) byteArrayOf(addr.size.toByte()) else byteArrayOf()
        out.write(byteArrayOf(5, 1, 0, atyp.toByte()) + lenPrefix + addr +
                  byteArrayOf((port shr 8).toByte(), port.toByte()))
        val rep = ByteArray(4).also { input.readFully(it) }
        if (rep[1].toInt() != 0) throw IOException("upstream connect failed ${rep[1]}")
        val skip = when (rep[3].toInt()) { 1 -> 4; 4 -> 16; else -> input.readUnsignedByte() }
        input.skipBytes(skip + 2)
        return s
    }

    private fun pipe(from: Socket, to: Socket, rec: Capture.Conn?, dir: Int) {
        val buf = ByteArray(65536)
        try {
            val input = from.getInputStream()
            val out = to.getOutputStream()
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                rec?.write(dir, buf, n)
                out.write(buf, 0, n)
            }
        } catch (e: IOException) {
        } finally {
            runCatching { to.shutdownOutput() }
        }
    }

    companion object {
        val CAPTURE_PORTS = setOf(3333, 5222)
    }
}
