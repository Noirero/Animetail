package eu.kanade.tachiyomi.ui.browse

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.core.preference.asState
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.components.TabbedScreen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.connections.discord.DiscordRPCService
import eu.kanade.tachiyomi.data.connections.discord.DiscordScreen
import eu.kanade.tachiyomi.ui.browse.anime.extension.AnimeExtensionsScreenModel
import eu.kanade.tachiyomi.ui.browse.anime.extension.animeExtensionsTab
import eu.kanade.tachiyomi.ui.browse.anime.migration.sources.migrateAnimeSourceTab
import eu.kanade.tachiyomi.ui.browse.anime.source.animeSourcesTab
import eu.kanade.tachiyomi.ui.browse.anime.source.globalsearch.GlobalAnimeSearchScreen
import eu.kanade.tachiyomi.ui.browse.feed.FeedScreenModel
import eu.kanade.tachiyomi.ui.browse.feed.feedTab
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data object BrowseTab : Tab {
    private fun readResolve(): Any = BrowseTab

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_browse_enter)
            return TabOptions(
                index = 3u,
                title = stringResource(MR.strings.browse),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    // TODO: Find a way to let it open Global Anime/Manga Search depending on what Tab(e.g. Anime/Manga Source Tab) is open
    override suspend fun onReselect(navigator: Navigator) {
        navigator.push(GlobalAnimeSearchScreen())
    }

    private enum class ExtensionTabTarget {
        ANIME,
    }

    private val switchToExtensionTabChannel = Channel<ExtensionTabTarget>(1, BufferOverflow.DROP_OLDEST)

    fun showAnimeExtension() {
        switchToExtensionTabChannel.trySend(ExtensionTabTarget.ANIME)
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        // SY -->
        val hideFeedTab by remember { Injekt.get<UiPreferences>().hideFeedTab.asState(scope) }
        val feedTabInFront by remember { Injekt.get<UiPreferences>().feedTabInFront.asState(scope) }
        // SY <--

        // Hoisted for extensions tab's search bar
        val animeExtensionsScreenModel = rememberScreenModel { AnimeExtensionsScreenModel() }
        val animeExtensionsState by animeExtensionsScreenModel.state.collectAsState()

        val animeExtensionsTabContent = animeExtensionsTab(animeExtensionsScreenModel)

        // KMK -->
        val feedScreenModel = rememberScreenModel { FeedScreenModel() }
        // KMK <--

        val tabs = when {
            hideFeedTab ->
                listOf(
                    animeSourcesTab(),
                    animeExtensionsTabContent,
                    migrateAnimeSourceTab(),
                    )

            feedTabInFront ->
                listOf(
                    feedTab(
                        // KMK -->
                        feedScreenModel,
                        // KMK <--
                    ),
                    animeSourcesTab(),
                    animeExtensionsTabContent,
                    migrateAnimeSourceTab(),
                    )

            else ->
                listOf(
                    animeSourcesTab(),
                    feedTab(
                        // KMK -->
                        feedScreenModel,
                        // KMK <--
                    ),
                    animeExtensionsTabContent,
                    migrateAnimeSourceTab(),
                    )
            // SY <--
        }

        val animeExtensionsTabIndex = remember(tabs, animeExtensionsTabContent) {
            tabs.indexOf(animeExtensionsTabContent)
        }

        val state = rememberPagerState { tabs.size }

        TabbedScreen(
            titleRes = MR.strings.browse,
            tabs = tabs,
            state = state,
            mangaSearchQuery = "",
            onChangeMangaSearchQuery = {},
            animeSearchQuery = animeExtensionsState.searchQuery,
            onChangeAnimeSearchQuery = animeExtensionsScreenModel::search,
            animeExtensionsTabIndex = animeExtensionsTabIndex,
            mangaExtensionsTabIndex = -1,
            // KMK -->
            feedScreenModel = feedScreenModel,
            // KMK <--
            scrollable = true,
        )
        LaunchedEffect(animeExtensionsTabIndex) {
            switchToExtensionTabChannel.receiveAsFlow()
                .collectLatest { target ->
                    val tabIndex = when (target) {
                        ExtensionTabTarget.ANIME -> animeExtensionsTabIndex
                    }
                    if (tabIndex >= 0) {
                        state.scrollToPage(tabIndex)
                    }
                }
        }

        LaunchedEffect(Unit) {
            (context as? MainActivity)?.ready = true
            // AM (DISCORD) -->
            DiscordRPCService.setScreen(context, DiscordScreen.BROWSE)
            // <-- AM (DISCORD)
        }
    }
}
