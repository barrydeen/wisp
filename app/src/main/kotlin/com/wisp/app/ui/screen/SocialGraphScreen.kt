package com.wisp.app.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import android.content.res.Configuration
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.stringResource
import com.wisp.app.R
import com.wisp.app.nostr.Nip19
import com.wisp.app.nostr.hexToByteArray
import com.wisp.app.repo.DiscoveryState
import com.wisp.app.repo.ExtendedNetworkCache
import com.wisp.app.repo.ExtendedNetworkRepository
import com.wisp.app.repo.NetworkStats
import com.wisp.app.repo.ProfileRepository
import com.wisp.app.repo.SocialGraphDb
import com.wisp.app.ui.component.ProfilePicture
import com.wisp.app.viewmodel.GraphNode
import com.wisp.app.viewmodel.RankedAccount
import com.wisp.app.viewmodel.SocialGraphViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import kotlin.math.roundToInt
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocialGraphScreen(
    extendedNetworkRepo: ExtendedNetworkRepository,
    profileRepo: ProfileRepository,
    socialGraphDb: SocialGraphDb,
    userPubkey: String?,
    onBack: () -> Unit,
    onNavigateToProfile: (String) -> Unit,
    onNetworkDiscovered: () -> Unit = {}
) {
    val discoveryState by extendedNetworkRepo.discoveryState.collectAsState()
    val cachedNetwork by extendedNetworkRepo.cachedNetwork.collectAsState()
    val scope = rememberCoroutineScope()
    val graphViewModel: SocialGraphViewModel = viewModel()

    val isComputing = discoveryState is DiscoveryState.FetchingFollowLists ||
        discoveryState is DiscoveryState.BuildingGraph ||
        discoveryState is DiscoveryState.ComputingNetwork ||
        discoveryState is DiscoveryState.Filtering ||
        discoveryState is DiscoveryState.FetchingRelayLists

    // Prevent leaving while computing
    BackHandler(enabled = isComputing) { /* block back navigation */ }

    // Notify once when discovery completes so the feed can resubscribe with new pubkeys
    var notifiedComplete by remember { mutableStateOf(false) }
    LaunchedEffect(discoveryState) {
        if (discoveryState is DiscoveryState.Complete && !notifiedComplete) {
            notifiedComplete = true
            onNetworkDiscovered()
        } else if (discoveryState !is DiscoveryState.Complete) {
            notifiedComplete = false
        }
    }

    LaunchedEffect(Unit) {
        extendedNetworkRepo.resetDiscoveryState()
    }

    // Initialize graph when cache is available
    LaunchedEffect(cachedNetwork) {
        val cache = cachedNetwork ?: return@LaunchedEffect
        graphViewModel.init(cache, socialGraphDb, profileRepo, userPubkey)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.drawer_social_graph)) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !isComputing) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        when (val state = discoveryState) {
            is DiscoveryState.Idle -> {
                if (cachedNetwork != null) {
                    GraphContent(
                        cachedNetwork = cachedNetwork!!,
                        graphViewModel = graphViewModel,
                        profileRepo = profileRepo,
                        extendedNetworkRepo = extendedNetworkRepo,
                        socialGraphDb = socialGraphDb,
                        onNavigateToProfile = onNavigateToProfile,
                        onRecompute = { scope.launch { extendedNetworkRepo.discoverNetwork() } },
                        modifier = Modifier.padding(padding)
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(modifier = Modifier.height(48.dp))
                        Text(
                            text = stringResource(R.string.social_not_computed),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(onClick = { scope.launch { extendedNetworkRepo.discoverNetwork() } }) {
                            Text(stringResource(R.string.social_compute_now))
                        }
                    }
                }
            }
            is DiscoveryState.FetchingFollowLists -> {
                ProgressColumn(padding) {
                    ProgressContent(
                        label = stringResource(R.string.social_fetching_follows),
                        progress = if (state.total > 0) state.fetched.toFloat() / state.total else 0f,
                        detail = "${state.fetched} / ${state.total}"
                    )
                    ComputingWarning()
                }
            }
            is DiscoveryState.BuildingGraph -> {
                ProgressColumn(padding) {
                    ProgressContent(
                        label = stringResource(R.string.social_building_graph),
                        progress = if (state.total > 0) state.processed.toFloat() / state.total else 0f,
                        detail = "${state.processed} / ${state.total}"
                    )
                    ComputingWarning()
                }
            }
            is DiscoveryState.ComputingNetwork -> {
                ProgressColumn(padding) {
                    ProgressContent(
                        label = stringResource(R.string.social_computing_network),
                        detail = "${state.uniqueUsers} unique users"
                    )
                    ComputingWarning()
                }
            }
            is DiscoveryState.Filtering -> {
                ProgressColumn(padding) {
                    ProgressContent(
                        label = stringResource(R.string.social_filtering),
                        detail = "${state.qualified} qualified"
                    )
                    ComputingWarning()
                }
            }
            is DiscoveryState.FetchingRelayLists -> {
                ProgressColumn(padding) {
                    ProgressContent(
                        label = stringResource(R.string.social_fetching_relays),
                        progress = if (state.total > 0) state.fetched.toFloat() / state.total else 0f,
                        detail = "${state.fetched} / ${state.total}"
                    )
                    ComputingWarning()
                }
            }
            is DiscoveryState.Complete -> {
                ProgressColumn(padding) {
                    CompleteContent(
                        stats = state.stats,
                        onDone = { extendedNetworkRepo.resetDiscoveryState() }
                    )
                }
            }
            is DiscoveryState.Failed -> {
                ProgressColumn(padding) {
                    FailedContent(
                        reason = state.reason,
                        onRetry = { scope.launch { extendedNetworkRepo.discoverNetwork() } }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressColumn(
    padding: PaddingValues,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        content()
    }
}

@Composable
private fun ComputingWarning() {
    Spacer(Modifier.height(24.dp))
    Text(
        text = stringResource(R.string.social_warning),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GraphContent(
    cachedNetwork: ExtendedNetworkCache,
    graphViewModel: SocialGraphViewModel,
    profileRepo: ProfileRepository,
    extendedNetworkRepo: ExtendedNetworkRepository,
    socialGraphDb: SocialGraphDb,
    onNavigateToProfile: (String) -> Unit,
    onRecompute: () -> Unit,
    modifier: Modifier = Modifier
) {
    val nodes by graphViewModel.nodes.collectAsState()
    val edges by graphViewModel.edges.collectAsState()
    val selectedNode by graphViewModel.selectedNode.collectAsState()
    val topAccounts by graphViewModel.topAccounts.collectAsState()

    val nodeDetailSheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.PartiallyExpanded
        )
    )

    val configuration = LocalConfiguration.current
    val sheetMaxHeight = (configuration.screenHeightDp * 0.7f).dp

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = 100.dp,
        sheetDragHandle = {
            // Compact drag handle — just the pill
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 32.dp, height = 4.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            shape = MaterialTheme.shapes.extraSmall
                        )
                )
            }
        },
        sheetContainerColor = MaterialTheme.colorScheme.surface,
        sheetContent = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = sheetMaxHeight)
            ) {
                // Compact header: title + stats inline
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.social_top_accounts),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.social_qualified_from, cachedNetwork.stats.qualifiedCount, cachedNetwork.stats.firstDegreeCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                // Top accounts list
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)
                ) {
                    itemsIndexed(topAccounts) { index, account ->
                        TopAccountRow(
                            rank = index + 1,
                            account = account,
                            profileRepo = profileRepo,
                            onClick = { onNavigateToProfile(account.pubkey) }
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            OutlinedButton(onClick = onRecompute) {
                                Text(stringResource(R.string.social_recompute))
                            }
                        }
                    }
                }
            }
        },
        modifier = modifier
    ) { innerPadding ->
        // Graph fills the background behind the sheet
        InteractiveGraphCanvas(
            nodes = nodes,
            edges = edges,
            profileRepo = profileRepo,
            onNodeTapped = { node -> graphViewModel.selectNode(node) },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
    }

    // Node detail bottom sheet (separate modal, on top of everything)
    if (selectedNode != null) {
        val node = selectedNode!!
        ModalBottomSheet(
            onDismissRequest = { graphViewModel.selectNode(null) },
            sheetState = nodeDetailSheetState
        ) {
            NodeDetailSheet(
                node = node,
                profileRepo = profileRepo,
                socialGraphDb = socialGraphDb,
                onViewProfile = {
                    scope.launch {
                        nodeDetailSheetState.hide()
                        graphViewModel.selectNode(null)
                        onNavigateToProfile(node.pubkey)
                    }
                }
            )
        }
    }
}

