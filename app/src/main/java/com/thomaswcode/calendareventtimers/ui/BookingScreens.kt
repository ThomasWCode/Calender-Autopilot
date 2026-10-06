package com.thomaswcode.calendareventtimers.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.calendareventtimers.booking.Answers
import com.thomaswcode.calendareventtimers.booking.BookingCandidate
import com.thomaswcode.calendareventtimers.booking.BookingController
import com.thomaswcode.calendareventtimers.booking.BookingPlan
import com.thomaswcode.calendareventtimers.booking.BookingPlanner
import com.thomaswcode.calendareventtimers.booking.BookingRange
import com.thomaswcode.calendareventtimers.booking.BookingRanges
import com.thomaswcode.calendareventtimers.booking.BookingResults
import com.thomaswcode.calendareventtimers.booking.Person
import com.thomaswcode.calendareventtimers.booking.ResultRow
import com.thomaswcode.calendareventtimers.booking.RowOutcome
import com.thomaswcode.calendareventtimers.calendar.CalendarStore
import com.thomaswcode.calendareventtimers.data.BookingEntity
import com.thomaswcode.calendareventtimers.data.BookingStore
import com.thomaswcode.calendareventtimers.data.RoomReply
import com.thomaswcode.calendareventtimers.domain.AlarmText
import com.thomaswcode.calendareventtimers.domain.TriggerTime
import com.thomaswcode.calendareventtimers.outlook.OutlookReaderService
import com.thomaswcode.calendareventtimers.util.Prefs
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val shortDay = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

private fun timeSpan(c: BookingCandidate) = "${TriggerTime.formatHhMm(c.event.start)}–${TriggerTime.formatHhMm(c.event.endTime)}"

