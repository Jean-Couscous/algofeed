package algofeed.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@kotlinx.serialization.Serializable
enum class MediaKind { IMAGE, VIDEO, GIF }

/** One image or video attached to an entry; several make a gallery. */
@kotlinx.serialization.Serializable
data class MediaItem(
    val url: String,
    val kind: MediaKind,
    val thumbnailUrl: String? = null,
    val caption: String? = null,
    /** For video: a muxed stream (DASH/HLS) with audio, when [url] is a separate track. Null means [url] plays as-is. */
    val streamUrl: String? = null,
)

/**
 * Media is persisted as a JSON string column so Room's KSP never has to resolve the serializable
 * types; this codec is the single place that encodes and decodes it.
 */
object MediaCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(MediaItem.serializer())

    fun encode(media: List<MediaItem>): String = json.encodeToString(serializer, media)

    fun decode(raw: String?): List<MediaItem> =
        if (raw.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())

    /** The entry's first video attachment, if any; used by cards to decide whether to play inline. */
    fun firstVideo(raw: String?): MediaItem? = decode(raw).firstOrNull { it.kind == MediaKind.VIDEO }
}
