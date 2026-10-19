package com.nuvio.app.features.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_player_no_subtitles_found
import org.jetbrains.compose.resources.getString

object SubtitleRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _addonSubtitles = MutableStateFlow<List<AddonSubtitle>>(emptyList())
    val addonSubtitles: StateFlow<List<AddonSubtitle>> = _addonSubtitles.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadingProgress = MutableStateFlow<SubtitleLoadingProgress?>(null)
    internal val loadingProgress: StateFlow<SubtitleLoadingProgress?> = _loadingProgress.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var activeFetchJob: Job? = null

    /** The video params sent with the last fetch, so callers can tell when a refetch would differ. */
    var lastRequestedVideoParams: SubtitleVideoParams? = null
        private set

    fun fetchAddonSubtitles(
        type: String,
        videoId: String,
        videoParams: SubtitleVideoParams? = null,
    ) {
        activeFetchJob?.cancel()
        _loadingProgress.value = null
        lastRequestedVideoParams = videoParams
        activeFetchJob = scope.launch {
            _isLoading.value = true
            _error.value = null
            _addonSubtitles.value = emptyList()

            val requests = addonSubtitleRequests(type, videoId, videoParams)
            if (requests.isEmpty()) {
                _isLoading.value = false
                return@launch
            }

            _loadingProgress.value = SubtitleLoadingProgress(total = requests.size)
            loadAddonSubtitles(requests) { request, subtitles ->
                _addonSubtitles.update { it + subtitles }
                _loadingProgress.update { progress ->
                    progress?.copy(completed = progress.completed + 1, addonName = request.addonName)
                }
            }

            if (_addonSubtitles.value.isEmpty()) {
                _error.value = getString(Res.string.compose_player_no_subtitles_found)
            }
            _isLoading.value = false
        }
    }

    /**
     * Refetches with better [videoParams] (typically once the video hash is known) without
     * clearing the current list: the new results replace it in one step, and a failed refetch
     * keeps the old list. Falls back to a normal fetch while a fetch is still running.
     */
    fun refreshAddonSubtitles(
        type: String,
        videoId: String,
        videoParams: SubtitleVideoParams,
    ) {
        if (_isLoading.value || _addonSubtitles.value.isEmpty()) {
            fetchAddonSubtitles(type, videoId, videoParams)
            return
        }
        activeFetchJob?.cancel()
        lastRequestedVideoParams = videoParams
        activeFetchJob = scope.launch {
            val requests = addonSubtitleRequests(type, videoId, videoParams)
            if (requests.isEmpty()) return@launch
            val subtitles = loadAddonSubtitles(requests)
            if (subtitles.isNotEmpty()) {
                _error.value = null
                _addonSubtitles.value = subtitles
            }
        }
    }

    fun clear() {
        activeFetchJob?.cancel()
        lastRequestedVideoParams = null
        _loadingProgress.value = null
        _addonSubtitles.value = emptyList()
        _isLoading.value = false
        _error.value = null
    }
}

internal data class SubtitleLoadingProgress(
    val total: Int,
    val completed: Int = 0,
    val addonName: String? = null,
)
