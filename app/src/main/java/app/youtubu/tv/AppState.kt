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
    data object Offline : Screen
    data object About : Screen
}

const val OFFLINE_KEY = "offline"

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

    /** songs stored completely on the projector, and the background downloader that fills the cache */
    var offlineVideos by mutableStateOf<List<Video>>(emptyList())
    lateinit var precacher: Precacher
    var lastOfflineIndex = 0

    /** Home screen position, kept while a video plays so the child comes back to the same card. */
    val homeList = LazyListState()
    var lastFocus by mutableStateOf<Pair<String, Int>?>(null)

    /** The video on screen (player) or under the focus (home), for the ≡ favourite toggle. */
    var currentVideo by mutableStateOf<Video?>(null)

    var toast by mutableStateOf<String?>(null)
    private var toastJob: Job? = null

    // tablet: one channel at a time (5 pages each), so the Invidious server never sees a burst of requests
    private val loadLimit = Semaphore(if (BuildConfig.PAD) 1 else 3)

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
            is Screen.Home, is Screen.Player, Screen.Offline -> currentVideo?.let { toggleFavorite(it) }
            Screen.Parent, Screen.About -> Unit
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
        channels = channels + channel.copy(lang = channel.lang.ifEmpty { guessLang(channel.name) })
        store.saveChannels(channels)
        loadRow(channel)
    }

    fun setChannelLang(id: String, lang: String) {
        channels = channels.map { if (it.id == id) it.copy(lang = lang) else it }
        store.saveChannels(channels)
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
        currentInstance = store.instance
        refreshAll()
    }

    // ---- several servers: switch to the next one when the current one blocks us or is down ----

    /** the server in use (for the settings page) */
    var currentInstance by mutableStateOf(store.instance)
        private set
    var instances by mutableStateOf(store.instances)
        private set
    private val failedAt = mutableMapOf<String, Long>()
    private var lastSwitch = 0L

    /** Switch by hand (settings). Song lists and stored songs stay as they are. */
    fun useInstance(url: String) {
        store.instance = url
        currentInstance = store.instance
        if (url !in instances) {
            instances = instances + url
            store.instances = instances
        }
    }

    fun addInstance(url: String) {
        val clean = url.trim().trimEnd('/')
        if (clean in instances) return
        instances = instances + clean
        store.instances = instances
    }

    fun removeInstance(url: String) {
        if (instances.size <= 1) return
        instances = instances - url
        store.instances = instances
        if (currentInstance == url) useInstance(instances.first())
    }

    /**
     * The current server failed (blocked, overloaded, down). Moves on to the next server that has not failed
     * in the last 10 minutes. Returns false when there is none (then everybody waits instead).
     */
    @Synchronized
    fun failover(reason: String?): Boolean {
        val now = System.currentTimeMillis()
        // errors from requests that were still running on the old server right after a switch
        if (now - lastSwitch < 30_000) return true
        // an error that names another server (an older request) says nothing about the current one
        val current = store.instance
        if (reason != null && !reason.contains(hostOf(current)) &&
            store.instances.any { it != current && reason.contains(hostOf(it).substringBefore(":")) }
        ) return true
        failedAt[current] = now
        val next = store.instances.firstOrNull { it != store.instance && now - (failedAt[it] ?: 0) > 10 * 60_000 }
            ?: return false
        Media.log("服务器 ${hostOf(store.instance)} 出问题（${reason?.take(50)}），自动换到 ${hostOf(next)}")
        lastSwitch = now
        store.instance = next
        scope.launch { currentInstance = next }
        return true
    }

    fun refreshAll() {
        store.clearRowCache()
        loadAll(force = true)
    }
}
