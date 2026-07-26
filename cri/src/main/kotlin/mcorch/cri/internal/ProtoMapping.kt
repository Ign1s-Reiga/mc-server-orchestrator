package mcorch.cri.internal

import mcorch.cri.ContainerId
import mcorch.cri.ContainerMetadata
import mcorch.cri.ContainerSpec
import mcorch.cri.ContainerState
import mcorch.cri.ContainerStatus
import mcorch.cri.ContainerSummary
import mcorch.cri.ExecResult
import mcorch.cri.ImageId
import mcorch.cri.ImageInfo
import mcorch.cri.ImageName
import mcorch.cri.LinuxResources
import mcorch.cri.LinuxSecurityContext
import mcorch.cri.MountPropagation
import mcorch.cri.NamespaceMode
import mcorch.cri.NamespaceSpec
import mcorch.cri.PortProtocol
import mcorch.cri.RegistryAuth
import mcorch.cri.RuntimeCondition
import mcorch.cri.RuntimeHandlerInfo
import mcorch.cri.RuntimeStatus
import mcorch.cri.RuntimeVersion
import mcorch.cri.SandboxId
import mcorch.cri.SandboxMetadata
import mcorch.cri.SandboxSpec
import mcorch.cri.SandboxState
import mcorch.cri.SandboxStatus
import mcorch.cri.SandboxSummary
import mcorch.cri.VolumeMount
import runtime.v1.Api
import runtime.v1.authConfig
import runtime.v1.capability
import runtime.v1.containerConfig
import runtime.v1.containerMetadata
import runtime.v1.dNSConfig
import runtime.v1.imageSpec
import runtime.v1.int64Value
import runtime.v1.keyValue
import runtime.v1.linuxContainerConfig
import runtime.v1.linuxContainerResources
import runtime.v1.linuxContainerSecurityContext
import runtime.v1.linuxPodSandboxConfig
import runtime.v1.linuxSandboxSecurityContext
import runtime.v1.mount
import runtime.v1.namespaceOption
import runtime.v1.podSandboxConfig
import runtime.v1.podSandboxMetadata
import runtime.v1.portMapping
import java.time.Instant

// Translation between the wrapper's own Kotlin types and the generated
// `runtime.v1` messages.
//
// Everything here is `internal`. A generated type must never appear in a public
// signature — that is what keeps grpc and protobuf off `:core`'s classpath.

// ── outbound: wrapper types -> proto ─────────────────────────────────────────

internal fun SandboxSpec.toProto(): Api.PodSandboxConfig =
    podSandboxConfig {
        metadata =
            podSandboxMetadata {
                name = this@toProto.name
                uid = this@toProto.uid
                namespace = this@toProto.namespace
                attempt = this@toProto.attempt.toInt()
            }
        hostname = this@toProto.hostname
        logDirectory = this@toProto.logDirectory
        this@toProto.dnsConfig?.let { dns ->
            dnsConfig =
                dNSConfig {
                    servers += dns.servers
                    searches += dns.searches
                    options += dns.options
                }
        }
        portMappings +=
            this@toProto.portMappings.map { mapping ->
                portMapping {
                    protocol = mapping.protocol.toProto()
                    containerPort = mapping.containerPort
                    hostPort = mapping.hostPort
                    hostIp = mapping.hostIp
                }
            }
        labels.putAll(this@toProto.labels)
        annotations.putAll(this@toProto.annotations)
        linux =
            linuxPodSandboxConfig {
                this@toProto.linux.cgroupParent?.let { cgroupParent = it }
                sysctls.putAll(this@toProto.linux.sysctls)
                securityContext =
                    linuxSandboxSecurityContext {
                        val source = this@toProto.linux.securityContext
                        namespaceOptions = source.namespaces.toProto()
                        source.runAsUser?.let { runAsUser = int64Value { value = it } }
                        source.runAsGroup?.let { runAsGroup = int64Value { value = it } }
                        supplementalGroups += source.supplementalGroups
                        readonlyRootfs = source.readOnlyRootfs
                        privileged = source.privileged
                    }
            }
    }

internal fun NamespaceSpec.toProto(): Api.NamespaceOption =
    namespaceOption {
        network = this@toProto.network.toProto()
        pid = this@toProto.pid.toProto()
        ipc = this@toProto.ipc.toProto()
    }

