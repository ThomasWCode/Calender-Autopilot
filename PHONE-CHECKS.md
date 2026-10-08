# Phone checks

Room booking and the shared engine ([PLAN-ROOM-BOOKING.md](PLAN-ROOM-BOOKING.md)) were built on
2026-10-05 **without the phone**, then reviewed on 2026-10-06 (seven rounds of Codex's review on
PR #1 and another agent's review). **They were run on the Pixel 8a (Android 17, Outlook 5.2638.1)
on 2026-10-07/08**: the results and what was fixed are in [Results](#results-2026-10-0708) below;
the tables after it say what each check is. Questions for the user are in [QUESTIONS.md](QUESTIONS.md).

Rules for testing on the phone (the user's, 2026-10-05): look at calendar events, **never emails**;
any event created must have **no invitees** and be **deleted afterwards**. A room counts as an
invitee (choosing one sends the room a meeting request), so **real bookings need the user's
go-ahead** (part D, QUESTIONS.md Q13). Parts A–C send nothing: drafts are always discarded.

Order: A (read only) → B (one step at a time on Outlook's form, nothing saved) → C (dry runs) → D
(real bookings). An early failure usually explains later ones, so fix as you go. Updated on
2026-10-06 for the review rounds: A9–A12, B1, B8, B11, C4, C9–C11 and D2–D15 are new or changed.

## Results (2026-10-07/08)

Run with the user's go-ahead (QUESTIONS.md Q13): calendar events looked at, never emails; test
events `CA test A/B/C` with no invitees, in quiet slots on Thu 8 Oct (06:00–13:40), labelled in
Outlook and deleted afterwards, as were their bookings. Debug probes made and deleted the test
events (`create_event`, `edit_event`, `delete_event`; `rooms` set the room list for C5 and C7).

**Left for the user:** A10 (rotation: needs someone to turn the phone), D5, D6 and D10 (need a
consenting colleague: no invitees may be added yet), the C dry run with *Notify = Yes* (it would type
real colleagues' addresses into a draft; only dummy addresses were allowed), A8 timings beyond those
below. **To decide:** KS-103D declined both bookings made at 06:00 (00:14 and 00:53 on 8 Oct) though
Room Finder showed it Free; it is first in the room list, so every run tries it first.

| Check | Result |
|---|---|
| A1 | ✓ Launcher with both cards |
| A2 | ✓ 26 of 28 labels read; review as the old scan. The cross-check found two gaps, both fixed: **series in `US/Pacific-New`** (removed from tzdata; Android repeats them in GMT, so after a clock change they were an hour out: provider 15:05, Outlook 16:05) are moved back to their clock time (`TimeZones`); **occurrences the provider has but Outlook doesn't** (a series moved to another day; six copies of one Friday series) are remembered as missing, per occurrence, for 7 days, and their series read at another occurrence |
| A3 | ✓ All labels remembered, Outlook not opened |
| A4 | ✓ A test event labelled Moveable in Outlook (event details → *Categorise*): `1 to read in Outlook`, only it opened. The change key (`sync_data3`) changed ~10 s after a move and after a label change |
| A5 | Thu: `15:05 Weekly AI discussion` (stale series) not in the Day view; Fri: the six `Re: Rui ZHANG … [room added]` copies. Both now left out (A2) |
| A6 | ✓ Started events skipped |
| A7 | ✓ Without calendar access the whole-day scan reads 27 events, 12 labelled |
| A9 | Not run (two same-title test events would only repeat what D9 showed for bookings) |
| A11 | Not run (no alarm was set with calendar access off) |
| A12 | ✓ One Outlook account; `Calendar` (id 34) owned by `eiderwhi@lshtm.ac.uk` |
| B1 | ✓ Rows: People, All Day, Date `Thu 8 Oct, Tomorrow`, `Time (GMT+1)`, **Time zone** (new), Location, **Online Meeting (Zoom) switch** (new, off), Description, Attachments, Repeat, Alert `15 minutes before`, Show as, Private; account `richard.white@lshtm.ac.uk` |
| B2 | ✓ `ACTION_SET_TEXT` sets the title |
| B3–B6 | ✓ Drag picker, mode button → wheels; scrolling steps a wheel; hours wrap. Fixed: wheel values read stale from the accessibility cache (the service now receives every event type and reads a fresh snapshot), and the date wheel says *Yesterday/Today/Tomorrow* near today |
| B7 | ✗ Text set on a wheel doesn't stick: stepping stays |
| B8 | Fixed: `ACTION_SET_TEXT` (and Enter) never make a chip. Typing through the service's input method followed by a comma key does; the chip's description is `<address>`. Tapping a chip takes it off (or turns it into text, which is cleared). Anything that doesn't become a chip with an address asked for is taken off and named in the results (Q18) |
| B9 | Fixed: the editor (a WebView, sometimes without its id) refuses `ACTION_PASTE`; a paste through the input method keeps bold and links. The form's Description row then shows the text |
| B10 | ✓ Alert sheet |
| B11 | ✓ KS-Rooms: 10 rooms a page with Free/Busy (only plain *Free* and *Busy* seen) |
| B12 | ✓ Recent: 3 rooms with their status |
| B13 | ✓ Keyboard hidden and back; typing through the input method works while it is hidden |
| C1, C3 | ✓ 7 dry-run bookings in about 1.5 minutes (10–30 s each: the wheels take most of it) |
| C2 | ✓ No `Room Booking -` event left |
| C4 | ✓ STOP while the description was pasted: form discarded, *not done*, clipboard empty |
| C5 | ✓ Only KS-184 in the list at a busy time: *No room in your list was free*; form discarded |
| C6 | ✓ Back to the results after the run. Fixed: leaving the screen cancelled the reply refresh, logged as a failure |
| C7 | ✓ Same room with the Recent shortcut on and off (at that time the first free room wasn't in Recent; at 06:00 it was, and the shortcut picked it) |
| C8 | ✓ *Not done: it was moved, changed or deleted after you answered*; Outlook not opened |
| C9 | ✓ |
| C10 | ✓ *Dry run doesn't apply here: these changes are real* |
| C11 | Not applicable: one account |
| D1 | ✓ Saved with no prompt; the button is *Save*. The booking event is in the provider within seconds. Results said *1 booked* for a booking its room had declined: fixed, booked counts only rooms that hold |
| D2 | KS-103D **declined** within 20 s (twice, at 06:00); KS-117 **accepted** within 30 s. A declined room stays in the location (attendee status 2) |
| D3 | Fixed: *Change room* failed (`No 'Clear location'`): the clear button appears only once Add Location has loaded; it is now waited for. Then ✓: cleared, KS-117 from Recent, saved, no prompt. With a Zoom link added as a location: refused, nothing changed ✓. (A room picked from Recent on an Edit form shows twice in Outlook's location, `KS-117; KS-117`: harmless) |
| D4 | ✓ Prompt `Delete the event?` [Delete]; the event left the provider at once; the row went, nothing came back |
| D7 | Fixed: with the room taken off in Outlook, the provider kept the room's invitee row, *accepted*, for 17+ minutes (location empty, Outlook showing no room), so the app kept saying *Reserved*. A room invitee now counts only while the location names it; then *No room on it* with the warning ✓. The launcher's line now puts *1 without a room* first (it was cut off) |
| D8 | Not run (STOP just after Save needs exact timing) |
| D9 | ✓ Two `Room Booking - CA test A` events at 06:00 in KS-103D: *Delete booking* opened both, deleted nothing: *2 events … Outlook doesn't show which is the booking* |
| D11 | Not run on a booking. An original moved in Outlook reached the provider in seconds (C8, D14) |
| D12 | Not run as written. A room booked for part of an event's time shows as the note *KS-184 is booked for 13:05–13:30 (“calls”)* (C5) |
| D13 | ✓ After *Clear data*, the booking was listed as *Found in your calendar*; *Change room* worked and showed *Changed just now: waiting for Outlook…*. (The app's data and alarms were put back afterwards) |
| D14 | ✓ No room answered; then Yes, and the event moved in Outlook before *Book Rooms* (not done, C8); the next run suggests **Yes** |
| D15 | ✓ The Room booking screen's week line (`0 booked · 1 without a room`). The results header and the launcher's order were fixed after (D1, D7) and are covered by unit tests; not seen again on the phone |

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
| ~~ACTION_SET_TEXT works on the Compose title~~ ✓ B2 | `BookingNavigator.setTitle` | — |
| ~~NumberPicker steps with ACTION_SCROLL_FORWARD/BACKWARD~~ ✓ B5 (values read from a fresh snapshot) | `stepWheel` | — |
| ~~"a,b," in one ACTION_SET_TEXT makes chips~~ ✗ B8: each address is typed through the input method, then a comma key | `typePeople` | — |
| Chips show their person's address ✓ B8 (`<address>` description); a chip that doesn't is taken off and named (Q18) | `typePeople`, `PeopleReader.chips` | D5 with a real colleague |
| ~~HTML paste into the WebView editor~~ ✗ B9: pasted through the input method instead (keeps formatting) | `setDescription` (plain text fallback) | — |
| The form's Description row shows the text once set | `EventFormReader.descriptionRow` (found by position too) | a note "may not have been copied" in results |
| The form's account row reads "Name, address" | `EventFormReader.accountAddress` | B1: **every booking fails** until the reader is fixed |
| ~~No prompt after Save~~ ✓ D1 (a room, no people) | `save()` | with people: D6 |
| ~~Delete prompt labels~~ ✓ D4: `Delete the event?` [Delete] | `deleteBooking`, `DELETE_CONFIRMATIONS` | — |
| Removing a person: tapping the chip ✓ B8 (dummy address) | `removePerson`, `takeOff` | D5 with a real colleague |
| Room statuses are exactly Free/Busy ✓ B11 (none other seen) | `RoomChoice.status` | — |
| Outlook goes back to the Day view on the same day after Save | `ensureOnDay` before each booking | — (it's checked) |
| ~~After saving an **edited** event Outlook shows its details~~ ✓ D3 | `BookingNavigator.save` | — |
| Closed sub-screens (Add Location, Room Finder, Add People, picker) leave the tree, or at least stop being visible | the `visibleId` checks in `BookingReaders.kt`, `onlyForm` | every step would time out with "…didn't close" |
| The booking event shows in the provider under its title at the original's start, organised by Outlook's account (`eiderwhi@…`, one of the user's addresses) | `BookingStore.matches`, `pair`, `ownBookingEvents`; `RoomCover.cover` | D1–D2: bookings never found, so missing after 15 minutes |
| Booking events reach the provider within 15 minutes, Manage changes within about 2 ✓ D1–D3: seconds | `BookingStore.synced` (sync grace), the Manage hold | — |
| ~~A room that declines stays in the event's location~~ ✓ D2. A room taken off stays an invitee, accepted (D7): counted only while the location names it | `RoomCover.cover`, `RoomCover.withoutStaleRooms` | — |
| The details screen's time reads "HH:MM to HH:MM, duration: …" (seen in the captured screens) | `DetailsReader.read` → `end` | without it, Manage bookings tells events apart by title, start and room only. A missing end is let through for now; once D3–D5 show the end is always there, make it required (deferred from Codex's last review: requiring it before then could stop every Manage action) |
| Opening a booking for Change room / Edit people / Delete finds exactly one event: title, start, end, room | `BookingNavigator.openForEdit` | D3–D5, D9 |
| A visible overlay asking for portrait holds the screen upright | `ScanOverlay` (`screenOrientation`) | A10: Q17's fallback |
| ~~Outlook's LSHTM account has the only owned `Calendar` the app should read~~ ✓ A12 | `CalendarRows.mainCalendar` | — |