@Composable
private fun InteractiveGraphCanvas(
    nodes: List<GraphNode>,
    edges: List<com.wisp.app.viewmodel.GraphEdge>,
    profileRepo: ProfileRepository,
    onNodeTapped: (GraphNode) -> Unit,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.3f, 5f)
        offset += panChange
    }

    val density = LocalDensity.current
    val edgeColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .clipToBounds()
            .transformable(state = transformableState)
            .pointerInput(nodes) {
                detectTapGestures { tapOffset ->
                    val centerX = size.width / 2f
                    val centerY = size.height / 2f
                    val graphX = (tapOffset.x - centerX - offset.x) / scale
                    val graphY = (tapOffset.y - centerY - offset.y) / scale

                    var nearest: GraphNode? = null
                    var nearestDist = Float.MAX_VALUE
                    for (node in nodes) {
                        val nodePx = with(density) { node.x.dp.toPx() }
                        val nodePy = with(density) { node.y.dp.toPx() }
                        val dx = graphX - nodePx
                        val dy = graphY - nodePy
                        val dist = sqrt(dx * dx + dy * dy)
                        val radiusPx = with(density) { (node.radius + 8f).dp.toPx() }
                        if (dist < radiusPx && dist < nearestDist) {
                            nearest = node
                            nearestDist = dist
                        }
                    }
                    if (nearest != null) {
                        onNodeTapped(nearest)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Edge lines drawn on Canvas
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        ) {
            val centerX = size.width / 2f
            val centerY = size.height / 2f

            val nodePositions = HashMap<String, Offset>(nodes.size)
            for (node in nodes) {
                nodePositions[node.pubkey] = Offset(
                    centerX + node.x.dp.toPx(),
                    centerY + node.y.dp.toPx()
                )
            }

            for (edge in edges) {
                val from = nodePositions[edge.fromPubkey] ?: continue
                val to = nodePositions[edge.toPubkey] ?: continue
                drawLine(
                    color = edgeColor,
                    start = from,
                    end = to,
                    strokeWidth = 1f.dp.toPx()
                )
            }
        }

        // Profile picture nodes overlaid on top
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            contentAlignment = Alignment.Center
        ) {
            for (node in nodes) {
                val profile = remember(node.pubkey) { profileRepo.get(node.pubkey) }
                val sizeDp = (node.radius * 2).toInt()

                ProfilePicture(
                    url = profile?.picture,
                    size = sizeDp,
                    highlighted = node.degree == 0,
                    modifier = Modifier.offset {
                        IntOffset(
                            x = (node.x.dp.toPx() - sizeDp.dp.toPx() / 2f).roundToInt(),
                            y = (node.y.dp.toPx() - sizeDp.dp.toPx() / 2f).roundToInt()
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun StatsRow(stats: NetworkStats) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        StatChip(stringResource(R.string.social_stat_follows), stats.firstDegreeCount.toString())
        StatChip(stringResource(R.string.social_stat_2nd_degree), stats.totalSecondDegree.toString())
        StatChip(stringResource(R.string.social_stat_qualified), stats.qualifiedCount.toString())
        StatChip(stringResource(R.string.social_stat_relays), stats.relaysCovered.toString())
    }
}

@Composable
private fun StatChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TopAccountRow(
    rank: Int,
    account: RankedAccount,
    profileRepo: ProfileRepository,
    onClick: () -> Unit
) {
    val profile = remember(account.pubkey) { profileRepo.get(account.pubkey) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "#$rank",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(32.dp)
        )

        ProfilePicture(
            url = profile?.picture,
            size = 36
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile?.displayName
                    ?: profile?.name
                    ?: "${account.pubkey.take(8)}...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.social_followed_by, account.followerCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NodeDetailSheet(
    node: GraphNode,
    profileRepo: ProfileRepository,
    socialGraphDb: SocialGraphDb,
    onViewProfile: () -> Unit
) {
    val profile = remember(node.pubkey) { profileRepo.get(node.pubkey) }
    val followers = remember(node.pubkey) { socialGraphDb.getFollowers(node.pubkey) }
    val npub = remember(node.pubkey) {
        try {
            Nip19.npubEncode(node.pubkey.hexToByteArray())
        } catch (_: Exception) {
            node.pubkey.take(16) + "..."
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ProfilePicture(
            url = profile?.picture,
            size = 72
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = profile?.displayName
                ?: profile?.name
                ?: "${node.pubkey.take(8)}...",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )

        Text(
            text = "${npub.take(12)}...${npub.takeLast(8)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.social_followed_by_yours, node.followerCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        // Show small avatar row of followers (up to 6)
        val followerProfiles = remember(followers) {
            followers.take(6).map { pk -> pk to profileRepo.get(pk) }
        }

        if (followerProfiles.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy((-4).dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for ((pk, fp) in followerProfiles) {
                    ProfilePicture(
                        url = fp?.picture,
                        size = 28
                    )
                }
                if (followers.size > 6) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "+${followers.size - 6}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(onClick = onViewProfile) {
            Text(stringResource(R.string.social_view_profile))
        }
    }
}

// --- Progress / Complete / Failed states ---

@Composable
private fun ProgressContent(
    label: String,
    progress: Float? = null,
    detail: String
) {
    Spacer(modifier = Modifier.height(48.dp))

    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface
    )

    Spacer(modifier = Modifier.height(16.dp))

    if (progress != null) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth()
        )
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = detail,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun CompleteContent(
    stats: NetworkStats,
    onDone: () -> Unit
) {
    Spacer(modifier = Modifier.height(24.dp))

    Text(
        text = stringResource(R.string.social_complete),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
    )

    Spacer(modifier = Modifier.height(16.dp))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatDetailRow(stringResource(R.string.social_stat_follows_1st), stats.firstDegreeCount.toString())
        StatDetailRow(stringResource(R.string.social_stat_2nd_users), stats.totalSecondDegree.toString())
        StatDetailRow(stringResource(R.string.social_stat_qualified_thresh), stats.qualifiedCount.toString())
        StatDetailRow(stringResource(R.string.social_stat_relays_covered), stats.relaysCovered.toString())
    }

    Spacer(modifier = Modifier.height(24.dp))

    OutlinedButton(onClick = onDone) {
        Text(stringResource(R.string.social_done))
    }
}

@Composable
private fun StatDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun FailedContent(
    reason: String,
    onRetry: () -> Unit
) {
    Spacer(modifier = Modifier.height(48.dp))

    Text(
        text = stringResource(R.string.social_failed),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.error
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = reason,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(24.dp))

    Button(onClick = onRetry) {
        Text(stringResource(R.string.social_retry))
    }
}
