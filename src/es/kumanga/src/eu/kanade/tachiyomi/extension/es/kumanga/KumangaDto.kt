package eu.kanade.tachiyomi.extension.es.kumanga

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.ceil

@Serializable
class ComicsPayloadDto(
    val contents: List<ComicDto> = emptyList(),
    val retrievedCount: Int = 0,
)

@Serializable
class ComicDto(
    private val id: String = "",
    private val name: String = "",
    private val slug: String? = null,
) {
    fun toSManga(baseUrl: String) = SManga.create().apply {
        title = name
        url = "/manga/$id/${slug ?: "manga"}"
        val numId = id.toIntOrNull() ?: 1
        val folder = ceil(numId / 2500.0).toInt()
        thumbnail_url = "https://static.kumanga.com/manga/$folder/$id.jpg"
    }
}

@Serializable
class ImageDto(
    @SerialName("imgURL") val imgURL: String = "",
)
