package com.wisp.app.ui.screen

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.wisp.app.repo.DiscoveryState
import com.wisp.app.repo.ExtendedNetworkCache
import com.wisp.app.repo.ExtendedNetworkRepository
import com.wisp.app.repo.NetworkStats
import com.wisp.app.repo.ProfileRepository
import com.wisp.app.ui.component.ProfilePicture
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocialGraphScreen(
    extendedNetworkRepo: ExtendedNetworkRepository,
    profileRepo: ProfileRepository,
    userPubkey: String?,
    onBack: () -> Unit
) {
    val discoveryState by extendedNetworkRepo.discoveryState.collectAsState()
    val cachedNetwork by extendedNetworkRepo.cachedNetwork.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        extendedNetworkRepo.resetDiscoveryState()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Social Graph") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (val state = discoveryState) {
                is DiscoveryState.Idle -> {
                    IdleContent(
                        cachedNetwork = cachedNetwork,
                        extendedNetworkRepo = extendedNetworkRepo,
                        profileRepo = profileRepo,
                        userPubkey = userPubkey,
                        onRecompute = { scope.launch { extendedNetworkRepo.discoverNetwork() } }
                    )
                }
                is DiscoveryState.FetchingFollowLists -> {
                    ProgressContent(
                        label = "Fetching follow lists...",
                        progress = if (state.total > 0) state.fetched.toFloat() / state.total else 0f,
                        detail = "${state.fetched} / ${state.total}"
                    )
                }
                is DiscoveryState.ComputingNetwork -> {
                    ProgressContent(
                        label = "Computing network...",
                        detail = "${state.uniqueUsers} unique users"
                    )
                }
                is DiscoveryState.Filtering -> {
                    ProgressContent(
                        label = "Filtering...",
                        detail = "${state.qualified} qualified"
                    )
                }
                is DiscoveryState.FetchingRelayLists -> {
                    ProgressContent(
                        label = "Fetching relay lists...",
                        progress = if (state.total > 0) state.fetched.toFloat() / state.total else 0f,
                        detail = "${state.fetched} / ${state.total}"
                    )
                }
                is DiscoveryState.Complete -> {
                    CompleteContent(
                        stats = state.stats,
                        onDone = { extendedNetworkRepo.resetDiscoveryState() }
                    )
                }
                is DiscoveryState.Failed -> {
                    FailedContent(
                        reason = state.reason,
                        onRetry = { scope.launch { extendedNetworkRepo.discoverNetwork() } }
                    )
                }
            }
        }
    }
}

// --- Graph data model ---

private data class GraphNode(
    val pubkey: String,
    val pictureUrl: String?,
    val x: Float, // offset from center in dp
    val y: Float,
    val size: Int  // dp
)

private data class GraphEdge(
    val from: GraphNode,
    val to: GraphNode
)

private data class GraphLayout(
    val center: GraphNode,
    val innerNodes: List<GraphNode>,
    val outerNodes: List<GraphNode>,
    val edges: List<GraphEdge>
)

