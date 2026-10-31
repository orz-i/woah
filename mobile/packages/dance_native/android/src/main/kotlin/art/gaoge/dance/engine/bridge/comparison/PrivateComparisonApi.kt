package art.gaoge.dance.engine.bridge.comparison

import art.gaoge.dance.engine.diagnostics.NativeDiagnosticPrivacy

/** Non-generated channel boundary. Successful DTOs/URIs pass through unchanged. */
internal class PrivateComparisonApi(private val delegate: DanceComparisonApi) : DanceComparisonApi {
    override suspend fun pickVideo(role: String): ComparisonSourceDto? = try {
        delegate.pickVideo(role)
    } catch (error: Throwable) {
        throw NativeDiagnosticPrivacy.comparison(error)
    }

    override suspend fun loadProjects(): List<String> = try {
        delegate.loadProjects()
    } catch (error: Throwable) {
        throw NativeDiagnosticPrivacy.comparison(error)
    }

    override suspend fun saveProject(projectJson: String): Unit = try {
        delegate.saveProject(projectJson)
    } catch (error: Throwable) {
        throw NativeDiagnosticPrivacy.comparison(error)
    }

    override suspend fun open(teacherUri: String, learnerUri: String, offsetUs: Long, positionUs: Long, speed: Double, width: Long, height: Long, sideBySide: Boolean, practice: ComparisonPracticeDto, timeScale: Double): ComparisonPlaybackDto = try {
        delegate.open(teacherUri, learnerUri, offsetUs, positionUs, speed, width, height, sideBySide, practice, timeScale)
    } catch (error: Throwable) {
        throw NativeDiagnosticPrivacy.comparison(error)
    }

    override suspend fun control(command: ComparisonCommand, valueUs: Long, speed: Double): ComparisonPlaybackDto = try {
        delegate.control(command, valueUs, speed)
    } catch (error: Throwable) {
        throw NativeDiagnosticPrivacy.comparison(error)
    }

    override suspend fun setAlignment(offsetUs: Long, timeScale: Double): ComparisonPlaybackDto = try {
        delegate.setAlignment(offsetUs, timeScale)
    } catch (error: Throwable) {
        throw NativeDiagnosticPrivacy.comparison(error)
    }

    override suspend fun resize(width: Long, height: Long, sideBySide: Boolean): ComparisonPlaybackDto = try {
        delegate.resize(width, height, sideBySide)
    } catch (error: Throwable) {
        throw NativeDiagnosticPrivacy.comparison(error)
    }

    override suspend fun setPractice(practice: ComparisonPracticeDto): ComparisonPlaybackDto = try {
        delegate.setPractice(practice)
    } catch (error: Throwable) {
        throw NativeDiagnosticPrivacy.comparison(error)
    }

    override suspend fun stepFrame(forward: Boolean): ComparisonPlaybackDto = try {
        delegate.stepFrame(forward)
    } catch (error: Throwable) {
        throw NativeDiagnosticPrivacy.comparison(error)
    }

    override suspend fun getState(): ComparisonPlaybackDto = try {
        delegate.getState()
    } catch (error: Throwable) {
        throw NativeDiagnosticPrivacy.comparison(error)
    }

    override suspend fun alignMusic(teacherUri: String, learnerUri: String): MusicAlignmentDto = try {
        delegate.alignMusic(teacherUri, learnerUri)
    } catch (error: Throwable) {
        throw NativeDiagnosticPrivacy.comparison(error)
    }

    override suspend fun cancelAlignment(): Unit = try {
        delegate.cancelAlignment()
    } catch (error: Throwable) {
        throw NativeDiagnosticPrivacy.comparison(error)
    }

    override suspend fun startExport(projectJson: String, startUs: Long, endUs: Long, width: Long, height: Long, gap: Long, sideBySide: Boolean, replacePreviousUnsaved: Boolean): ComparisonExportDto = try {
        delegate.startExport(projectJson, startUs, endUs, width, height, gap, sideBySide, replacePreviousUnsaved)
    } catch (error: Throwable) {
        throw NativeDiagnosticPrivacy.comparison(error)
    }

    override suspend fun getExport(): ComparisonExportDto? = try {
        delegate.getExport()
    } catch (error: Throwable) {
        throw NativeDiagnosticPrivacy.comparison(error)
    }

    override suspend fun cancelExport(jobId: String): ComparisonExportDto = try {
        delegate.cancelExport(jobId)
    } catch (error: Throwable) {
        throw NativeDiagnosticPrivacy.comparison(error)
    }

    override suspend fun retryExport(jobId: String): ComparisonExportDto = try {
        delegate.retryExport(jobId)
    } catch (error: Throwable) {
        throw NativeDiagnosticPrivacy.comparison(error)
    }

    override suspend fun saveExport(jobId: String): ComparisonExportDto = try {
        delegate.saveExport(jobId)
    } catch (error: Throwable) {
        throw NativeDiagnosticPrivacy.comparison(error)
    }

    override suspend fun openExport(jobId: String): Unit = try {
        delegate.openExport(jobId)
    } catch (error: Throwable) {
        throw NativeDiagnosticPrivacy.comparison(error)
    }

    override suspend fun close(): Unit = try {
        delegate.close()
    } catch (error: Throwable) {
        throw NativeDiagnosticPrivacy.comparison(error)
    }
}
