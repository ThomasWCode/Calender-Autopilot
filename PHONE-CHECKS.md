# Phone checks (deferred)

Room booking and the shared engine ([PLAN-ROOM-BOOKING.md](PLAN-ROOM-BOOKING.md)) were built on
2026-10-05 **without the phone**, then reviewed on 2026-10-06 (seven rounds of Codex's review on
PR #1 and another agent's review). Everything compiles and the unit tests pass against the screens
captured on 2026-10-05, but **nothing has run on the Pixel yet**. This is what to check there, in
order, with what to record. Questions for the user are in [QUESTIONS.md](QUESTIONS.md).

Rules for testing on the phone (the user's, 2026-10-05): look at calendar events, **never emails**;
any event created must have **no invitees** and be **deleted afterwards**. A room counts as an
invitee (choosing one sends the room a meeting request), so **real bookings need the user's
go-ahead** (part D, QUESTIONS.md Q13). Parts A–C send nothing: drafts are always discarded.

Order: A (read only) → B (one step at a time on Outlook's form, nothing saved) → C (dry runs) → D
(real bookings). An early failure usually explains later ones, so fix as you go. Updated on
2026-10-06 for the review rounds: A9–A12, B1, B8, B11, C4, C9–C11 and D2–D15 are new or changed.

## 0. Install

```bash
./gradlew assembleDebug
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then on the phone: Setup checklist → **Calendar access** → Allow. Keep the Outlook reader on. Watch
the log with:

```bash
adb logcat -s CET
```

Git Bash rewrites `/sdcard/...` paths: set `MSYS_NO_PATHCONV=1` for `adb shell` commands that use them.

## A. Engine and timers (read only)

| # | Check | Expect / record |
|---|---|---|
| A1 | App opens on the launcher: **Timers** and **Room booking** | Both cards; setup card only if something is missing |
| A2 | Timers → *Set alarms for tomorrow* (first time) | Log: `N event(s) to check, 0 label(s) remembered, M to read in Outlook`; Outlook opens only those events. **Compare** the review with what the old whole-day scan showed (same labelled events?) |
| A3 | Same again | Review says *all labels remembered, Outlook not opened*; no Outlook at all |
| A4 | Give a **test event** (no invitees, made for the test, deleted after) the Moveable label in Outlook; scan its day | Only that event is opened (`1 to read in Outlook`). Confirms the change key (`sync_data3`) on a second event |
| A5 | Log lines `…: the Day view and the phone's calendar agree` | Note any `not in the Day view` / `only in the Day view` lines: events in other calendars, or sync gaps (QUESTIONS.md Q3) |
| A6 | Today's scan after some events have started | Started ones skipped, count shown |
| A7 | Calendar access refused (App info → Permissions → deny), scan | Falls back to the old whole-day scan; works as before |
| A8 | Time for A2 / A3 | Seconds with Outlook on screen |
| A9 | Two **test events** (no invitees, deleted after) with the same title and start on one day: one Moveable, the other no label; scan | Both blocks opened; problem `…different labels, and Outlook doesn't show which is which`; nothing remembered for them. Then both Moveable: both read, one label. If a block fails to open: problem `…couldn't be read in Outlook…, so whose labels these are isn't certain`. Note how Outlook orders the two blocks |
| A10 | Auto-rotate **on**; start a scan, then turn the phone sideways while Outlook is being driven | The screen stays portrait until the strip goes, then turns as usual. Start a scan with the phone sideways: log `Turning the screen upright for the run`, the screen turns first, the strip fits the status bar. If the screen still turns mid-run, the fallback is QUESTIONS.md Q17 |
| A11 | With calendar access off (A7), set an alarm for a labelled event; turn access back on and scan that day again | The event shows *✓ Alarm already set*: no second alarm (titles are cleaned the same way on both paths) |
| A12 | `adb shell content query --uri content://com.android.calendar/calendars --projection _id:name:account_name:ownerAccount:calendar_access_level` | One Outlook calendar called `Calendar` owned by an LSHTM address (`eiderwhi@lshtm.ac.uk`): the one the app reads. If Outlook has another account, none of its events appear in the review or the wizard (QUESTIONS.md Q3) |

## B. Probes on Outlook's form (nothing saved)

Each probe tries **one** step on whatever Outlook screen is showing and logs what happened
(`DebugProbes.kt`). Open the screens by hand: Outlook → Calendar → Day view → **New event**. Only
type dummy addresses (`nobody@example.com`). When done, **Cancel → Discard** the draft.

```bash
adb shell am broadcast -a com.thomaswcode.calendareventtimers.DEBUG_PROBE --es probe screen
```

| # | Screen | Probe | Expect / record |
|---|---|---|---|
| B1 | New event form | `screen` | Rows listed (People, Date `Mon 12 Oct`, Time `08:05 ▸ 09:00`, Location, Description, Alert); `account richard.white@lshtm.ac.uk`. **If the account can't be read, every booking fails** (by design: never book from an unknown account) |
| B2 | Same | `title --es text "Probe title"` | `set=true`, form reads `Probe title`. If false: `BookingNavigator.setTitle` needs another way (click + IME) |
| B3 | Time row → picker | `screen` | `Time picker, wheels=false` (drag mode as it opens) |
| B4 | Tap the picker's mode button → Choose Time | `screen` | `wheels=true`, wheel values `Mon 12 Oct`, `8`, `05`; previous/next rows |
| B5 | Same | `wheel_step --ei wheel 1 --es dir forward` (scroll) and again with `--es how click` | Which moves the hour by one; `8 → 9`. Record time per step |
| B6 | Hour at 23 | `wheel_step --ei wheel 1` | Does it wrap to 0? (`stepWheel` takes the long way if not) |
| B7 | Same | `wheel_text --ei wheel 2 --es text 35` | Does `ACTION_SET_TEXT` set a minute **and keep it after Done**? If yes, the wheels could be set in one action each (faster) |
| B8 | Add People | `people --es text "nobody@example.com, nobody2@example.com,"` | Two chips from one action? Their addresses (`chips 2: [...]`). **Each chip must show its address** (description `<…>` or text): a booking that tells people fails otherwise, by design (QUESTIONS.md Q18). Close with **✕**, not Done |
| B9 | Description editor | `paste` | `ok=true`; does the editor keep bold and the link? Then `set_description` (plain fallback). Record what the **form's** Description row shows after Done |
| B10 | Alert sheet | `screen` | `Alert sheet open` |
| B11 | Location → Room Finder → KS-Rooms | `screen` | Rooms with `FREE/BUSY`. Only a plain `Free` counts as free: note any **other status text** (loading, "Free until …"), which counts as busy |
| B12 | Location (Add Location) | `screen` | Recent rows and statuses; check whether busy rooms ever appear in Recent |
| B13 | Any | `keyboard --es mode hidden`, tap a text field, then `--es mode auto` | Keyboard stays hidden, then comes back |

Also take `DEBUG_DUMP`s of the time picker in both modes: since this build the service no longer
blanks booking screens, so they replace the reconstructed fixtures `time_picker_*.xml`:

```bash
adb shell am broadcast -a com.thomaswcode.calendareventtimers.DEBUG_DUMP
```

## C. Dry-run bookings (nothing saved or sent)

Room booking ⋮ → **Dry run** on. Each booking is filled in completely, room included, then discarded.

| # | Check | Expect / record |
|---|---|---|
| C1 | *Today* or *Tomorrow* with one labelled event; answer Book = Yes, Notify = **No** | Outlook: New event → time set on the wheels → Room Finder (or Recent) → title → description → Alert None → **Cancel → Discard**. Results: `would be booked: KS-…` |
| C2 | Afterwards | No `Room Booking -` event in the calendar (`adb shell content query --uri content://com.android.calendar/events --projection _id:title --where "title LIKE 'Room Booking%'"`) |
| C3 | A week with several events | Each booking's time on screen (log timestamps); day changes between bookings |
| C4 | STOP during a booking (also once while the description is being pasted) | Form discarded, Outlook back on the calendar, results say *not done*. The clipboard is empty afterwards: the meeting's description isn't left on it |
| C5 | An event at a time when every listed room is busy | *No room in your list was free*; nothing left in Outlook |
| C6 | The app comes back to the results after the run; Back → Room booking | — |
| C7 | Recent shortcut off (Settings) vs on | Same room chosen; time saved |
| C8 | Answer the wizard for a **test event**, then move it in Outlook before *Book Rooms* | Results: *Not done: it was moved, changed or deleted after you answered*; Outlook isn't opened for it |
| C9 | In the wizard, tap *Keep the rest as suggested* on the second event | The summary opens; the remaining events keep the answers suggested for them (last time's, else Yes / No) |
| C10 | Room booking ⋮ → Manage bookings, with Dry run on | The note at the bottom says Dry run doesn't apply there: changes are real |
| C11 | Only if Outlook has a second account: make it the default for new events, then a dry run | The booking fails: *The new event would be made in …'s calendar, not your LSHTM one*; nothing is typed into the form |

Only after C1–C10 pass: a dry run with **Notify = Yes** (the people's addresses are typed into the
draft, which is discarded; nothing is sent). Confirm it stays a draft (C2).

## D. Real bookings (**need the user's go-ahead**)

Use test meetings with no other invitees where possible (D5, D6 and D10 need a consenting colleague).
Delete everything made here afterwards.

| # | Check | Expect / record |
|---|---|---|
| D1 | One booking, room only (Notify = No), on a quiet slot | Save: is there a prompt? (`save()` fails on any question, logging it.) Record the button's description (`Save` or `Send`). Results say *Reserved* or *waiting…*, never *Maybe booked* |
| D2 | The room's reply | Seconds until the booking event shows in the phone's calendar and the provider shows the room's `attendeeStatus` 1 (results say *Reserved*); Outlook's details say *Reserved*. Both must be well under 15 minutes (the sync grace). If a room ever declines: does Outlook keep the room in the event's location? (A declined room there is not counted as a room) |
| D3 | Manage bookings → Change room | Edit Event form opens; *Clear location*; next free room; any "send update" prompt? Add a Zoom link to the booking's location in Outlook first, then Change room: refused, *The booking has another location too …*, nothing changed |
| D4 | Manage bookings → Delete booking | Prompt text and buttons with a room on the event (`DELETE_CONFIRMATIONS` lists the labels accepted); the room's cancellation. The row goes at once and doesn't come back as *Found in your calendar* while Outlook syncs; the meeting is offered again next run |
| D5 | Edit people (needs a consenting colleague) | Adding works by typing; **removing** a person: what tapping a chip offers (`removePerson` looks for Remove/Delete). After *Update*, Manage bookings keeps showing the new list while Outlook syncs. With a meeting of many LSHTM invitees, the dialog scrolls and its buttons stay reachable |
| D6 | Notify one consenting colleague | They receive `Room Booking - …` with the room; their reply doesn't disturb the app |
| D7 | Take the room off a booking in Outlook (D1's, before deleting it) | 15 minutes after the change: Manage bookings says *No room on it* with a warning, and the meeting is offered again |
| D8 | D1's booking: Manage bookings → Change room, and press STOP just after Save | The message says what changed; Manage bookings shows the new room (recorded before the run tidied up) |
| D9 | Two bookings with the same title, time, end and room (D1 made twice), then Delete one in Manage bookings | Nothing is deleted: *2 events at … are '…', and Outlook doesn't show which is the booking* |
| D10 | Add the consenting colleague to D1's booking **in Outlook** | Manage bookings lists them as told (read from the booking event); Edit people can take them off |
| D11 | Shorten D1's booking in Outlook (end 15 minutes earlier); separately, change its room in Outlook | After the next refresh its end follows Outlook, and the meeting is offered again with the note *KS-… is booked for … (“Room Booking - …”)*. A room changed in Outlook is followed (room and reply) 15 minutes after the change |
| D12 | Make the booked test meeting longer after D1 | Offered again, with the note that the room is booked for part of the time |
| D13 | With D1's booking still in Outlook, clear the app's data (App info → Storage → **Clear data**; this also clears alarms and settings) and set the app up again | Manage bookings lists it as *Found in your calendar*; the meeting isn't offered. Change its room: the row says *Changed just now: waiting for Outlook to update the calendar* and its ⋮ is off for about 2 minutes; then the new room shows |
| D14 | Answer **No room** for a test meeting (recorded; nothing booked). Next run, answer **Yes**, then move the meeting in Outlook and wait until the phone's calendar shows the move before *Book Rooms*, so nothing is booked (as C8) | The run after that suggests **Yes** for it: the old *No room* answer is gone |
| D15 | Results, the Room booking screen's week lines and the launcher, with a mix of replies (D2, D7, D4) | *Booked* counts only bookings holding a room; *declined*, *without a room* and *missing* are named; colours match |

## E. Data to collect for the tests

Save as fixtures (`app/src/test/resources/fixtures/booking/`) with uiautomator dumps or the service's
`DEBUG_DUMP` (now unredacted on booking screens):

- the form with people added (what the People row shows), with a description (the Description row),
  with Alert None; its account row (B1), and with a second account if there is one (C11);
- Choose Time wheels and the drag picker, from the service (to replace the reconstructed fixtures);
- Room Finder showing a status other than plain Free/Busy, if one exists;
- any prompt after Save with a room (D1), the delete prompt with a room (D4), the chip menu (D5);
- a booking whose room declined: its details screen and its provider rows (does the room stay in
  the location? attendee status 2);
- timings: the booking event reaching the phone's calendar (D2), and a Manage change reaching it
  (D3–D5), against the 15-minute sync grace and the 2-minute hold.

## F. Where the guesses are in the code

| Guess | Where | If wrong |
|---|---|---|
| ACTION_SET_TEXT works on the Compose title | `BookingNavigator.setTitle` | click the field, then set text again (already tried once) |
| NumberPicker steps with ACTION_SCROLL_FORWARD/BACKWARD; the virtual rows click | `stepWheel` | B5 decides which first |
| "a,b," in one ACTION_SET_TEXT makes chips | `typePeople` (falls back to one address at a time) | B8 |
| Chips show their person's address | `typePeople`, `removePerson` (strict) | B8, D5: bookings that tell people fail (Q18) |
| HTML paste into the WebView editor | `setDescription` (plain text fallback) | B9 |
| The form's Description row shows the text once set | `EventFormReader.descriptionRow` (found by position too) | a note "may not have been copied" in results |
| The form's account row reads "Name, address" | `EventFormReader.accountAddress` | B1: **every booking fails** until the reader is fixed |
| No prompt after Save | `save()` | D1: add the prompt to `PromptReader` and answer it |
| Delete prompt labels | `deleteBooking`, `DELETE_CONFIRMATIONS` | D4 |
| Removing a person from a chip | `removePerson` | D5 |
| Room statuses are exactly Free/Busy | `RoomChoice.status` | B11 |
| Outlook goes back to the Day view on the same day after Save | `ensureOnDay` before each booking | — (it's checked) |
| After saving an **edited** event Outlook shows its details (the app closes them) or the calendar | `BookingNavigator.save` | D3: anywhere else it's reported as "maybe saved" |
| Closed sub-screens (Add Location, Room Finder, Add People, picker) leave the tree, or at least stop being visible | the `visibleId` checks in `BookingReaders.kt`, `onlyForm` | every step would time out with "…didn't close" |
| The booking event shows in the provider under its title at the original's start, organised by Outlook's account (`eiderwhi@…`, one of the user's addresses) | `BookingStore.matches`, `pair`, `ownBookingEvents`; `RoomCover.cover` | D1–D2: bookings never found, so missing after 15 minutes |
| Booking events reach the provider within 15 minutes, Manage changes within about 2 | `BookingStore.synced` (sync grace), the Manage hold | D2–D5 timings |
| A room that declines stays in the event's location | `RoomCover.cover` (the location doesn't count for a declined room) | D2 |
| The details screen's time reads "HH:MM to HH:MM, duration: …" (seen in the captured screens) | `DetailsReader.read` → `end` | without it, Manage bookings tells events apart by title, start and room only. A missing end is let through for now; once D3–D5 show the end is always there, make it required (deferred from Codex's last review: requiring it before then could stop every Manage action) |
| Opening a booking for Change room / Edit people / Delete finds exactly one event: title, start, end, room | `BookingNavigator.openForEdit` | D3–D5, D9 |
| A visible overlay asking for portrait holds the screen upright | `ScanOverlay` (`screenOrientation`) | A10: Q17's fallback |
| Outlook's LSHTM account has the only owned `Calendar` the app should read | `CalendarRows.mainCalendar` | A12 |