private fun computeGraphLayout(
    cache: ExtendedNetworkCache,
    extendedNetworkRepo: ExtendedNetworkRepository,
    profileRepo: ProfileRepository,
    userPubkey: String?
): GraphLayout {
    val centerSize = 56
    val innerSize = 36
    val outerSize = 28
    val innerRadius = 90f  // dp from center
    val outerRadius = 155f // dp from center

    // Center node
    val centerPic = userPubkey?.let { profileRepo.get(it)?.picture }
    val centerNode = GraphNode(
        pubkey = userPubkey ?: "",
        pictureUrl = centerPic,
        x = 0f, y = 0f,
        size = centerSize
    )

    // Pick up to 8 first-degree follows, preferring those with profile pictures
    val firstDegree = cache.firstDegreePubkeys.toList()
    val withPics = firstDegree.filter { profileRepo.get(it)?.picture != null }
    val withoutPics = firstDegree.filter { profileRepo.get(it)?.picture == null }
    val selectedInner = (withPics.shuffled() + withoutPics.shuffled()).take(8)

    val innerNodes = selectedInner.mapIndexed { i, pk ->
        val angle = (2.0 * Math.PI * i / selectedInner.size) - Math.PI / 2
        GraphNode(
            pubkey = pk,
            pictureUrl = profileRepo.get(pk)?.picture,
            x = (innerRadius * cos(angle)).toFloat(),
            y = (innerRadius * sin(angle)).toFloat(),
            size = innerSize
        )
    }

    // Pick up to 14 qualified (2nd-degree) pubkeys, clustered near the inner node that connects them
    val qualified = cache.qualifiedPubkeys.toList()
    val qualWithPics = qualified.filter { profileRepo.get(it)?.picture != null }
    val qualWithoutPics = qualified.filter { profileRepo.get(it)?.picture == null }
    val candidateOuter = (qualWithPics.shuffled() + qualWithoutPics.shuffled()).take(14)

    val outerNodes = mutableListOf<GraphNode>()
    val outerEdges = mutableListOf<GraphEdge>()

    // Track how many outer nodes are assigned to each inner node for angular spread
    val innerAssignCount = mutableMapOf<Int, Int>()

    for (pk in candidateOuter) {
        // Find which inner node follows this pubkey
        val followers = extendedNetworkRepo.getFollowedBy(pk)
        val parentIdx = innerNodes.indexOfFirst { it.pubkey in followers }
        val parent = if (parentIdx >= 0) innerNodes[parentIdx] else innerNodes.randomOrNull() ?: continue
        val pIdx = if (parentIdx >= 0) parentIdx else 0

        val assignNum = innerAssignCount.getOrDefault(pIdx, 0)
        innerAssignCount[pIdx] = assignNum + 1

        // Angle from center to parent, then spread outer nodes around it
        val parentAngle = Math.atan2(parent.y.toDouble(), parent.x.toDouble())
        val spreadAngle = (assignNum - 0.5) * 0.35 // ~20 degree spread between siblings
        val angle = parentAngle + spreadAngle

        val node = GraphNode(
            pubkey = pk,
            pictureUrl = profileRepo.get(pk)?.picture,
            x = (outerRadius * cos(angle)).toFloat(),
            y = (outerRadius * sin(angle)).toFloat(),
            size = outerSize
        )
        outerNodes.add(node)
        outerEdges.add(GraphEdge(parent, node))
    }

    // Edges from center to inner nodes
    val centerEdges = innerNodes.map { GraphEdge(centerNode, it) }

    return GraphLayout(
        center = centerNode,
        innerNodes = innerNodes,
        outerNodes = outerNodes,
        edges = centerEdges + outerEdges
    )
}

// --- Composables ---

@Composable
private fun IdleContent(
    cachedNetwork: ExtendedNetworkCache?,
    extendedNetworkRepo: ExtendedNetworkRepository,
    profileRepo: ProfileRepository,
    userPubkey: String?,
    onRecompute: () -> Unit
) {
    Spacer(modifier = Modifier.height(16.dp))

    if (cachedNetwork != null) {
        SocialGraphVisualization(
            cachedNetwork = cachedNetwork,
            extendedNetworkRepo = extendedNetworkRepo,
            profileRepo = profileRepo,
            userPubkey = userPubkey
        )

        Spacer(modifier = Modifier.height(16.dp))

        StatsCard(cachedNetwork.stats)

        Spacer(modifier = Modifier.height(8.dp))

        val date = Date(cachedNetwork.computedAtEpoch * 1000)
        val fmt = SimpleDateFormat("MMM d, yyyy  h:mm a", Locale.getDefault())
        Text(
            text = "Last computed: ${fmt.format(date)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedButton(onClick = onRecompute) {
            Text("Recompute")
        }
    } else {
        Text(
            text = "Social graph has not been computed yet.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onRecompute) {
            Text("Compute Now")
        }
    }
}