/** Room booking (PLAN-ROOM-BOOKING.md §3.1): the ranges to book, and how the last runs went. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomBookingScreen(
    state: BookingController.State,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onManage: () -> Unit,
    onSettings: () -> Unit,
    onSetup: () -> Unit,
    onLog: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dryRun by Prefs.dryRun.collectAsStateWithLifecycle()
    val connected by OutlookReaderService.connected.collectAsStateWithLifecycle()
    var calendarAccess by remember { mutableStateOf(CalendarStore.hasAccess(context)) }
    var today by remember { mutableStateOf(LocalDate.now()) }
    LifecycleResumeEffect(Unit) {
        calendarAccess = CalendarStore.hasAccess(context)
        today = LocalDate.now()
        scope.launch { BookingController.refreshReplies(context) }
        onPauseOrDispose { }
    }
    val askCalendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        calendarAccess = granted
        if (!granted) SetupChecks.open(context, SetupAction.APP_INFO)
    }
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val bookings by remember(monday) { BookingStore.get(context).observeFrom(monday) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val busy = state is BookingController.State.Preparing || state is BookingController.State.Booking

    fun start(range: BookingRange) {
        BookingController.prepare(context, range)?.let { scope.launch { snackbar.showNow(it) } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                title = { Text("Room booking") },
                actions = {
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Manage bookings") }, onClick = { menu = false; onManage() })
                        DropdownMenuItem(text = { Text("Settings (rooms)") }, onClick = { menu = false; onSettings() })
                        DropdownMenuItem(
                            text = { Text("Dry run (book nothing)") },
                            trailingIcon = { Checkbox(checked = dryRun, onCheckedChange = null) },
                            onClick = { Prefs.setDryRun(context, !dryRun) },
                        )
                        DropdownMenuItem(text = { Text("Setup checklist") }, onClick = { menu = false; onSetup() })
                        DropdownMenuItem(text = { Text("Activity log") }, onClick = { menu = false; onLog() })
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!calendarAccess) {
                item {
                    InfoCard(
                        "Allow calendar access",
                        "Room booking reads your Outlook events, their invitees and the rooms' replies from the phone's calendar. Read only.",
                        action = "Allow" to { askCalendar.launch(Manifest.permission.READ_CALENDAR) },
                    )
                }
            }
            if (!connected) {
                item { InfoCard("Turn on the Outlook reader", "Booking rooms drives Outlook through it.", action = "Setup checklist" to onSetup) }
            }
            if (dryRun) {
                item {
                    InfoCard(
                        "Dry run is on",
                        "Each booking is filled in in Outlook, room included, then discarded. Nothing is saved or sent.",
                        action = "Turn off" to { Prefs.setDryRun(context, false) }, warning = false,
                    )
                }
            }
            when (state) {
                is BookingController.State.Preparing -> item { ProgressCard("Checking ${BookingRanges.title(state.range).lowercase()}…", state.step) }
                is BookingController.State.Booking -> item { ProgressCard("Booking rooms (${state.done} of ${state.total} done)…", state.step) }
                is BookingController.State.Failed -> item {
                    InfoCard(
                        "Couldn't prepare ${BookingRanges.title(state.range).lowercase()}", state.message,
                        action = "Try again" to { start(state.range) }, dismiss = { BookingController.clear() },
                    )
                }
                else -> Unit
            }
            item {
                val days = BookingRanges.days(BookingRange.NEXT_WEEK, today)
                Button(onClick = { start(BookingRange.NEXT_WEEK) }, enabled = !busy && calendarAccess, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Book rooms for next week", style = MaterialTheme.typography.titleMedium)
                        Text(BookingRanges.describe(days), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Text("Or, for events added since:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (range in listOf(BookingRange.TODAY, BookingRange.TOMORROW, BookingRange.THIS_WEEK)) {
                        val days = BookingRanges.days(range, today)
                        FilledTonalButton(
                            onClick = { start(range) },
                            enabled = !busy && calendarAccess && days.isNotEmpty(),
                            modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(BookingRanges.title(range), style = MaterialTheme.typography.labelLarge)
                                Text(
                                    if (days.isEmpty()) "—" else if (days.size == 1) shortDay.format(days.first()) else "to ${shortDay.format(days.last())}",
                                    style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
            item { WeekStatus(bookings, monday) }
        }
    }
}

@Composable
private fun WeekStatus(bookings: List<BookingEntity>, monday: LocalDate) {
    fun week(start: LocalDate) = bookings.filter { LocalDate.parse(it.eventDate).let { d -> !d.isBefore(start) && d.isBefore(start.plusDays(7)) } }
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Bookings", style = MaterialTheme.typography.titleMedium)
        for ((label, start) in listOf("This week" to monday, "Next week" to monday.plusWeeks(1))) {
            val list = week(start)
            val text = if (list.isEmpty()) "none" else listOfNotNull(
                "${list.size} booked",
                list.count { it.roomReply == RoomReply.WAITING }.takeIf { it > 0 }?.let { "$it waiting for a reply" },
                list.count { it.roomReply == RoomReply.DECLINED }.takeIf { it > 0 }?.let { "$it declined" },
                list.count { it.roomReply == RoomReply.NO_ROOM }.takeIf { it > 0 }?.let { "$it without a room" },
            ).joinToString(" · ")
            Text("$label: $text", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The wizard and the summary over a prepared run (PLAN-ROOM-BOOKING.md §3.8). */
@Composable
fun BookingWizard(ready: BookingController.State.Ready, snackbar: SnackbarHostState, dryRun: Boolean) {
    val plan = ready.plan
    var index by rememberSaveable(plan) { mutableIntStateOf(0) }
    var summary by rememberSaveable(plan) { mutableStateOf(plan.toAsk.isEmpty()) }
    var editing by rememberSaveable(plan) { mutableStateOf<String?>(null) }
    val editingCandidate = editing?.let { key -> plan.all.firstOrNull { it.key == key } }
    when {
        editingCandidate != null -> CandidateStep(
            plan = plan,
            candidate = editingCandidate,
            answers = ready.answers[editingCandidate.key] ?: editingCandidate.defaults,
            position = null,
            onBack = { editing = null },
            onNext = { editing = null },
            nextLabel = "Done",
            onRest = null,
        )
        !summary && plan.toAsk.isNotEmpty() -> {
            val candidate = plan.toAsk[index.coerceIn(0, plan.toAsk.lastIndex)]
            CandidateStep(
                plan = plan,
                candidate = candidate,
                answers = ready.answers[candidate.key] ?: candidate.defaults,
                position = index to plan.toAsk.size,
                onBack = { if (index > 0) index-- else BookingController.clear() },
                onNext = { if (index < plan.toAsk.lastIndex) index++ else summary = true },
                nextLabel = if (index < plan.toAsk.lastIndex) "Next" else "Review",
                onRest = { summary = true },
            )
        }
        else -> BookingSummary(
            ready = ready,
            dryRun = dryRun,
            snackbar = snackbar,
            onBack = { if (plan.toAsk.isEmpty()) BookingController.clear() else { summary = false; index = plan.toAsk.lastIndex } },
            onEdit = { editing = it },
        )
    }
}