internal fun NamespaceMode.toProto(): Api.NamespaceMode =
    when (this) {
        NamespaceMode.POD -> Api.NamespaceMode.POD
        NamespaceMode.CONTAINER -> Api.NamespaceMode.CONTAINER
        NamespaceMode.NODE -> Api.NamespaceMode.NODE
    }

internal fun PortProtocol.toProto(): Api.Protocol =
    when (this) {
        PortProtocol.TCP -> Api.Protocol.TCP
        PortProtocol.UDP -> Api.Protocol.UDP
        PortProtocol.SCTP -> Api.Protocol.SCTP
    }

internal fun MountPropagation.toProto(): Api.MountPropagation =
    when (this) {
        MountPropagation.PRIVATE -> Api.MountPropagation.PROPAGATION_PRIVATE
        MountPropagation.HOST_TO_CONTAINER -> Api.MountPropagation.PROPAGATION_HOST_TO_CONTAINER
        MountPropagation.BIDIRECTIONAL -> Api.MountPropagation.PROPAGATION_BIDIRECTIONAL
    }

internal fun ImageName.toProto(): Api.ImageSpec = imageSpec { image = value }

internal fun VolumeMount.toProto(): Api.Mount =
    mount {
        containerPath = this@toProto.containerPath
        hostPath = this@toProto.hostPath
        readonly = this@toProto.readOnly
        selinuxRelabel = this@toProto.selinuxRelabel
        propagation = this@toProto.propagation.toProto()
        recursiveReadOnly = this@toProto.recursiveReadOnly
    }

internal fun ContainerSpec.toProto(): Api.ContainerConfig =
    containerConfig {
        metadata =
            containerMetadata {
                name = this@toProto.name
                attempt = this@toProto.attempt.toInt()
            }
        image = this@toProto.image.toProto()
        command += this@toProto.command
        args += this@toProto.args
        this@toProto.workingDir?.let { workingDir = it }
        envs +=
            this@toProto.env.map { (name, value) ->
                keyValue {
                    key = name
                    this.value = value
                }
            }
        mounts += this@toProto.mounts.map { it.toProto() }
        labels.putAll(this@toProto.labels)
        annotations.putAll(this@toProto.annotations)
        logPath = this@toProto.logPath
        stdin = this@toProto.stdin
        stdinOnce = this@toProto.stdinOnce
        tty = this@toProto.tty
        linux =
            linuxContainerConfig {
                resources = this@toProto.linux.resources.toProto()
                securityContext = this@toProto.linux.securityContext.toProto()
            }
    }

internal fun LinuxResources.toProto(): Api.LinuxContainerResources =
    linuxContainerResources {
        this@toProto.cpuPeriodMicros?.let { cpuPeriod = it }
        this@toProto.cpuQuotaMicros?.let { cpuQuota = it }
        this@toProto.cpuShares?.let { cpuShares = it }
        this@toProto.memoryLimitBytes?.let { memoryLimitInBytes = it }
        this@toProto.memorySwapLimitBytes?.let { memorySwapLimitInBytes = it }
        this@toProto.oomScoreAdj?.let { oomScoreAdj = it }
        this@toProto.cpusetCpus?.let { cpusetCpus = it }
        this@toProto.cpusetMems?.let { cpusetMems = it }
        unified.putAll(this@toProto.unified)
    }

internal fun LinuxSecurityContext.toProto(): Api.LinuxContainerSecurityContext =
    linuxContainerSecurityContext {
        this@toProto.runAsUser?.let { runAsUser = int64Value { value = it } }
        this@toProto.runAsGroup?.let { runAsGroup = int64Value { value = it } }
        this@toProto.runAsUsername?.let { runAsUsername = it }
        supplementalGroups += this@toProto.supplementalGroups
        readonlyRootfs = this@toProto.readOnlyRootFilesystem
        noNewPrivs = this@toProto.noNewPrivileges
        privileged = this@toProto.privileged
        if (this@toProto.addCapabilities.isNotEmpty() || this@toProto.dropCapabilities.isNotEmpty()) {
            capabilities =
                capability {
                    addCapabilities += this@toProto.addCapabilities
                    dropCapabilities += this@toProto.dropCapabilities
                }
        }
    }

