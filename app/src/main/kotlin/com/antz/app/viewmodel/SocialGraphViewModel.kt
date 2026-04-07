package com.antz.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antz.app.repo.ExtendedNetworkCache
import com.antz.app.repo.ProfileRepository
import com.antz.app.repo.SocialGraphDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt

data class GraphNode(
    val pubkey: String,
    val degree: Int, // 0 = user, 1 = first-degree, 2 = second-degree
    val followerCount: Int,
    val radius: Float,
    var x: Float,
    var y: Float,
    var vx: Float = 0f,
    var vy: Float = 0f
)

data class GraphEdge(
    val fromPubkey: String,
    val toPubkey: String
)

data class RankedAccount(
    val pubkey: String,
    val followerCount: Int
)

class SocialGraphViewModel : ViewModel() {

    private val _nodes = MutableStateFlow<List<GraphNode>>(emptyList())
    val nodes: StateFlow<List<GraphNode>> = _nodes

    private val _edges = MutableStateFlow<List<GraphEdge>>(emptyList())
    val edges: StateFlow<List<GraphEdge>> = _edges

    private val _selectedNode = MutableStateFlow<GraphNode?>(null)
    val selectedNode: StateFlow<GraphNode?> = _selectedNode

    private val _topAccounts = MutableStateFlow<List<RankedAccount>>(emptyList())
    val topAccounts: StateFlow<List<RankedAccount>> = _topAccounts

    private val _simulationSettled = MutableStateFlow(false)
    val simulationSettled: StateFlow<Boolean> = _simulationSettled

    private var simulationJob: Job? = null
    private var initialized = false

    fun selectNode(node: GraphNode?) {
        _selectedNode.value = node
    }

    fun init(
        cache: ExtendedNetworkCache,
        socialGraphDb: SocialGraphDb,
        profileRepo: ProfileRepository,
        userPubkey: String?
    ) {
        if (initialized) return
        initialized = true

        viewModelScope.launch(Dispatchers.Default) {
            val firstDegree = cache.firstDegreePubkeys.toList()
            val firstDegreeSet = cache.firstDegreePubkeys

            // Get top accounts ranked by within-network follower count
            val topRanked = socialGraphDb.getTopByFollowerCount(200, firstDegreeSet)
            val followerCountMap = topRanked.associate { it.first to it.second }

            // Select nodes: top 15 first-degree by follower count
            val top1stDegree = firstDegree
                .sortedByDescending { followerCountMap[it] ?: 0 }
                .take(15)
            val top1stSet = top1stDegree.toSet()

            // Top 64 second-degree (qualified) by follower count, excluding first-degree and user
            val excludeSet = firstDegreeSet + setOfNotNull(userPubkey)
            val top2ndDegree = topRanked
                .filter { it.first !in excludeSet }
                .take(64)
                .map { it.first }

            // Build nodes
            val nodeList = mutableListOf<GraphNode>()

            // User at center
            if (userPubkey != null) {
                nodeList.add(GraphNode(
                    pubkey = userPubkey,
                    degree = 0,
                    followerCount = followerCountMap[userPubkey] ?: 0,
                    radius = 24f,
                    x = 0f, y = 0f
                ))
            }

            // First-degree in a ring at radius 200
            top1stDegree.forEachIndexed { i, pk ->
                val angle = (2.0 * Math.PI * i / top1stDegree.size) - Math.PI / 2
                val fc = followerCountMap[pk] ?: 0
                val r = computeRadius(fc, degree = 1)
                nodeList.add(GraphNode(
                    pubkey = pk,
                    degree = 1,
                    followerCount = fc,
                    radius = r,
                    x = (200f * Math.cos(angle)).toFloat(),
                    y = (200f * Math.sin(angle)).toFloat()
                ))
            }

            // Second-degree at radius 400 near their strongest 1st-degree connection
            val nodesByPubkey = nodeList.associateBy { it.pubkey }
            val edgeList = mutableListOf<GraphEdge>()

            for (pk in top2ndDegree) {
                val followers = socialGraphDb.getFollowers(pk)
                // Find which 1st-degree nodes follow this pubkey
                val connecting1st = followers.filter { it in top1stSet }
                val parent = connecting1st
                    .mapNotNull { nodesByPubkey[it] }
                    .maxByOrNull { it.followerCount }

                val parentX = parent?.x ?: 0f
                val parentY = parent?.y ?: 0f
                val parentAngle = Math.atan2(parentY.toDouble(), parentX.toDouble())
                val jitter = (Math.random() - 0.5) * 0.8
                val angle = parentAngle + jitter

                val fc = followerCountMap[pk] ?: 0
                val r = computeRadius(fc, degree = 2)
                val node = GraphNode(
                    pubkey = pk,
                    degree = 2,
                    followerCount = fc,
                    radius = r,
                    x = (400f * Math.cos(angle)).toFloat(),
                    y = (400f * Math.sin(angle)).toFloat()
                )
                nodeList.add(node)

                // Add edges from connecting 1st-degree nodes
                for (conn in connecting1st) {
                    if (conn in top1stSet) {
                        edgeList.add(GraphEdge(conn, pk))
                    }
                }
            }

            // Add edges from user to 1st-degree
            if (userPubkey != null) {
                for (pk in top1stDegree) {
                    edgeList.add(GraphEdge(userPubkey, pk))
                }
            }

            // Top accounts list (top 30)
            _topAccounts.value = topRanked
                .filter { it.first !in setOfNotNull(userPubkey) }
                .take(30)
                .map { RankedAccount(it.first, it.second) }

            _nodes.value = nodeList
            _edges.value = edgeList

            // Start force simulation
            runSimulation()
        }
    }

