package com.bizfunnel.vidloot.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {
    private fun job(mode: String, quality: String) = Job(
        id = "1", url = "https://example.com/v", title = "t", thumbnail = null, mode = mode, quality = quality,
        label = "", playlist = false, subtitles = true, embedThumbnail = true, chapters = true, status = Status.DOWNLOADING,
    )

    @Test fun bandsAndSizes() {
        val v = JSONObject(
            """{"title":"Clip","duration":100.0,"extractor_key":"Youtube","formats":[
              {"vcodec":"none","acodec":"opus","abr":130.0,"filesize":1000000},
              {"vcodec":"avc1","acodec":"none","height":1080,"filesize":50000000},
              {"vcodec":"vp9","acodec":"none","height":2160,"filesize":200000000},
              {"vcodec":"avc1","acodec":"none","height":720,"tbr":1000.0},
              {"vcodec":"avc1","acodec":"mp4a","height":360,"filesize":5000000}]}""",
        )
        val i = InfoParser.parse("https://x", v)
        assertEquals(2160, i.maxHeight)
        assertEquals(listOf("2160", "1080", "720", "360"), i.videoOptions.map { it.id })
        assertEquals(51_000_000L, i.videoOptions[1].size)
        assertEquals(13_500_000L, i.videoOptions[2].size)
        assertEquals(5_000_000L, i.videoOptions[3].size)
        assertEquals(4_000_000L, i.audioOptions[0].size)
    }

    @Test fun directFileHasBestOption() {
        val i = InfoParser.parse("https://x/clip.mp4", JSONObject("""{"title":"clip","formats":[{"url":"u","ext":"mp4"}]}"""))
        assertEquals(listOf("best"), i.videoOptions.map { it.id })
    }

    @Test fun readableErrors() {
        assertEquals("This site isn't supported, or the link isn't a video page.", InfoParser.cleanError("ERROR: Unsupported URL: https://a"))
        assertEquals("Something odd happened", InfoParser.cleanError("WARNING: x\nERROR: [generic] abc: Something odd happened"))
    }

    @Test fun videoArgs() {
        val a = Ytdlp.buildArgs(job("video", "1080"), "/w", "en").joinToString(" ")
        assertTrue(a.contains("-S res:1080,vcodec:h264,acodec:aac"))
        assertTrue(a.contains("--merge-output-format mp4"))
        assertTrue(a.contains("--embed-subs"))
        assertTrue(a.contains("-P /w"))
    }

    @Test fun audioArgs() {
        val a = Ytdlp.buildArgs(job("audio", "mp3"), "/w", "en").joinToString(" ")
        assertTrue(a.contains("-x --audio-format mp3 --audio-quality 320K"))
        assertTrue(!a.contains("--embed-subs"))
    }

    @Test fun progressVideoThenAudio() {
        var j = job("video", "1080")
        j = Ytdlp.applyLine(j, "VLPROG|downloading| 50.0%|1.2MiB/s|00:10|100|200|NA|NA|NA|avc1|none")!!
        assertEquals(45.0, j.percent, 0.01)
        assertEquals("1.2MiB/s", j.speed)
        j = Ytdlp.applyLine(j, "VLPROG|downloading| 50.0%|1.2MiB/s|00:01|1|2|NA|NA|NA|none|mp4a")!!
        assertEquals(95.0, j.percent, 0.01)
        assertEquals("Downloading audio", j.stage)
        j = Ytdlp.applyLine(j, "VLPP|Merger|started")!!
        assertEquals(Status.PROCESSING, j.status)
        assertEquals("Merging audio + video", j.stage)
        assertNull(Ytdlp.applyLine(j, "[download] something else"))
    }

    @Test fun playlistProgress() {
        val j = Ytdlp.applyLine(job("audio", "mp3"), "VLPROG|downloading|100%|NA|NA|5|5|NA|3|4|none|opus")!!
        assertEquals(3, j.itemIndex); assertEquals(4, j.itemCount)
        assertEquals(75.0, j.percent, 0.01)
    }

    @Test fun jobJsonRoundTrip() {
        val j = job("video", "720").copy(status = Status.DOWNLOADING, fileUri = null)
        val back = Job.fromJson(j.toJson())
        assertEquals(Status.PAUSED, back.status)
        assertEquals(j.url, back.url)
        assertNull(back.fileUri)
    }
}