internal fun RegistryAuth.toProto(): Api.AuthConfig =
    authConfig {
        this@toProto.username?.let { username = it }
        this@toProto.password?.let { password = it }
        this@toProto.auth?.let { auth = it }
        this@toProto.serverAddress?.let { serverAddress = it }
        this@toProto.identityToken?.let { identityToken = it }
        this@toProto.registryToken?.let { registryToken = it }
    }

internal fun SandboxState.toProto(): Api.PodSandboxState =
    when (this) {
        SandboxState.READY -> Api.PodSandboxState.SANDBOX_READY

        SandboxState.NOT_READY -> Api.PodSandboxState.SANDBOX_NOTREADY

        // Guarded by SandboxFilter's init block; unreachable through the public API.
        SandboxState.UNKNOWN -> error("SandboxState.UNKNOWN has no CRI equivalent")
    }

internal fun ContainerState.toProto(): Api.ContainerState =
    when (this) {
        ContainerState.CREATED -> Api.ContainerState.CONTAINER_CREATED
        ContainerState.RUNNING -> Api.ContainerState.CONTAINER_RUNNING
        ContainerState.EXITED -> Api.ContainerState.CONTAINER_EXITED
        ContainerState.UNKNOWN -> Api.ContainerState.CONTAINER_UNKNOWN
    }

// ── inbound: proto -> wrapper types ──────────────────────────────────────────

/**
 * Nanosecond epoch timestamps, as CRI carries every timestamp. `0` means "not
 * specified" for the optional ones, which is why the callers of this convert
 * zero to `null` rather than to 1970.
 */
internal fun Long.nanosToInstant(): Instant =
    Instant.ofEpochSecond(Math.floorDiv(this, NANOS_PER_SECOND), Math.floorMod(this, NANOS_PER_SECOND))

private const val NANOS_PER_SECOND: Long = 1_000_000_000L

internal fun Api.PodSandboxState.toWrapper(): SandboxState =
    when (this) {
        Api.PodSandboxState.SANDBOX_READY -> SandboxState.READY
        Api.PodSandboxState.SANDBOX_NOTREADY -> SandboxState.NOT_READY
        Api.PodSandboxState.UNRECOGNIZED -> SandboxState.UNKNOWN
    }

internal fun Api.ContainerState.toWrapper(): ContainerState =
    when (this) {
        Api.ContainerState.CONTAINER_CREATED -> ContainerState.CREATED
        Api.ContainerState.CONTAINER_RUNNING -> ContainerState.RUNNING
        Api.ContainerState.CONTAINER_EXITED -> ContainerState.EXITED
        Api.ContainerState.CONTAINER_UNKNOWN, Api.ContainerState.UNRECOGNIZED -> ContainerState.UNKNOWN
    }

internal fun Api.MountPropagation.toWrapper(): MountPropagation =
    when (this) {
        Api.MountPropagation.PROPAGATION_HOST_TO_CONTAINER -> MountPropagation.HOST_TO_CONTAINER
        Api.MountPropagation.PROPAGATION_BIDIRECTIONAL -> MountPropagation.BIDIRECTIONAL
        Api.MountPropagation.PROPAGATION_PRIVATE, Api.MountPropagation.UNRECOGNIZED -> MountPropagation.PRIVATE
    }

internal fun Api.PodSandboxMetadata.toWrapper(): SandboxMetadata =
    SandboxMetadata(name = name, uid = uid, namespace = namespace, attempt = attempt.toUInt())

internal fun Api.ContainerMetadata.toWrapper(): ContainerMetadata =
    ContainerMetadata(name = name, attempt = attempt.toUInt())

internal fun Api.Mount.toWrapper(): VolumeMount =
    VolumeMount(
        containerPath = containerPath,
        hostPath = hostPath,
        readOnly = readonly,
        selinuxRelabel = selinuxRelabel,
        propagation = propagation.toWrapper(),
        recursiveReadOnly = recursiveReadOnly,
    )

