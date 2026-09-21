package com.wisp.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.wisp.app.R

/**
 * The "Description" bottom sheet for NIP-92 imeta alt text — a description
 * must be inspectable by sighted users too, not only announced to assistive
 * tech. Hosted by [AltBadgeWithSheet] and by the fullscreen viewers'
 * tappable caption previews.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AltDescriptionSheet(
    alt: String,
    visible: Boolean,
    onDismiss: () -> Unit
) {
    if (!visible) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Size follows the text: opens at a comfortable default height even for
    // one-liners, grows with the description up to ~55% of the screen, and
    // scrolls inside beyond that.
    val configuration = LocalConfiguration.current
    val maxContentHeight = (configuration.screenHeightDp * 0.55f).dp
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = stringResource(R.string.alt_description_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.padding(top = 10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 280.dp, max = maxContentHeight)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = alt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/**
 * The "ALT" chip shown on images that carry alt text, opening
 * [AltDescriptionSheet] on tap.
 *
 * Place this as a **sibling** of the image's tap target (e.g. aligned
 * BottomStart inside the image's Box), never nested inside it, so a screen
 * reader gets two clean focus stops: the image, then "View image description".
 */
@Composable
fun AltBadgeWithSheet(
    alt: String,
    modifier: Modifier = Modifier
) {
    var showDescription by remember { mutableStateOf(false) }

    val viewDescription = stringResource(R.string.cd_view_image_description)
    Text(
        text = stringResource(R.string.alt_badge),
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.65f))
            .clickable { showDescription = true }
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .semantics { contentDescription = viewDescription }
    )

    AltDescriptionSheet(
        alt = alt,
        visible = showDescription,
        onDismiss = { showDescription = false }
    )
}
