package com.antz.app.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.antz.app.R
import com.antz.app.relay.RelayPool
import com.antz.app.viewmodel.ProfileViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProfileEditScreen(
    viewModel: ProfileViewModel,
    relayPool: RelayPool,
    onBack: () -> Unit,
    signer: com.antz.app.nostr.NostrSigner? = null
) {
    val name by viewModel.name.collectAsState()
    val about by viewModel.about.collectAsState()
    val picture by viewModel.picture.collectAsState()
    val nip05 by viewModel.nip05.collectAsState()
    val banner by viewModel.banner.collectAsState()
    val lud16 by viewModel.lud16.collectAsState()
    val publishing by viewModel.publishing.collectAsState()
    val uploading by viewModel.uploading.collectAsState()
    val error by viewModel.error.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    @Composable
    fun Modifier.scrollOnFocus(): Modifier {
        val bringIntoViewRequester = remember { BringIntoViewRequester() }
        return this
            .bringIntoViewRequester(bringIntoViewRequester)
            .onFocusChanged { if (it.isFocused) scope.launch { delay(100); bringIntoViewRequester.bringIntoView() } }
    }

    val avatarPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) viewModel.uploadImage(context.contentResolver, uri, ProfileViewModel.ImageTarget.PICTURE, signer)
    }

    val bannerPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) viewModel.uploadImage(context.contentResolver, uri, ProfileViewModel.ImageTarget.BANNER, signer)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_edit_profile)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
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
                .imePadding()
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { viewModel.updateName(it) },
                label = { Text(stringResource(R.string.placeholder_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().scrollOnFocus()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = about,
                onValueChange = { viewModel.updateAbout(it) },
                label = { Text(stringResource(R.string.placeholder_about)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth().scrollOnFocus()
            )
            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = picture,
                    onValueChange = { viewModel.updatePicture(it) },
                    label = { Text(stringResource(R.string.placeholder_profile_picture_url)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f).scrollOnFocus()
                )
                IconButton(
                    onClick = {
                        avatarPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    enabled = uploading == null
                ) {
                    if (uploading != null && uploading!!.contains("avatar")) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Outlined.FileUpload, contentDescription = stringResource(R.string.cd_upload_avatar))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = banner,
                    onValueChange = { viewModel.updateBanner(it) },
                    label = { Text(stringResource(R.string.placeholder_banner_url)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f).scrollOnFocus()
                )
                IconButton(
                    onClick = {
                        bannerPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    enabled = uploading == null
                ) {
                    if (uploading != null && uploading!!.contains("banner")) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Outlined.FileUpload, contentDescription = stringResource(R.string.cd_upload_banner))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = nip05,
                onValueChange = { viewModel.updateNip05(it) },
                label = { Text(stringResource(R.string.placeholder_nip05)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().scrollOnFocus()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = lud16,
                onValueChange = { viewModel.updateLud16(it) },
                label = { Text(stringResource(R.string.placeholder_lightning_address)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().scrollOnFocus()
            )
            Spacer(Modifier.height(16.dp))

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
            }

            Button(
                onClick = {
                    if (viewModel.publishProfile(relayPool, signer = signer)) onBack()
                },
                enabled = !publishing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (publishing) stringResource(R.string.onboarding_publishing) else stringResource(R.string.btn_save_profile))
            }
        }
    }
}
