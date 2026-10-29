package link.mccoy88f.zephyr

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaChunkExtractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser

/**
 * HLS whose audio is timed far from its video (some live channels' segments have their audio hours ahead: the
 * player, which keeps time by the audio, waits for it and never starts): the audio moved onto the video's time.
 * Only past [GAP_US], a gap no stream has on purpose, and only as one segment has both (audio and video muxed):
 * audio in segments of its own starts where its own segments do, not with the video's first. The shift, once found,
 * holds for the stream's later segments.
 */
@OptIn(UnstableApi::class, androidx.media3.common.util.ExperimentalApi::class)
internal class HlsAlign(private val inner: HlsExtractorFactory = DefaultHlsExtractorFactory()) : HlsExtractorFactory by inner {
    @Volatile private var shift = 0L // added to the audio's times

    override fun createExtractor(uri: Uri, format: Format, muxedCaptionFormats: List<Format>?, timestampAdjuster: TimestampAdjuster,
                                 responseHeaders: Map<String, List<String>>, sniffingExtractorInput: ExtractorInput,
                                 playerId: PlayerId): HlsMediaChunkExtractor =
        Chunk(inner.createExtractor(uri, format, muxedCaptionFormats, timestampAdjuster, responseHeaders, sniffingExtractorInput, playerId))

    // Its settings go to the factory it wraps, and it stays the one in use.
    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): HlsExtractorFactory =
        also { inner.setSubtitleParserFactory(subtitleParserFactory) }
    override fun experimentalParseSubtitlesDuringExtraction(parseSubtitlesDuringExtraction: Boolean): HlsExtractorFactory =
        also { inner.experimentalParseSubtitlesDuringExtraction(parseSubtitlesDuringExtraction) }
    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies: Int): HlsExtractorFactory =
        also { inner.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies) }

    /** A segment's first video and audio sample: from the two of the same segment, the shift (once). */
    private fun found(firstVideo: Long, firstAudio: Long) {
        if (shift == 0L && kotlin.math.abs(firstAudio - firstVideo) > GAP_US) {
            shift = firstVideo - firstAudio
            warn("stream audio ${(-shift) / 1_000_000} s off its video: moved onto it")
        }
    }

    private inner class Chunk(private val chunk: HlsMediaChunkExtractor) : HlsMediaChunkExtractor by chunk {
        override fun init(extractorOutput: ExtractorOutput) = chunk.init(Output(extractorOutput))
        override fun recreate(): HlsMediaChunkExtractor = Chunk(chunk.recreate())
    }

    /** One segment's tracks: its first video and audio sample, compared once it has both. */
    private inner class Output(private val out: ExtractorOutput) : ExtractorOutput {
        private var firstVideo = C.TIME_UNSET
        private var firstAudio = C.TIME_UNSET

        fun saw(type: Int, timeUs: Long) {
            if (type == C.TRACK_TYPE_VIDEO && firstVideo == C.TIME_UNSET) firstVideo = timeUs
            if (type == C.TRACK_TYPE_AUDIO && firstAudio == C.TIME_UNSET) firstAudio = timeUs
            if (firstVideo != C.TIME_UNSET && firstAudio != C.TIME_UNSET) found(firstVideo, firstAudio)
        }

        override fun track(id: Int, type: Int): TrackOutput = Track(out.track(id, type), type, this)
        override fun endTracks() = out.endTracks()
        override fun seekMap(seekMap: SeekMap) = out.seekMap(seekMap)
    }

    private inner class Track(private val track: TrackOutput, private val type: Int, private val segment: Output) : TrackOutput by track {
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            segment.saw(type, timeUs)
            track.sampleMetadata(if (type == C.TRACK_TYPE_AUDIO) timeUs + shift else timeUs, flags, size, offset, cryptoData)
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
            track.sampleData(input, length, allowEndOfInput, sampleDataPart)

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = track.sampleData(data, length, sampleDataPart)
    }

    companion object {
        private const val GAP_US = 30_000_000L // 30 s
    }
}
