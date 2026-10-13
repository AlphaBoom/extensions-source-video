import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/** Video-specific counterpart of Keiyoushi's extension DSL. */
abstract class VideoExtension
    @Inject
    constructor(
        private val objects: ObjectFactory,
    ) {
        abstract val name: Property<String>
        abstract val sourceClass: Property<String>
        abstract val versionCode: Property<Int>
        abstract val contentWarning: Property<Int>
        abstract val torrent: Property<Boolean>
        abstract val theme: Property<String>
        abstract val baseUrl: Property<String>
        val sources = mutableListOf<VideoSource>()

        fun source(block: VideoSource.() -> Unit) {
            sources.add(objects.newInstance(VideoSource::class.java).apply(block))
        }

        init {
            contentWarning.convention(0)
            torrent.convention(false)
        }
    }

abstract class VideoSource {
    abstract val name: Property<String>
    abstract val lang: Property<String>
    abstract val baseUrl: Property<String>
    abstract val versionId: Property<Int>
    abstract val id: Property<Long>

    init {
        versionId.convention(1)
    }
}
