package com.wisp.app.ui.component

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// Inactive items stay null rather than observing another track's progress ticks.
internal fun Flow<AudioPlaybackState?>.forAudioItem(url: String?): Flow<AudioPlaybackState?> =
    map { state -> state?.takeIf { it.track.url == url } }.distinctUntilChanged()