@Composable
private fun SocialGraphVisualization(
    cachedNetwork: ExtendedNetworkCache,
    extendedNetworkRepo: ExtendedNetworkRepository,
    profileRepo: ProfileRepository,
    userPubkey: String?
) {
    val layout = remember(cachedNetwork) {
        computeGraphLayout(cachedNetwork, extendedNetworkRepo, profileRepo, userPubkey)
    }

    val boxSize = 340.dp
    val density = LocalDensity.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceVariantColor = MaterialTheme.colorScheme.surfaceVariant

    Box(
        modifier = Modifier.size(boxSize),
        contentAlignment = Alignment.Center
    ) {
        // Connection lines drawn on Canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerX = size.width / 2f
            val centerY = size.height / 2f

            for (edge in layout.edges) {
                val fromX = centerX + with(density) { edge.from.x.dp.toPx() }
                val fromY = centerY + with(density) { edge.from.y.dp.toPx() }
                val toX = centerX + with(density) { edge.to.x.dp.toPx() }
                val toY = centerY + with(density) { edge.to.y.dp.toPx() }

                // Cubic bezier curving slightly toward center
                val midX = (fromX + toX) / 2f
                val midY = (fromY + toY) / 2f
                val ctrlOffsetX = (centerX - midX) * 0.2f
                val ctrlOffsetY = (centerY - midY) * 0.2f

                val path = Path().apply {
                    moveTo(fromX, fromY)
                    cubicTo(
                        fromX + ctrlOffsetX, fromY + ctrlOffsetY,
                        toX + ctrlOffsetX, toY + ctrlOffsetY,
                        toX, toY
                    )
                }

                drawPath(
                    path = path,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = 0.4f),
                            surfaceVariantColor.copy(alpha = 0.15f)
                        ),
                        start = Offset(fromX, fromY),
                        end = Offset(toX, toY)
                    ),
                    style = Stroke(width = 1.5f.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        // Center node
        ProfilePicture(
            url = layout.center.pictureUrl,
            size = layout.center.size,
            highlighted = true,
            modifier = Modifier.align(Alignment.Center)
        )

        // Inner ring nodes
        for (node in layout.innerNodes) {
            val halfSize = node.size / 2
            ProfilePicture(
                url = node.pictureUrl,
                size = node.size,
                modifier = Modifier.offset {
                    IntOffset(
                        x = (node.x.dp.toPx() - halfSize.dp.toPx()).roundToInt(),
                        y = (node.y.dp.toPx() - halfSize.dp.toPx()).roundToInt()
                    )
                }
            )
        }

        // Outer ring nodes
        for (node in layout.outerNodes) {
            val halfSize = node.size / 2
            ProfilePicture(
                url = node.pictureUrl,
                size = node.size,
                modifier = Modifier.offset {
                    IntOffset(
                        x = (node.x.dp.toPx() - halfSize.dp.toPx()).roundToInt(),
                        y = (node.y.dp.toPx() - halfSize.dp.toPx()).roundToInt()
                    )
                }
            )
        }
    }
}

@Composable
private fun StatsCard(stats: NetworkStats) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatRow("Follows (1st degree)", stats.firstDegreeCount.toString())
        StatRow("2nd degree users", stats.totalSecondDegree.toString())
        StatRow("Qualified (threshold)", stats.qualifiedCount.toString())
        StatRow("Relays covered", stats.relaysCovered.toString())
    }
}

@Composable
private fun StatRow(label: String, value: String) {
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
        text = "Computation complete",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
    )

    Spacer(modifier = Modifier.height(16.dp))

    StatsCard(stats)

    Spacer(modifier = Modifier.height(24.dp))

    OutlinedButton(onClick = onDone) {
        Text("Done")
    }
}

@Composable
private fun FailedContent(
    reason: String,
    onRetry: () -> Unit
) {
    Spacer(modifier = Modifier.height(48.dp))

    Text(
        text = "Discovery failed",
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
        Text("Retry")
    }
}