    private fun computeRadius(followerCount: Int, degree: Int): Float {
        val base = when (degree) {
            0 -> 24f
            1 -> 12f
            else -> 7f
        }
        val maxR = when (degree) {
            0 -> 24f
            1 -> 22f
            else -> 16f
        }
        val scale = when (degree) {
            1 -> 2.5f
            else -> 2.0f
        }
        val r = base + (ln((followerCount + 1).toDouble()) / ln(2.0)).toFloat() * scale
        return min(r, maxR)
    }

    private fun runSimulation() {
        simulationJob?.cancel()
        simulationJob = viewModelScope.launch(Dispatchers.Default) {
            val maxTicks = 300
            val settleThreshold = 0.5f
            val damping = 0.88f
            val repulsionK = 8000f
            val attractionK = 0.005f
            val restLength = 120f
            val centerGravity = 0.01f
            val frameDelay = 33L // ~30fps

            for (tick in 0 until maxTicks) {
                if (!isActive) break

                val currentNodes = _nodes.value.toMutableList()
                val edges = _edges.value
                val n = currentNodes.size
                if (n == 0) break

                // Build pubkey -> index map
                val indexMap = HashMap<String, Int>(n)
                for (i in currentNodes.indices) {
                    indexMap[currentNodes[i].pubkey] = i
                }

                // Reset forces
                val fx = FloatArray(n)
                val fy = FloatArray(n)

                // Repulsion between all pairs
                for (i in 0 until n) {
                    val ni = currentNodes[i]
                    for (j in i + 1 until n) {
                        val nj = currentNodes[j]
                        var dx = ni.x - nj.x
                        var dy = ni.y - nj.y
                        var distSq = dx * dx + dy * dy
                        if (distSq < 1f) {
                            dx = (Math.random().toFloat() - 0.5f) * 2f
                            dy = (Math.random().toFloat() - 0.5f) * 2f
                            distSq = dx * dx + dy * dy
                        }
                        val dist = sqrt(distSq)
                        val radiusScale = (ni.radius + nj.radius) / 20f
                        val force = repulsionK * radiusScale / distSq
                        val forceX = force * dx / dist
                        val forceY = force * dy / dist
                        fx[i] += forceX
                        fy[i] += forceY
                        fx[j] -= forceX
                        fy[j] -= forceY
                    }
                }

                // Attraction along edges
                for (edge in edges) {
                    val iFrom = indexMap[edge.fromPubkey] ?: continue
                    val iTo = indexMap[edge.toPubkey] ?: continue
                    val nFrom = currentNodes[iFrom]
                    val nTo = currentNodes[iTo]
                    val dx = nTo.x - nFrom.x
                    val dy = nTo.y - nFrom.y
                    val dist = sqrt(dx * dx + dy * dy)
                    if (dist < 0.01f) continue
                    val displacement = dist - restLength
                    val force = attractionK * displacement
                    val forceX = force * dx / dist
                    val forceY = force * dy / dist
                    fx[iFrom] += forceX
                    fy[iFrom] += forceY
                    fx[iTo] -= forceX
                    fy[iTo] -= forceY
                }

                // Center gravity
                for (i in 0 until n) {
                    val ni = currentNodes[i]
                    fx[i] -= centerGravity * ni.x
                    fy[i] -= centerGravity * ni.y
                }

                // Apply forces, update velocities and positions
                var maxVelocity = 0f
                for (i in 0 until n) {
                    val node = currentNodes[i]
                    if (node.degree == 0) {
                        // Pin user at center
                        currentNodes[i] = node.copy(x = 0f, y = 0f, vx = 0f, vy = 0f)
                        continue
                    }
                    val newVx = (node.vx + fx[i]) * damping
                    val newVy = (node.vy + fy[i]) * damping
                    val newX = node.x + newVx
                    val newY = node.y + newVy
                    currentNodes[i] = node.copy(x = newX, y = newY, vx = newVx, vy = newVy)
                    val vel = sqrt(newVx * newVx + newVy * newVy)
                    if (vel > maxVelocity) maxVelocity = vel
                }

                _nodes.value = currentNodes

                if (maxVelocity < settleThreshold && tick > 50) {
                    _simulationSettled.value = true
                    break
                }

                delay(frameDelay)
            }
            _simulationSettled.value = true
        }
    }

    override fun onCleared() {
        super.onCleared()
        simulationJob?.cancel()
    }
}
