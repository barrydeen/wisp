package cooking.zap.app.ui.screen

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cooking.zap.app.R
import cooking.zap.app.viewmodel.AboutViewModel
import cooking.zap.app.viewmodel.AboutViewModel.Expiry
import cooking.zap.app.viewmodel.AboutViewModel.MembershipRow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * About — the app's membership readout and its links to the policies published
 * on the website (concern: about-screen).
 *
 * Two jobs, both legibility rather than capability:
 *
 * 1. **Membership is visible somewhere.** Before this screen, a member who
 *    bought Cook+ on the web and signed in here saw an app identical to a
 *    stranger's: `MembershipStatus` was read in exactly one place (the Sous
 *    Chef upsell banner), and only to decide whether to show an upsell. An
 *    entitlement nothing ever confirms is indistinguishable from one that
 *    never arrived.
 * 2. **The policies are reachable from inside the app.** The website publishes
 *    four; the app linked to none of them.
 *
 * This screen is deliberately **non-transactional** — it reports status and
 * opens published pages, and offers no path to spend money. Nothing here is
 * gated on `MEMBERSHIP_LINKOUT_ENABLED`, because nothing here is a purchase
 * entry point.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    viewModel: AboutViewModel,
    onRefreshMembership: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val row by viewModel.row.collectAsState()

    LaunchedEffect(Unit) { onRefreshMembership() }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.drawer_membership_policies)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Hidden = no signer, so no authenticated answer is possible. The
            // whole section goes away rather than leaving a header over an
            // empty or guessed line.
            if (row != MembershipRow.Hidden) {
                Text(
                    text = stringResource(R.string.about_membership_header),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                MembershipLine(row)

                Spacer(Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(Modifier.height(24.dp))
            }

            Text(
                text = stringResource(R.string.about_policies_header),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            PolicyLinks.ALL.forEach { link ->
                PolicyRow(label = stringResource(link.labelRes)) {
                    // A device with no browser is the only documented failure
                    // here; swallow it rather than crash a settings screen.
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url)))
                    } catch (e: ActivityNotFoundException) {
                        // no-op
                    }
                }
            }
        }
    }
}

/**
 * The membership line itself — **a state and a date, no tier noun.**
 *
 * The tier the server returns is unusable (it normalizes to the *free* tier's
 * name for every real member — see [AboutViewModel]), so this renders the two
 * things that survive verification: whether the membership is active, and when
 * it runs out.
 *
 * Every state is a distinct sentence. In particular [MembershipRow.Unavailable]
 * (we could not check) never renders as [MembershipRow.Inactive] (you are not a
 * member), because a member told "no membership" by a network blip is exactly
 * the confusion this screen exists to remove.
 */
@Composable
private fun MembershipLine(row: MembershipRow) {
    when (row) {
        // Handled by the caller — the section is not rendered at all.
        MembershipRow.Hidden -> Unit

        MembershipRow.Checking ->
            Secondary(stringResource(R.string.about_membership_checking))

        MembershipRow.Inactive ->
            Secondary(stringResource(R.string.about_membership_none))

        MembershipRow.Unavailable ->
            Secondary(stringResource(R.string.about_membership_unavailable))

        is MembershipRow.Active -> Column {
            Text(
                text = stringResource(R.string.about_membership_active),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            when (val expiry = row.expiry) {
                // No usable date on the record — "Active" above already said
                // everything we can stand behind.
                Expiry.Unknown -> Unit
                is Expiry.On -> Secondary(
                    stringResource(
                        R.string.about_membership_paid_through,
                        formatDate(expiry.epochMillis)
                    )
                )
            }
        }
    }
}

@Composable
private fun Secondary(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The date is formatted here rather than in the ViewModel because it is
 * locale-dependent; parsing it is [AboutViewModel.expiry]'s job and is
 * unit-tested there.
 */
private fun formatDate(epochMillis: Long): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))

@Composable
private fun PolicyRow(label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Icon(
            Icons.AutoMirrored.Outlined.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The four policies the website publishes, each labelled with **that page's own
 * heading** rather than a paraphrase — a settings row that promises something
 * the destination does not call itself is a small lie, and these four are
 * exactly the pages a store reviewer follows.
 */
internal object PolicyLinks {

    internal data class Link(@StringRes val labelRes: Int, val url: String)

    private const val SITE_ORIGIN = "https://zap.cooking"

    val ALL = listOf(
        Link(R.string.about_privacy_policy, "$SITE_ORIGIN/privacy"),
        Link(R.string.about_terms, "$SITE_ORIGIN/terms"),
        Link(R.string.about_child_safety, "$SITE_ORIGIN/child-safety"),
        Link(R.string.about_delete_account, "$SITE_ORIGIN/delete-account"),
    )
}
