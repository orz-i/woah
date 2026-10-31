package art.gaoge.dance.engine.bridge

import art.gaoge.dance.engine.diagnostics.NativeDiagnosticPrivacy

/** Non-generated channel boundary. Successful DTOs/URIs pass through unchanged. */
internal class PrivateDanceNativeApi(private val delegate: DanceNativeApi) : DanceNativeApi {
    override suspend fun getCapabilities(): NativeCapabilitiesDto = try {
        delegate.getCapabilities()
    } catch (error: Throwable) {
        throw NativeDiagnosticPrivacy.pigeon(error, "capabilities")
    }

    override suspend fun probeVideo(uri: String): VideoInfoDto = try {
        delegate.probeVideo(uri)
    } catch (error: Throwable) {
        throw NativeDiagnosticPrivacy.pigeon(error, "probe")
    }

    override suspend fun analyzeVideo(request: AnalyzeRequestDto): AnalyzeResultDto = try {
        delegate.analyzeVideo(request)
    } catch (error: Throwable) {
        throw NativeDiagnosticPrivacy.pigeon(error, "analysis")
    }

    override suspend fun getPreviewFrame(request: PreviewRequestDto): PreviewFrameDto = try {
        delegate.getPreviewFrame(request)
    } catch (error: Throwable) {
        throw NativeDiagnosticPrivacy.pigeon(error, "preview")
    }

    override suspend fun startExport(request: ExportRequestDto): String = try {
        delegate.startExport(request)
    } catch (error: Throwable) {
        throw NativeDiagnosticPrivacy.pigeon(error, "export")
    }

    override suspend fun cancelJob(jobId: String): Unit = try {
        delegate.cancelJob(jobId)
    } catch (error: Throwable) {
        throw NativeDiagnosticPrivacy.pigeon(error, "cancel")
    }

    override suspend fun getJobStatus(jobId: String): JobStatusDto = try {
        delegate.getJobStatus(jobId)
    } catch (error: Throwable) {
        throw NativeDiagnosticPrivacy.pigeon(error, "status")
    }

    override suspend fun releaseProject(projectId: String): Unit = try {
        delegate.releaseProject(projectId)
    } catch (error: Throwable) {
        throw NativeDiagnosticPrivacy.pigeon(error, "release")
    }
}
