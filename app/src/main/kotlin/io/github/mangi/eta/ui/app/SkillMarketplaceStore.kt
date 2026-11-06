package io.github.mangi.eta.ui.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.skill.GitHubSkillCandidate
import io.github.mangi.eta.agent.skill.GitHubSkillRepository
import io.github.mangi.eta.agent.skill.GitHubSkillRepositoryParser
import io.github.mangi.eta.agent.skill.GitHubSkillSourceException
import io.github.mangi.eta.agent.skill.InstalledSkill
import io.github.mangi.eta.agent.skill.PublicGitHubSkillSource
import io.github.mangi.eta.agent.skill.SkillInstallConflict
import io.github.mangi.eta.agent.skill.SkillInstallResult
import io.github.mangi.eta.agent.skill.SkillRuntime
import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A conflict from a first (non-replacing) install attempt, waiting on the user's decision. */
@Immutable
internal data class SkillMarketplaceConflict(
    val candidate: GitHubSkillCandidate,
    val conflicts: List<SkillInstallConflict>,
)

internal class SkillMarketplaceViewModel(application: Application) : AndroidViewModel(application) {
    val store = SkillMarketplaceStore(application, viewModelScope)

    override fun onCleared() {
        store.close()
        super.onCleared()
    }
}

/**
 * Browses skill candidates from a GitHub repository (the curated default, or one the user types
 * in) and installs them — a tap-driven front end for the same PublicGitHubSkillSource /
 * SkillPackageInstaller the agent already uses when asked to install a skill from chat.
 *
 * Unlike the agent's own tool path (AgentLocalTools), this does not need the cross-turn
 * inspect-then-install replay protection: a person tapping "install" on a candidate they can see
 * right in front of them, within one screen session, does not need defending against the way an
 * automated multi-turn tool call does. A conflicting install is simply retried once, right away,
 * pinned to the same ref, after the person confirms.
 */
internal class SkillMarketplaceStore(
    context: Context,
    private val scope: CoroutineScope,
) : Closeable {
    private val applicationContext = context.applicationContext
    private val source = PublicGitHubSkillSource(
        cacheRoot = applicationContext.cacheDir,
        baseClient = AgentHttpClient.client,
    )
    private val installer = SkillRuntime.createPackageInstaller(applicationContext)
    private var currentRepository: GitHubSkillRepository? = null
    private var loadJob: Job? = null
    private var installJob: Job? = null

    var loading by mutableStateOf(true)
        private set
    var repositoryLabel by mutableStateOf("")
        private set
    var candidates by mutableStateOf<List<GitHubSkillCandidate>>(emptyList())
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    var installingPath by mutableStateOf<String?>(null)
        private set
    var installError by mutableStateOf<String?>(null)
        private set
    var installedNames by mutableStateOf<List<String>>(emptyList())
        private set
    var pendingConflict by mutableStateOf<SkillMarketplaceConflict?>(null)
        private set

    fun ensureLoaded() {
        if (candidates.isNotEmpty() || loadJob?.isActive == true) return
        loadCurated()
    }

    fun loadCurated() {
        if (loadJob?.isActive == true) return
        loadJob = scope.launch {
            loading = true
            loadError = null
            try {
                val inspection = withContext(Dispatchers.IO) { source.listCurated() }
                currentRepository = GitHubSkillRepositoryParser.parse(inspection.repository)
                    .copy(ref = inspection.ref)
                repositoryLabel = inspection.repository
                candidates = inspection.candidates
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                loadError = error.skillSourceErrorMessage()
            } finally {
                loading = false
            }
        }
    }

    /** repositoryInput: a GitHub repo URL or "owner/repo" slug, optionally with a path/ref. */
    fun browseRepository(repositoryInput: String) {
        if (loadJob?.isActive == true) return
        loadJob = scope.launch {
            loading = true
            loadError = null
            try {
                val requested = GitHubSkillRepositoryParser.parse(repositoryInput)
                val inspection = withContext(Dispatchers.IO) { source.inspect(requested) }
                currentRepository = requested.copy(ref = inspection.ref)
                repositoryLabel = inspection.repository
                candidates = inspection.candidates
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                loadError = error.skillSourceErrorMessage()
            } finally {
                loading = false
            }
        }
    }

    fun install(candidate: GitHubSkillCandidate) = attemptInstall(candidate, forceReplace = false)

    fun confirmReplace() {
        val pending = pendingConflict ?: return
        pendingConflict = null
        attemptInstall(pending.candidate, forceReplace = true, expectedId = pending.conflicts.firstOrNull()?.id)
    }

    fun dismissConflict() {
        pendingConflict = null
    }

    fun dismissInstallError() {
        installError = null
    }

    private fun attemptInstall(candidate: GitHubSkillCandidate, forceReplace: Boolean, expectedId: String? = null) {
        if (installJob?.isActive == true) return
        val repository = currentRepository ?: return
        installJob = scope.launch {
            installingPath = candidate.path
            installError = null
            try {
                val result = withContext(Dispatchers.IO) {
                    source.downloadArchive(repository).use { archive ->
                        installer.installRepositoryZip(
                            openStream = { archive.file.inputStream() },
                            selectedPaths = listOf(candidate.path),
                            replaceUserSkills = forceReplace,
                            expectedReplacementIds = if (expectedId != null) setOf(expectedId) else emptySet(),
                        )
                    }
                }
                when (result) {
                    is SkillInstallResult.Success -> installedNames = result.installed.map(InstalledSkill::name)
                    is SkillInstallResult.Conflict -> {
                        val conflict = result.conflicts.firstOrNull()
                        if (conflict?.replaceAllowed == true) {
                            pendingConflict = SkillMarketplaceConflict(candidate, result.conflicts)
                        } else {
                            installError = "Skill ini sudah terpasang dan tidak bisa ditimpa dari sini."
                        }
                    }
                    is SkillInstallResult.Failure -> installError = result.error.message
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                installError = error.skillSourceErrorMessage()
            } finally {
                installingPath = null
            }
        }
    }

    override fun close() {
        source.close()
    }

    private fun Throwable.skillSourceErrorMessage(): String = when {
        this is GitHubSkillSourceException -> message ?: "Permintaan ke GitHub gagal (${code})"
        this is SocketTimeoutException -> "Waktu koneksi ke GitHub habis"
        this is UnknownHostException -> "Tidak bisa menghubungi GitHub — periksa koneksi internet"
        this is SSLException -> "Koneksi aman ke GitHub gagal"
        this is IOException -> "Koneksi jaringan atau penyimpanan lokal gagal"
        this is IllegalArgumentException -> message ?: "Repositori atau tautan GitHub tidak valid"
        else -> "Gagal memuat, coba lagi nanti"
    }
}
