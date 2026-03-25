package com.wisp.app.ui.screen

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.stringResource
import com.wisp.app.R
import com.wisp.app.relay.RelayPool
import com.wisp.app.viewmodel.BlossomServersViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlossomServersScreen(
    viewModel: BlossomServersViewModel,
    relayPool: RelayPool,
    onBack: () -> Unit,
    signer: com.wisp.app.nostr.NostrSigner? = null
) {
    val servers by viewModel.servers.collectAsState()
    val newServerUrl by viewModel.newServerUrl.collectAsState()
    val error by viewModel.error.collectAsState()
    val published by viewModel.published.collectAsState()

    var draggedIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var itemHeightPx by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.blossom_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = newServerUrl,
                    onValueChange = { viewModel.updateNewServerUrl(it) },
                    label = { Text("https://...") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { viewModel.addServer() }) {
                    Icon(Icons.Default.Add, "Add server")
                }
            }

            error?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { viewModel.publishServerList(relayPool, signer = signer) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (published) stringResource(R.string.blossom_broadcast_sent) else stringResource(R.string.blossom_broadcast))
            }

            Spacer(Modifier.height(8.dp))

            if (servers.size > 1) {
                Text(
                    text = stringResource(R.string.blossom_drag_reorder),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))

            servers.forEachIndexed { index, url ->
                val isDragged = draggedIndex == index
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .then(
                            if (isDragged) Modifier
                                .zIndex(1f)
                                .offset { IntOffset(0, dragOffsetY.roundToInt()) }
                            else Modifier
                        )
                        .onGloballyPositioned { coords ->
                            if (itemHeightPx == 0f) {
                                itemHeightPx = coords.size.height.toFloat()
                            }
                        }
                ) {
                    if (servers.size > 1) {
                        Icon(
                            Icons.Default.DragHandle,
                            contentDescription = "Drag to reorder",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .pointerInput(servers.size) {
                                    detectVerticalDragGestures(
                                        onDragStart = {
                                            draggedIndex = index
                                            dragOffsetY = 0f
                                        },
                                        onVerticalDrag = { change, dragAmount ->
                                            change.consume()
                                            dragOffsetY += dragAmount

                                            if (itemHeightPx > 0f) {
                                                val steps =
                                                    (dragOffsetY / itemHeightPx).roundToInt()
                                                val targetIndex =
                                                    (draggedIndex + steps).coerceIn(
                                                        0,
                                                        servers.size - 1
                                                    )
                                                if (targetIndex != draggedIndex) {
                                                    viewModel.moveServer(
                                                        draggedIndex,
                                                        targetIndex
                                                    )
                                                    dragOffsetY -= (targetIndex - draggedIndex) * itemHeightPx
                                                    draggedIndex = targetIndex
                                                }
                                            }
                                        },
                                        onDragEnd = {
                                            draggedIndex = -1
                                            dragOffsetY = 0f
                                        },
                                        onDragCancel = {
                                            draggedIndex = -1
                                            dragOffsetY = 0f
                                        }
                                    )
                                }
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = url,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (index == 0) {
                            Text(
                                text = "Primary",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    IconButton(onClick = { viewModel.removeServer(url) }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Remove",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}