/** One event: book a room? notify the others? who? */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CandidateStep(
    plan: BookingPlan,
    candidate: BookingCandidate,
    answers: Answers,
    position: Pair<Int, Int>?,
    onBack: () -> Unit,
    onNext: () -> Unit,
    nextLabel: String,
    onRest: (() -> Unit)?,
) {
    BackHandler(onBack = onBack)
    fun change(a: Answers) = BookingController.setAnswers(candidate.key, a)
    val e = candidate.event
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                title = {
                    Column {
                        Text(BookingRanges.title(plan.range))
                        position?.let { (i, n) -> Text("Event ${i + 1} of $n", style = MaterialTheme.typography.bodyMedium) }
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Back") }
                        Button(onClick = onNext, modifier = Modifier.weight(1f)) { Text(nextLabel) }
                    }
                    if (onRest != null) {
                        TextButton(onClick = onRest, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Use these answers for the rest ›") }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${shortDay.format(e.date)} · ${timeSpan(candidate)}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Row(verticalAlignment = Alignment.Top) {
                        Text(e.title.trim(), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        LabelPill(candidate.label)
                    }
                    AlarmText.shortLocation(e.location)?.let { Text("@ $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(
                        when (candidate.people.size) {
                            0 -> "No LSHTM invitees"
                            1 -> "1 LSHTM invitee"
                            else -> "${candidate.people.size} LSHTM invitees"
                        },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    candidate.partial?.let {
                        Text("${it.room} is booked for ${it.from}–${it.to} (“${it.title}”)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                    if (candidate.roomDeclined) Text("The room declined the last booking for this event.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    if (candidate.roomRemoved) Text("The last booking for this event has no room any more.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    if (candidate.remembered) Text("Answers as last time", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { HorizontalDivider() }
            item { YesNo("Book a room?", answers.bookRoom) { change(answers.copy(bookRoom = it)) } }
            if (answers.bookRoom) {
                if (candidate.people.isEmpty()) {
                    item { Text("Nobody at LSHTM to notify.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    item { YesNo("Notify the others?", answers.notify) { change(answers.copy(notify = it)) } }
                    if (answers.notify) {
                        val (kept, removed) = candidate.people.partition { it.email !in answers.removed }
                        items(kept, key = { "keep-" + it.email }) { p ->
                            PersonRow(p, removed = false) { change(answers.copy(removed = answers.removed + p.email)) }
                        }
                        if (removed.isNotEmpty()) {
                            item { Text("Not notified", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp)) }
                            items(removed, key = { "removed-" + it.email }) { p ->
                                PersonRow(p, removed = true) { change(answers.copy(removed = answers.removed - p.email)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun YesNo(question: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(question, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        SingleChoiceSegmentedButtonRow {
            SegmentedButton(selected = value, onClick = { onChange(true) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Yes") }
            SegmentedButton(selected = !value, onClick = { onChange(false) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("No") }
        }
    }
}

@Composable
private fun PersonRow(person: Person, removed: Boolean, onToggle: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(person.display, style = MaterialTheme.typography.bodyLarge, color = if (removed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                if (person.name != null) Text(person.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (removed) {
                IconButton(onClick = onToggle) { Icon(Icons.Default.Refresh, contentDescription = "Notify ${person.display} after all") }
            } else {
                IconButton(onClick = onToggle) { Icon(Icons.Default.Close, contentDescription = "Don't notify ${person.display}") }
            }
        }
    }
}

@Composable
private fun LabelPill(label: String) {
    val immoveable = label.equals("Immoveable", ignoreCase = true)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (immoveable) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

/** Every event of the run with its answers, the people to be told, and Book Rooms. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookingSummary(
    ready: BookingController.State.Ready,
    dryRun: Boolean,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val plan = ready.plan
    fun answersOf(c: BookingCandidate) = ready.answers[c.key] ?: c.defaults
    val pairs = plan.all.map { it to answersOf(it) }
    val toBook = pairs.count { it.second.bookRoom }
    val notified = pairs.flatMap { (c, a) -> c.notifyList(a) }.distinctBy { it.email }
    val seconds = BookingPlanner.estimateSeconds(pairs) { it.key in plan.withDescription }
    var busy by remember(ready) { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                title = {
                    Column {
                        Text("${BookingRanges.title(plan.range)} · ${plan.all.size} event${if (plan.all.size == 1) "" else "s"}")
                        Text(BookingRanges.describe(plan.days), style = MaterialTheme.typography.bodyMedium)
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { BookingController.clear() }, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(
                        onClick = {
                            if (plan.all.isEmpty()) {
                                BookingController.clear()
                                return@Button
                            }
                            // Also with nothing to book: the answers given are remembered.
                            busy = true
                            BookingController.book(context)?.let {
                                busy = false
                                scope.launch { snackbar.showNow(it) }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (toBook == 0) "Done" else if (dryRun) "Dry run ($toBook)" else "Book Rooms ($toBook)") }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                val notes = listOfNotNull(
                    "${plan.labelsRemembered} labels remembered".takeIf { plan.labelsRemembered > 0 },
                    "${plan.labelsRead} read in Outlook".takeIf { plan.labelsRead > 0 },
                )
                if (notes.isNotEmpty()) Text(notes.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (plan.labelProblems.isNotEmpty()) {
                item { InfoCard("Some events' labels couldn't be read", plan.labelProblems.joinToString("\n") { "• $it" } + "\nThey aren't offered.") }
            }
            if (plan.all.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Text("No Moveable or Immoveable events without a room on ${BookingRanges.describe(plan.days)}.", Modifier.padding(16.dp))
                    }
                }
            }
            items(plan.toAsk, key = { it.key }) { c -> SummaryRow(c, answersOf(c)) { onEdit(c.key) } }
            if (plan.answeredBefore.isNotEmpty()) {
                item { Text("Answered before (no room)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp)) }
                items(plan.answeredBefore, key = { it.key }) { c -> SummaryRow(c, answersOf(c)) { onEdit(c.key) } }
            }
            if (notified.isNotEmpty()) {
                item {
                    Text("Notifies: ${notified.joinToString { it.display }}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                }
            }
            if (toBook > 0) {
                item {
                    Text(
                        "Outlook will be on screen for ${BookingPlanner.describeSeconds(seconds)}. Don't touch the phone meanwhile; STOP at the top ends it.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(c: BookingCandidate, a: Answers, onClick: () -> Unit) {
    val choice = when {
        !a.bookRoom -> "No room"
        c.notifyList(a).isEmpty() -> "Room"
        else -> "Room · tell ${c.notifyList(a).size}"
    }
    OutlinedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(96.dp)) {
                Text(shortDay.format(c.event.date), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(TriggerTime.formatHhMm(c.event.start), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Text(c.event.title.trim(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            Text(choice, style = MaterialTheme.typography.labelLarge, color = if (a.bookRoom) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** What each event got, with the rooms' replies as they arrive (PLAN-ROOM-BOOKING.md §3.11). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookingResultsScreen(results: BookingResults, onDone: () -> Unit) {
    BackHandler(onBack = onDone)
    val context = LocalContext.current
    val firstDay = results.rows.minOfOrNull { it.candidate.event.date } ?: LocalDate.now()
    val saved by remember(firstDay) { BookingStore.get(context).observeFrom(firstDay) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val replies = saved.associate { it.id to it.roomReply }
    // The rooms answer within minutes; look in the phone's calendar every 5 s for 2 minutes.
    LaunchedEffect(results) {
        repeat(24) {
            BookingController.refreshReplies(context)
            delay(5_000)
        }
    }
    val (choseNo, rest) = results.rows.partition { it.outcome is RowOutcome.NoRoomWanted }
    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text(if (results.dryRun) "Dry run finished" else "Rooms for ${BookingRanges.title(results.range).lowercase()}")
                    Text(summaryLine(results), style = MaterialTheme.typography.bodyMedium)
                }
            })
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) { Text("Done") }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            results.stopped?.let { item { InfoCard("The run stopped early", it) } }
            if (results.dryRun) item { Text("Nothing was saved or sent: each booking was filled in, then discarded.", style = MaterialTheme.typography.bodyMedium) }
            items(rest, key = { it.candidate.key }) { row -> ResultCard(row, replies) }
            if (results.missingRooms.isNotEmpty()) {
                item { InfoCard("Not in Room Finder", "${results.missingRooms.joinToString()} — check the names in Settings.") }
            }
            if (choseNo.isNotEmpty()) {
                item {
                    Text(
                        "No room, as answered: ${choseNo.joinToString { it.candidate.event.title.trim() }}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

private fun summaryLine(r: BookingResults): String {
    val booked = r.rows.count { it.outcome is RowOutcome.Booked }
    val parts = mutableListOf(if (r.dryRun) "$booked would be booked" else "$booked booked")
    r.rows.count { it.outcome is RowOutcome.NoRoom }.takeIf { it > 0 }?.let { parts += "$it with no free room" }
    r.rows.count { it.outcome is RowOutcome.Failed }.takeIf { it > 0 }?.let { parts += "$it failed" }
    r.rows.count { it.outcome is RowOutcome.NotDone }.takeIf { it > 0 }?.let { parts += "$it not done" }
    return parts.joinToString(" · ")
}

@Composable
private fun ResultCard(row: ResultRow, replies: Map<Long, RoomReply>) {
    val c = row.candidate
    val (mark, text, colour) = when (val o = row.outcome) {
        is RowOutcome.Booked -> {
            val reply = o.bookingId?.let { replies[it] }
            val status = when {
                !o.saved -> "would be booked"
                reply == RoomReply.RESERVED -> "Reserved"
                reply == RoomReply.TENTATIVE -> "Tentative (needs approval)"
                reply == RoomReply.DECLINED -> "Declined: try another room in Manage bookings"
                reply == RoomReply.NOT_FOUND -> "not found in the calendar"
                reply == RoomReply.NO_ROOM -> "the room is no longer on it: change it in Manage bookings"
                else -> "waiting for the room's reply…"
            }
            val notified = o.told.size.takeIf { it > 0 }?.let { " · told $it" } ?: ""
            Triple("✓", "${o.room} · $status$notified" + o.notes.joinToString("") { "\n($it)" },
                if (reply == RoomReply.DECLINED || reply == RoomReply.NO_ROOM) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
        is RowOutcome.NoRoom -> Triple("✗", "No room in your list was free", MaterialTheme.colorScheme.error)
        is RowOutcome.Failed -> Triple("!", "Not booked: ${o.reason}", MaterialTheme.colorScheme.error)
        is RowOutcome.NotDone -> Triple("–", "Not done: ${o.reason}", MaterialTheme.colorScheme.onSurfaceVariant)
        RowOutcome.NoRoomWanted -> Triple("–", "No room, as answered", MaterialTheme.colorScheme.onSurfaceVariant)
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Text(mark, style = MaterialTheme.typography.titleMedium, color = colour, modifier = Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Text("${shortDay.format(c.event.date)} ${TriggerTime.formatHhMm(c.event.start)}  ${c.event.title.trim()}", style = MaterialTheme.typography.bodyLarge)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = colour)
            }
        }
    }
}

@Composable
fun ProgressCard(title: String, step: String, onStop: () -> Unit = { BookingController.stop() }) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(step, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onStop) { Text("Stop") }
        }
    }
}

/** A card with a title, text and up to one action and a dismiss. */
@Composable
fun InfoCard(title: String, body: String, action: Pair<String, () -> Unit>? = null, dismiss: (() -> Unit)? = null, warning: Boolean = true) {
    val container = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer
    val content = if (warning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container, contentColor = content)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            if (action != null || dismiss != null) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    dismiss?.let { TextButton(onClick = it, colors = ButtonDefaults.textButtonColors(contentColor = content)) { Text("Dismiss") } }
                    action?.let { (label, onClick) -> TextButton(onClick = onClick, colors = ButtonDefaults.textButtonColors(contentColor = content)) { Text(label) } }
                }
            }
        }
    }
}
