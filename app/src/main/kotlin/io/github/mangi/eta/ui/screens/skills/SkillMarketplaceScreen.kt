package io.github.mangi.eta.ui.screens.skills

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.skill.GitHubSkillCandidate
import io.github.mangi.eta.ui.app.SkillMarketplaceStore
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * Tap-driven front end for installing skills from GitHub — the same PublicGitHubSkillSource /
 * SkillPackageInstaller the agent already uses when asked to do this from chat, just with a list
 * and a button instead of typing a request. Shows the curated default list on open; typing a
 * repository and tapping "Jelajahi" switches to browsing that one instead.
 *
 * Note: unlike most of this app's screens, the text here is inline Indonesian rather than string
 * resources (R.string) — this screen was added without the project's string-resource generation
 * tooling, so it deliberately does not try to fabricate resource-name hashes by hand.
 */
@Composable
internal fun SkillMarketplaceScreen(
    store: SkillMarketplaceStore,
    onBack: () -> Unit,
) {
    var repositoryInput by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) { store.ensureLoaded() }

    MiuixScaffoldPage(
        title = "Skill komunitas",
        onBack = onBack,
    ) {
        item(key = "intro") {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text(
                    text = "Jelajahi dan pasang skill dari repositori GitHub mana pun yang berisi SKILL.md.",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                if (store.installedNames.isNotEmpty()) {
                    Text(
                        text = "Terpasang: ${store.installedNames.joinToString(", ")}",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
                store.installError?.let { error ->
                    Text(
                        text = error,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.error,
                    )
                }
            }
        }

        item(key = "repository_input") {
            InputField(
                query = repositoryInput,
                onQueryChange = { repositoryInput = it },
                onSearch = { if (repositoryInput.isNotBlank()) store.browseRepository(repositoryInput.trim()) },
                expanded = false,
                onExpandedChange = {},
                label = "owner/repo atau tautan GitHub lain",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item(key = "repository_actions") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                EtaTextButton(
                    text = "Jelajahi",
                    enabled = repositoryInput.isNotBlank() && !store.loading,
                    onClick = { store.browseRepository(repositoryInput.trim()) },
                )
                EtaTextButton(
                    text = "Pakai daftar kurasi",
                    enabled = !store.loading,
                    onClick = store::loadCurated,
                )
            }
        }

        item(key = "list_title") {
            EtaPreferenceGroupTitle(
                if (store.repositoryLabel.isEmpty()) {
                    "Skill ditemukan"
                } else {
                    "Skill di ${store.repositoryLabel} (${store.candidates.size})"
                },
            )
        }

        when {
            store.loading && store.candidates.isEmpty() -> item(key = "loading") { MarketplaceLoadingState() }
            store.loadError != null -> item(key = "error") {
                ListEmptyState(
                    title = "Gagal memuat",
                    summary = store.loadError,
                    icon = Icons.Rounded.CloudOff,
                )
            }
            store.candidates.isEmpty() -> item(key = "empty") {
                ListEmptyState(
                    title = "Tidak ada skill ditemukan",
                    summary = "Repositori ini tidak punya SKILL.md yang terdeteksi",
                    icon = Icons.Rounded.SearchOff,
                )
            }
            else -> items(
                store.candidates,
                key = { "skill:${it.path}" },
                contentType = { "skill" },
            ) { candidate ->
                SkillCandidateCard(
                    candidate = candidate,
                    installing = store.installingPath == candidate.path,
                    installEnabled = store.installingPath == null,
                    onInstall = { store.install(candidate) },
                )
            }
        }
    }

    val pending = store.pendingConflict
    EtaWindowDialog(
        show = pending != null,
        title = "Timpa skill yang sudah ada?",
        summary = pending?.conflicts?.firstOrNull()?.let {
            "\"${it.name}\" sudah terpasang dari ${it.existingSource}. Timpa dengan versi dari ${store.repositoryLabel}?"
        },
        onDismissRequest = store::dismissConflict,
    ) {
        MiuixDialogActions(
            confirmText = "Timpa",
            destructive = true,
            onCancel = store::dismissConflict,
            onConfirm = store::confirmReplace,
        )
    }
}

@Composable
private fun SkillCandidateCard(
    candidate: GitHubSkillCandidate,
    installing: Boolean,
    installEnabled: Boolean,
    onInstall: () -> Unit,
) {
    EtaCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        pressFeedbackType = PressFeedbackType.None,
    ) {
        Text(
            text = candidate.name,
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = candidate.path,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            EtaTextButton(
                text = if (installing) "Memasang…" else "Pasang",
                enabled = installEnabled,
                onClick = onInstall,
            )
        }
    }
}

@Composable
private fun MarketplaceLoadingState() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        InfiniteProgressIndicator(size = 28.dp)
    }
}
