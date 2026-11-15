package app.ljud

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private fun fmt(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/** Hex-id-like speaker names are long; shorten them for display. */
private fun short(name: String) = if (name.length > 24) name.take(12) + "…" else name

@Composable
fun LjudApp(vm: MainViewModel, onLogin: () -> Unit, onPhoneSpeaker: (Boolean) -> Unit) {
    if (!vm.loggedIn) {
        LoginScreen(vm, onLogin)
    } else {
        MainScreen(vm, onPhoneSpeaker)
    }
}

@Composable
private fun LoginScreen(vm: MainViewModel, onLogin: () -> Unit) {
    Scaffold { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(LjudIcons.Note, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("ljud", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "Control Spotify on your speakers. Requires Spotify Premium.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
            if (vm.loggingIn) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Finish logging in in the browser…")
                TextButton(onClick = vm::cancelLogin) { Text("Cancel") }
            } else {
                Button(onClick = onLogin) { Text("Log in with Spotify") }
            }
            vm.message?.let {
                Spacer(Modifier.height(24.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(vm: MainViewModel, onPhoneSpeaker: (Boolean) -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.message = null
        }
    }
    BackHandler(enabled = vm.detail != null) { vm.back() }

    val detail = vm.detail
    val title = when {
        detail is Detail.Liked -> "Liked songs"
        detail is Detail.OfPlaylist -> detail.playlist.name
        detail is Detail.OfAlbum -> detail.album.run { if (year != null) "$name ($year)" else name }
        detail is Detail.OfArtist -> detail.artist.name
        detail is Detail.Queue -> "Queue"
        vm.tab == Tab.Search -> "Search"
        else -> vm.user?.name?.let { "ljud · $it" } ?: "ljud"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (detail != null) {
                        IconButton(onClick = vm::back) { Icon(LjudIcons.Back, "Back") }
                    }
                },
                actions = {
                    IconButton(onClick = { vm.open(Detail.Queue) }) { Icon(LjudIcons.Queue, "Queue") }
                    IconButton(onClick = vm::openDevices) { Icon(LjudIcons.Speaker, "Speakers") }
                },
            )
        },
        bottomBar = {
            Column {
                NowPlaying(vm)
                NavigationBar {
                    NavigationBarItem(
                        selected = vm.tab == Tab.Library && detail == null,
                        onClick = { vm.closeDetails(); vm.tab = Tab.Library },
                        icon = { Icon(LjudIcons.Library, null) },
                        label = { Text("Library") },
                    )
                    NavigationBarItem(
                        selected = vm.tab == Tab.Search && detail == null,
                        onClick = { vm.closeDetails(); vm.tab = Tab.Search },
                        icon = { Icon(LjudIcons.Search, null) },
                        label = { Text("Search") },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                detail is Detail.OfArtist -> ArtistScreen(vm, detail)
                detail is Detail.Queue -> QueueScreen(vm)
                detail != null -> TrackList(vm, vm.tracks, vm.tracksLoading, detail)
                vm.tab == Tab.Library -> LibraryScreen(vm)
                else -> SearchScreen(vm)
            }
        }
    }

    if (vm.showDevices) DevicesSheet(vm, onPhoneSpeaker)
}

@Composable
private fun LibraryScreen(vm: MainViewModel) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            ListItem(
                headlineContent = { Text("Liked songs", fontWeight = FontWeight.SemiBold) },
                leadingContent = { Icon(LjudIcons.Heart, null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable { vm.open(Detail.Liked) },
            )
        }
        if (vm.playlistsLoading && vm.playlists.isEmpty()) {
            item { Loading() }
        }
        items(vm.playlists, key = { it.id }) { p ->
            val owner = if (p.ownerId == vm.user?.id) "" else " · ${p.ownerName}"
            ListItem(
                headlineContent = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text("${p.total} tracks$owner") },
                leadingContent = { Cover(p.images.pick(64), 48.dp, placeholder = LjudIcons.Library) },
                modifier = Modifier.clickable { vm.open(Detail.OfPlaylist(p)) },
            )
        }
    }
}

@Composable
private fun SearchScreen(vm: MainViewModel) {
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = vm.query,
            onValueChange = { vm.query = it },
            placeholder = { Text("Songs, albums, playlists") },
            leadingIcon = { Icon(LjudIcons.Search, null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.search() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        val r = vm.results
        when {
            vm.searching -> Loading()
            r == null -> {}
            r.tracks.isEmpty() && r.artists.isEmpty() && r.albums.isEmpty() && r.playlists.isEmpty() ->
                Text("No results.", Modifier.padding(16.dp))
            else -> LazyColumn(Modifier.fillMaxSize()) {
                if (r.tracks.isNotEmpty()) item { Section("Songs") }
                itemsIndexed(r.tracks) { i, t -> TrackRow(vm, t) { vm.playFrom(r.tracks, i, null) } }
                if (r.artists.isNotEmpty()) item { Section("Artists") }
                items(r.artists) { a -> ArtistRow(vm, a) }
                if (r.albums.isNotEmpty()) item { Section("Albums") }
                items(r.albums) { a -> AlbumRow(vm, a) }
                if (r.playlists.isNotEmpty()) item { Section("Playlists") }
                items(r.playlists) { p ->
                    ListItem(
                        headlineContent = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text("by ${p.ownerName} · ${p.total} tracks", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Cover(p.images.pick(64), 48.dp, placeholder = LjudIcons.Library) },
                        modifier = Modifier.clickable { vm.open(Detail.OfPlaylist(p)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun TrackList(vm: MainViewModel, tracks: List<Track>, loading: Boolean, context: Detail) {
    when {
        loading -> Loading()
        tracks.isEmpty() -> Text("No tracks.", Modifier.padding(16.dp))
        else -> LazyColumn(Modifier.fillMaxSize()) {
            when (context) {
                is Detail.OfAlbum -> item {
                    val a = context.album
                    DetailHeader(a.images.pick(300), listOfNotNull(a.artists, a.year).joinToString(" · "))
                }
                is Detail.OfPlaylist -> item {
                    val p = context.playlist
                    DetailHeader(p.images.pick(300), "${p.ownerName} · ${p.total} tracks", LjudIcons.Library)
                }
                else -> {}
            }
            // On an album page every song has the same cover; skip it there.
            val covers = context !is Detail.OfAlbum
            itemsIndexed(tracks) { i, t -> TrackRow(vm, t, covers) { vm.playFrom(tracks, i, context) } }
        }
    }
}

/** Large cover and a subtitle at the top of an album/playlist/artist page. */
@Composable
private fun DetailHeader(
    imageUrl: String?,
    subtitle: String,
    placeholder: ImageVector = LjudIcons.Album,
    shape: androidx.compose.ui.graphics.Shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
) {
    Column(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Cover(imageUrl, 200.dp, shape = shape, placeholder = placeholder)
        Spacer(Modifier.height(12.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

/**
 * A row that runs [onClick] on tap and shows a menu ([entries]) on long press.
 * With [onClick] null, a tap opens the menu too.
 * The menu entries and their order come from MainViewModel.menuFor.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WithMenu(
    title: String,
    entries: () -> List<MenuEntry>,
    onClick: (() -> Unit)?,
    content: @Composable (Modifier) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    Box {
        content(
            Modifier.combinedClickable(
                onClick = onClick ?: { open = true },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    open = true
                },
            ),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.widthIn(max = 320.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider()
            entries().forEach { e ->
                DropdownMenuItem(
                    text = { Text(e.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        open = false
                        e.run()
                    },
                )
            }
        }
    }
}

@Composable
private fun TrackRow(vm: MainViewModel, t: Track, showCover: Boolean = true, onClick: (() -> Unit)?) {
    val playing = vm.playback?.track?.uri == t.uri
    val color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    WithMenu(t.name, { vm.menuFor(t) }, onClick) { mod ->
        ListItem(
            headlineContent = {
                Text(
                    t.name,
                    color = color,
                    fontWeight = if (playing) FontWeight.Bold else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = {
                Text(
                    listOf(t.artists, t.album).filter { it.isNotEmpty() }.joinToString(" · "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingContent = if (showCover) {
                { Cover(t.albumRef?.images?.pick(64), 40.dp) }
            } else {
                null
            },
            trailingContent = { Text(fmt(t.durationMs), style = MaterialTheme.typography.labelMedium) },
            modifier = mod,
        )
    }
}

@Composable
private fun AlbumRow(vm: MainViewModel, a: Album) {
    WithMenu(a.name, { vm.menuFor(a) }, { vm.open(Detail.OfAlbum(a)) }) { mod ->
        ListItem(
            headlineContent = { Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(
                    listOfNotNull(a.artists, a.year, "${a.total} tracks").joinToString(" · "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingContent = { Cover(a.images.pick(64), 48.dp, placeholder = LjudIcons.Album) },
            modifier = mod,
        )
    }
}

@Composable
private fun ArtistRow(vm: MainViewModel, a: Artist) {
    WithMenu(a.name, { vm.menuFor(a) }, { vm.open(Detail.OfArtist(a)) }) { mod ->
        ListItem(
            headlineContent = { Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text("Artist") },
            leadingContent = { Cover(a.images.pick(64), 48.dp, shape = CircleShape, placeholder = LjudIcons.Person) },
            modifier = mod,
        )
    }
}

/** The queue. Spotify's API can't jump to a queued song, so a tap opens the menu. */
@Composable
private fun QueueScreen(vm: MainViewModel) {
    val current = vm.queueCurrent
    val next = vm.tracks
    when {
        vm.tracksLoading -> Loading()
        current == null && next.isEmpty() -> Text("Nothing is playing.", Modifier.padding(16.dp))
        else -> LazyColumn(Modifier.fillMaxSize()) {
            if (current != null) {
                item { Section("Now playing") }
                item { TrackRow(vm, current, onClick = null) }
            }
            if (next.isNotEmpty()) item { Section("Next up") }
            items(next) { t -> TrackRow(vm, t, onClick = null) }
        }
    }
}

@Composable
private fun ArtistScreen(vm: MainViewModel, d: Detail.OfArtist) {
    val tracks = vm.tracks
    val albums = vm.artistAlbums
    when {
        vm.tracksLoading -> Loading()
        tracks.isEmpty() && albums.isEmpty() -> Text("Nothing found.", Modifier.padding(16.dp))
        else -> LazyColumn(Modifier.fillMaxSize()) {
            if (d.artist.images.isNotEmpty()) {
                item { DetailHeader(d.artist.images.pick(300), "Artist", LjudIcons.Person, CircleShape) }
            }
            if (tracks.isNotEmpty()) item { Section("Popular") }
            itemsIndexed(tracks) { i, t -> TrackRow(vm, t) { vm.playFrom(tracks, i, d) } }
            if (albums.isNotEmpty()) item { Section("Albums & singles") }
            items(albums) { a -> AlbumRow(vm, a) }
        }
    }
}

@Composable
private fun NowPlaying(vm: MainViewModel) {
    val p = vm.playback ?: return
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(p.isPlaying) {
        while (p.isPlaying) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    val dur = p.track?.durationMs ?: 0
    val pos = vm.progressMs(now)

    Surface(tonalElevation = 3.dp) {
        Column {
            LinearProgressIndicator(
                progress = { if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                drawStopIndicator = {},
            )
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(p.track?.albumRef?.images?.pick(64), 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).clickable { vm.openDevices() }) {
                    Text(
                        p.track?.name ?: "—",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        p.track?.artists.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    p.device?.let { d ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(LjudIcons.Speaker, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                short(vm.deviceName(d)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                IconButton(onClick = vm::previous) { Icon(LjudIcons.Previous, "Previous") }
                FilledIconButton(onClick = vm::togglePlay) {
                    Icon(if (p.isPlaying) LjudIcons.Pause else LjudIcons.Play, if (p.isPlaying) "Pause" else "Play")
                }
                IconButton(onClick = vm::next) { Icon(LjudIcons.Next, "Next") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevicesSheet(vm: MainViewModel, onPhoneSpeaker: (Boolean) -> Unit) {
    var renaming by remember { mutableStateOf<Device?>(null) }
    ModalBottomSheet(
        onDismissRequest = vm::closeDevices,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Speakers", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::openDevices) { Text("Refresh") }
            }
            val phoneText = when {
                vm.phoneLoggingIn -> "Log in in the browser…"
                !vm.phoneSpeaker -> "Play through this phone, without the Spotify app"
                vm.phoneStatus == "ready" -> "Ready: pick “This phone” below"
                vm.phoneStatus.startsWith("offline") -> "Offline, reconnecting…"
                vm.phoneStatus.startsWith("error") -> vm.phoneStatus.removePrefix("error: ").replaceFirstChar { it.uppercase() }
                else -> "Connecting…"
            }
            ListItem(
                headlineContent = { Text("Use this phone as a speaker") },
                supportingContent = { Text(phoneText) },
                trailingContent = {
                    Switch(
                        checked = vm.phoneSpeaker || vm.phoneLoggingIn,
                        onCheckedChange = onPhoneSpeaker,
                        enabled = !vm.phoneLoggingIn,
                    )
                },
            )
            HorizontalDivider(Modifier.padding(bottom = 4.dp))
            if (vm.devicesLoading && vm.devices.isEmpty()) Loading()
            if (!vm.devicesLoading && vm.devices.isEmpty()) {
                Text(
                    "No speakers visible. Turn one on, or open Spotify on it once, then refresh.",
                    Modifier.padding(24.dp),
                )
            }
            LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                items(vm.devices, key = { it.id ?: it.name }) { d ->
                    val name = vm.deviceName(d)
                    val meta = buildList {
                        if (vm.hasAlias(d)) add(short(d.name))
                        if (d.type.isNotEmpty()) add(d.type.lowercase())
                        d.volume?.let { add("vol $it%") }
                        if (d.id == null) add("can't be controlled")
                    }.joinToString(" · ")
                    ListItem(
                        headlineContent = {
                            Text(
                                short(name),
                                fontWeight = if (d.isActive) FontWeight.Bold else null,
                                color = if (d.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        supportingContent = { Text(meta) },
                        leadingContent = {
                            Icon(
                                LjudIcons.Speaker,
                                null,
                                tint = if (d.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = {
                            IconButton(onClick = { renaming = d }) { Icon(LjudIcons.Edit, "Rename") }
                        },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.clickable { vm.chooseDevice(d) },
                    )
                }
            }
            val active = vm.playback?.device
            if (active?.volume != null) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                var vol by remember(active.id) { mutableFloatStateOf(active.volume.toFloat()) }
                Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(LjudIcons.Volume, "Volume")
                    Spacer(Modifier.width(12.dp))
                    Slider(
                        value = vol,
                        onValueChange = { vol = it },
                        onValueChangeFinished = { vm.setVolume(vol.toInt()) },
                        valueRange = 0f..100f,
                        modifier = Modifier.weight(1f),
                    )
                    Text("${vol.toInt()}%", Modifier.width(48.dp), style = MaterialTheme.typography.labelLarge)
                }
                Text(
                    short(vm.deviceName(active)),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
    }

    renaming?.let { d ->
        var text by remember(d) { mutableStateOf(if (vm.hasAlias(d)) vm.deviceName(d) else "") }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Name this speaker") },
            text = {
                Column {
                    Text("Reported name: ${short(d.name)}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        placeholder = { Text("e.g. Kitchen") },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.renameDevice(d, text); renaming = null }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    if (vm.hasAlias(d)) {
                        TextButton(onClick = { vm.renameDevice(d, ""); renaming = null }) { Text("Reset") }
                    }
                    TextButton(onClick = { renaming = null }) { Text("Cancel") }
                }
            },
        )
    }
}
