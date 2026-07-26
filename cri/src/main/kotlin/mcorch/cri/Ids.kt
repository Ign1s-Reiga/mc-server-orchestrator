package mcorch.cri

/**
 * ID of a pod sandbox as assigned by containerd.
 *
 * Opaque. Safe to log — sandbox and container IDs are runtime identifiers, not
 * player data.
 */
@JvmInline
public value class SandboxId(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "sandbox id must not be blank" }
    }

    override fun toString(): String = value
}

/** ID of a container as assigned by containerd. Opaque, and safe to log. */
@JvmInline
public value class ContainerId(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "container id must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * An image as *requested*: `repo/name:tag`, or a digest reference.
 *
 * This is the request side of CRI's `ImageSpec.image`. It is not the same thing
 * as [ImageId] — asking for `:latest` twice can resolve to two different images.
 */
@JvmInline
public value class ImageName(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "image name must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * The node-unique identifier containerd resolved an image to.
 *
 * CRI guarantees this is the same value across `PullImageResponse.image_ref`,
 * `Image.id`, `Container.image_id` and `ContainerStatus.image_id`, which is what
 * makes "is the image the container is running the one we asked for" answerable
 * without re-pulling.
 */
@JvmInline
public value class ImageId(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "image id must not be blank" }
    }

    override fun toString(): String = value
}
