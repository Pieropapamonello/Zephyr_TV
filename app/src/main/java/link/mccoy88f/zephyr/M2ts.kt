package link.mccoy88f.zephyr

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * A Blu-ray's MPEG-TS (.m2ts, as many films' remuxes are): packets of 192 bytes, a time of 4 bytes before each TS
 * packet of 188, which Media3 can't read. Read through this the times are left out, and the player sees plain MPEG-TS:
 * its byte n·188 + k is the file's n·192 + 4 + k, so seeking and the length work as for any TS file.
 */
@OptIn(UnstableApi::class)
internal class M2ts(private val upstream: DataSource) : DataSource {
    private var at = 0 // where in its 192-byte packet the file is read
    private val time = ByteArray(TIME)

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = M2ts(upstream.createDataSource())
    }

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val from = file(dataSpec.position)
        val length = if (dataSpec.length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong()
            else file(dataSpec.position + dataSpec.length - 1) + 1 - from
        at = (from % PACKET).toInt()
        val got = upstream.open(dataSpec.buildUpon().setPosition(from).setLength(length).build())
        return if (got == C.LENGTH_UNSET.toLong()) got else ts(from + got) - dataSpec.position
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (at < TIME) {
            val r = upstream.read(time, 0, TIME - at)
            if (r == C.RESULT_END_OF_INPUT) return r
            at += r
        }
        val r = upstream.read(buffer, offset, minOf(length, PACKET - at))
        if (r > 0) at = (at + r) % PACKET
        return r
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()

    companion object {
        private const val PACKET = 192
        private const val TIME = 4
        private const val TS = PACKET - TIME

        /** The file's byte for the player's [ts]. */
        fun file(ts: Long) = ts / TS * PACKET + TIME + ts % TS

        /** The player's bytes in the file's first [file] (the times left out). */
        fun ts(file: Long) = file / PACKET * TS + maxOf(0L, file % PACKET - TIME)
    }
}