internal fun Api.PodSandboxStatus.toWrapper(containerStatuses: List<ContainerStatus>): SandboxStatus =
    SandboxStatus(
        id = SandboxId(id),
        metadata = metadata.toWrapper(),
        state = state.toWrapper(),
        createdAt = createdAt.nanosToInstant(),
        ips =
            if (hasNetwork()) {
                buildList {
                    if (network.ip.isNotEmpty()) add(network.ip)
                    addAll(network.additionalIpsList.map { it.ip })
                }
            } else {
                emptyList()
            },
        labels = labelsMap.toMap(),
        annotations = annotationsMap.toMap(),
        runtimeHandler = runtimeHandler,
        containerStatuses = containerStatuses,
    )

internal fun Api.PodSandbox.toWrapper(): SandboxSummary =
    SandboxSummary(
        id = SandboxId(id),
        metadata = metadata.toWrapper(),
        state = state.toWrapper(),
        createdAt = createdAt.nanosToInstant(),
        labels = labelsMap.toMap(),
        annotations = annotationsMap.toMap(),
        runtimeHandler = runtimeHandler,
    )

internal fun Api.ContainerStatus.toWrapper(): ContainerStatus =
    ContainerStatus(
        id = ContainerId(id),
        metadata = metadata.toWrapper(),
        state = state.toWrapper(),
        createdAt = createdAt.nanosToInstant(),
        startedAt = startedAt.takeIf { it != 0L }?.nanosToInstant(),
        finishedAt = finishedAt.takeIf { it != 0L }?.nanosToInstant(),
        // CRI only guarantees exit_code is meaningful once finished_at is set.
        exitCode = if (finishedAt != 0L) exitCode else null,
        image = ImageName(image.image.ifEmpty { imageRef.ifEmpty { UNKNOWN_IMAGE } }),
        imageId = ImageId(imageId.ifEmpty { imageRef.ifEmpty { UNKNOWN_IMAGE } }),
        reason = reason,
        message = message,
        labels = labelsMap.toMap(),
        annotations = annotationsMap.toMap(),
        mounts = mountsList.map { it.toWrapper() },
        logPath = logPath,
    )

internal fun Api.Container.toWrapper(): ContainerSummary =
    ContainerSummary(
        id = ContainerId(id),
        sandboxId = SandboxId(podSandboxId),
        metadata = metadata.toWrapper(),
        state = state.toWrapper(),
        createdAt = createdAt.nanosToInstant(),
        image = ImageName(image.image.ifEmpty { imageRef.ifEmpty { UNKNOWN_IMAGE } }),
        imageId = ImageId(imageId.ifEmpty { imageRef.ifEmpty { UNKNOWN_IMAGE } }),
        labels = labelsMap.toMap(),
        annotations = annotationsMap.toMap(),
    )

/**
 * Placeholder for an image field containerd left empty. The ID value classes
 * reject blanks, and a status response is not worth failing over a field the
 * runtime chose not to populate.
 */
private const val UNKNOWN_IMAGE: String = "<unknown>"

internal fun Api.Image.toWrapper(): ImageInfo =
    ImageInfo(
        id = ImageId(id),
        repoTags = repoTagsList.toList(),
        repoDigests = repoDigestsList.toList(),
        sizeBytes = size,
        pinned = pinned,
    )

internal fun Api.VersionResponse.toWrapper(): RuntimeVersion =
    RuntimeVersion(
        version = version,
        runtimeName = runtimeName,
        runtimeVersion = runtimeVersion,
        runtimeApiVersion = runtimeApiVersion,
    )

internal fun Api.StatusResponse.toWrapper(): RuntimeStatus =
    RuntimeStatus(
        conditions =
            status.conditionsList.map {
                RuntimeCondition(type = it.type, status = it.status, reason = it.reason, message = it.message)
            },
        runtimeHandlers =
            runtimeHandlersList.map {
                RuntimeHandlerInfo(
                    name = it.name,
                    recursiveReadOnlyMounts = it.features.recursiveReadOnlyMounts,
                    userNamespaces = it.features.userNamespaces,
                )
            },
    )

internal fun Api.ExecSyncResponse.toWrapper(): ExecResult =
    ExecResult(
        exitCode = exitCode,
        stdout = stdout.toStringUtf8(),
        stderr = stderr.toStringUtf8(),
    )
