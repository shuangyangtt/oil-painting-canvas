package app.youtubu.tv

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class RowState(val videos: List<Video> = emptyList(), val loading: Boolean = false, val failed: Boolean = false)

sealed interface Screen {
    data object Home : Screen
    data class Player(val rowKey: String, val videos: List<Video>, val index: Int) : Screen
    data object Parent : Screen
}

const val FAVORITES_KEY = "favorites"
private const val ROW_MAX_AGE_MS = 12 * 60 * 60 * 1000L

/** All app state, kept by MainActivity for the whole lifetime of the app. */
class AppState(val store: Store, private val scope: CoroutineScope) {
    val api = Invidious { store.instance }

    var channels by mutableStateOf(store.channels())
        private set
    var favorites by mutableStateOf(store.favorites())
        private set
    val rows = mutableStateMapOf<String, RowState>()

    var screen by mutableStateOf<Screen>(Screen.Home)

    /** Home screen position, kept while a video plays so the child comes back to the same card. */
    val homeList = LazyListState()
    var lastFocus by mutableStateOf<Pair<String, Int>?>(null)

    /** The video on screen (player) or under the focus (home), for the ≡ favourite toggle. */
    var currentVideo by mutableStateOf<Video?>(null)

    var toast by mutableStateOf<String?>(null)
    private var toastJob: Job? = null

    private val loadLimit = Semaphore(3)

    fun showToast(text: String) {
        toast = text
        toastJob?.cancel()
        toastJob = scope.launch {
            delay(2500)
            toast = null
        }
    }

    fun loadAll(force: Boolean = false) {
        for (channel in channels) loadRow(channel, force)
    }

    private fun loadRow(channel: Channel, force: Boolean = false) {
        val cached = store.cachedRow(channel.id)
        val fresh = cached != null && System.currentTimeMillis() - cached.first < ROW_MAX_AGE_MS
        rows[channel.id] = RowState(cached?.second ?: emptyList(), loading = force || !fresh)
        if (channel.avatar.isEmpty()) loadAvatar(channel)
        if (fresh && !force) return
        scope.launch {
            loadLimit.withPermit {
                rows[channel.id] = try {
                    val videos = api.popularVideos(channel.id)
                    store.cacheRow(channel.id, videos)
                    RowState(videos)
                } catch (e: Exception) {
                    RowState(cached?.second ?: emptyList(), failed = true)
                }
            }
        }
    }

    private fun loadAvatar(channel: Channel) {
        scope.launch {
            val info = try {
                loadLimit.withPermit { api.channelInfo(channel.id) }
            } catch (e: Exception) {
                return@launch
            }
            channels = channels.map { if (it.id == channel.id) it.copy(avatar = info.avatar) else it }
            store.saveChannels(channels)
        }
    }

    fun isFavorite(video: Video) = favorites.any { it.id == video.id }

    fun toggleFavorite(video: Video) {
        favorites = if (isFavorite(video)) {
            showToast("已从「最喜欢」拿走")
            favorites.filterNot { it.id == video.id }
        } else {
            showToast("❤  已加入「最喜欢」")
            listOf(video) + favorites
        }
        store.saveFavorites(favorites)
    }

    fun removeFavorite(videoId: String) {
        favorites = favorites.filterNot { it.id == videoId }
        store.saveFavorites(favorites)
    }

    /** Short press on the ≡ key: add or remove the current video from the favourites. */
    fun onMenuShort() {
        when (screen) {
            is Screen.Home, is Screen.Player -> currentVideo?.let { toggleFavorite(it) }
            Screen.Parent -> Unit
        }
    }

    /** Long press on the ≡ key: the hidden parent settings. */
    fun onMenuLong() {
        if (screen != Screen.Parent) screen = Screen.Parent
    }

    // ---- channel management (parent settings and the phone page) ----

    fun hasChannel(id: String) = channels.any { it.id == id }

    fun addChannel(channel: Channel) {
        if (hasChannel(channel.id)) return
        channels = channels + channel
        store.saveChannels(channels)
        loadRow(channel)
    }

    fun removeChannel(id: String) {
        channels = channels.filterNot { it.id == id }
        rows.remove(id)
        store.saveChannels(channels)
    }

    fun moveChannel(id: String, delta: Int) {
        val list = channels.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in list.indices) return
        list.add(to, list.removeAt(from))
        channels = list
        store.saveChannels(channels)
    }

    fun resetChannels() {
        channels = DEFAULT_CHANNELS
        store.saveChannels(channels)
        loadAll()
    }

    fun setInstance(url: String) {
        store.instance = url
        refreshAll()
    }

    fun refreshAll() {
        store.clearRowCache()
        loadAll(force = true)
    }
}
