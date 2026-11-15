package app.youtubu.tv

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** One section of the About / disclaimer text; shown on the TV and on the phone page. */
data class AboutSection(val title: String, val lines: List<String>)

val ABOUT_SECTIONS = listOf(
    AboutSection(
        "这是什么",
        listOf(
            "油图布儿歌（英文名 Oil Painting Canvas）是为家里孩子自制的儿歌播放器，用投影仪或电视的遥控器就能自己选歌。",
            "它不是 YouTube 或 Google 的产品，和 YouTube、Google 以及软件里出现的各个儿歌频道没有任何关系，也没有得到它们的授权或认可。",
        ),
    ),
    AboutSection(
        "软件来源",
        listOf(
            "电视版的界面和用法沿用电脑版「油图布」。电脑版基于开源项目 FreeTube（freetubeapp.io，AGPL-3.0 许可证）。",
            "电视版是另外编写的程序，不包含 FreeTube 的代码。去广告和高清视频依靠开源项目 Invidious（invidious.io，AGPL-3.0 许可证）：视频列表和视频地址都经由 Invidious 服务器获取，默认使用公共服务器 yt.chocolatemoo53.com。",
            "用到的其他开源软件（均为 Apache-2.0 许可证）：AndroidX Media3 ExoPlayer（视频播放）、Jetpack Compose（界面）、OkHttp（网络）、Coil（图片）、ZXing（二维码）。",
            "感谢以上项目的作者和维护者。",
        ),
    ),
    AboutSection(
        "视频来源与版权",
        listOf(
            "所有视频、封面、频道名称和头像都来自 YouTube，版权归原作者和频道所有。本软件不保存、不修改、也不重新发布任何视频内容。",
            "离线缓存只把视频临时保存在本机上，供自己家观看，不会分享给任何人。",
            "如果喜欢某个频道，请到 YouTube 上关注并支持原作者。",
        ),
    ),
    AboutSection(
        "视频内容仅供个人观看",
        listOf(
            "通过本软件看到的视频属于各自的版权人，只能个人和家庭观看，不得用于商业用途，也不得录制、下载后再传播。",
            "绕开广告和不登录播放的方式可能不符合 YouTube 的服务条款。是否使用、如何使用，由使用者自行判断并承担责任。",
        ),
    ),
    AboutSection(
        "隐私",
        listOf(
            "不需要账号，也不登录。本软件不收集、不上传任何个人信息和使用记录。",
            "频道列表、最喜欢、播放记录和视频缓存只保存在这台投影仪上，卸载软件后全部删除。",
            "观看时的网络请求会发给 Invidious 服务器和 YouTube 的图片服务器（i.ytimg.com、yt3.ggpht.com），它们能看到这台设备的 IP 地址。",
        ),
    ),
    AboutSection(
        "免责声明",
        listOf(
            "本软件按现状提供，不提供任何形式的保证。YouTube 或 Invidious 的变化可能让部分或全部功能随时失效。",
            "频道是家长自己挑选的，视频内容由各频道负责。请家长先看过再给孩子看，并陪伴孩子使用。",
        ),
    ),
    AboutSection(
        "许可证",
        listOf(
            "油图布儿歌是自由软件，按 GNU 通用公共许可证第 3 版（GPL-3.0）发布：可以自由地使用、学习、修改和再分发；再分发（包括修改后的版本）时必须同样按 GPL-3.0 开源并附上源码。",
            "许可证只适用于本软件自己的源代码，不包括软件里显示的视频、封面和频道名称。",
            "源代码：github.com/shuangyangtt/oil-painting-canvas",
        ),
    ),
)

fun aboutJson(version: String): JSONObject = JSONObject()
    .put("version", version)
    .put(
        "sections",
        JSONArray(ABOUT_SECTIONS.map { JSONObject().put("title", it.title).put("lines", JSONArray(it.lines)) }),
    )

fun appVersion(context: android.content.Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
} catch (_: Exception) {
    ""
}

/** About / disclaimer page (parent settings → 关于). Up/Down scroll, Back returns. */
@Composable
fun AboutScreen(state: AppState) {
    val context = LocalContext.current
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BackHandler { state.screen = Screen.Parent }

    Box(
        Modifier
            .fillMaxSize()
            .background(C.bg)
            .focusRequester(focus)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> { scope.launch { scroll.animateScrollBy(260f) }; true }
                    KeyEvent.KEYCODE_DPAD_UP -> { scope.launch { scroll.animateScrollBy(-260f) }; true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { state.screen = Screen.Parent; true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 120.dp, vertical = 40.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                T("关于油图布儿歌 · Oil Painting Canvas", size = 30.sp, weight = FontWeight.Bold)
                Spacer(Modifier.width(16.dp))
                T("版本 ${appVersion(context)}", size = 16.sp, color = C.dim)
            }
            Spacer(Modifier.height(6.dp))
            T("↑ ↓ 翻页 · 返回键或 OK 回到家长设置", size = 14.sp, color = C.faint)
            for (section in ABOUT_SECTIONS) {
                Spacer(Modifier.height(22.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(C.card.copy(alpha = 0.55f))
                        .border(1.dp, C.faint.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 24.dp, vertical = 18.dp),
                ) {
                    T(section.title, size = 20.sp, weight = FontWeight.Bold, color = C.yellow)
                    for (line in section.lines) {
                        Spacer(Modifier.height(8.dp))
                        T(line, size = 16.sp, color = C.text)
                    }
                }
            }
            Spacer(Modifier.height(60.dp))
        }
    }
}
