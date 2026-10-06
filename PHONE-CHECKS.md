# Phone checks (deferred)

Room booking and the shared engine ([PLAN-ROOM-BOOKING.md](PLAN-ROOM-BOOKING.md)) were built on
2026-10-05 **without the phone**: everything below compiles, the readers pass their tests against
the screens captured that day, but nothing has run on the Pixel yet. This is the list of what to
check there, in order, with the data to collect. Questions for the user are in
[QUESTIONS.md](QUESTIONS.md).

Rules for testing on the phone (the user's, 2026-10-05): look at calendar events, **never emails**;
any event created must have **no invitees** and be **deleted afterwards**. A room counts as an
invitee (choosing one sends the room a meeting request), so **real bookings need the user's
go-ahead** (part D). Parts A–C send nothing: drafts are always discarded.

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
| A9 | Two **test events** (no invitees, deleted after) with the same title and start on one day: one Moveable, the other no label; scan | Both blocks opened; problem `…different labels, and Outlook doesn't show which is which`; nothing remembered for them. Then both Moveable: both read, one label. Note how Outlook orders the two blocks |
| A10 | Auto-rotate **on**; start a scan, then turn the phone sideways while Outlook is being driven | The screen stays portrait until the strip goes, then turns as usual. Start a scan with the phone sideways: log `Turning the screen upright for the run`, the screen turns first, the strip fits the status bar. If the screen still turns mid-run, the fallback is QUESTIONS.md Q17 |

## B. Probes on Outlook's form (nothing saved)

Each probe tries **one** step on whatever Outlook screen is showing and logs what happened
(`DebugProbes.kt`). Open the screens by hand: Outlook → Calendar → Day view → **New event**. Only
type dummy addresses (`nobody@example.com`). When done, **Cancel → Discard** the draft.

```bash
adb shell am broadcast -a com.thomaswcode.calendareventtimers.DEBUG_PROBE --es probe screen
```

| # | Screen | Probe | Expect / record |
|---|---|---|---|
| B1 | New event form | `screen` | Rows listed (People, Date `Mon 12 Oct`, Time `08:05 ▸ 09:00`, Location, Description, Alert); `account richard.white@lshtm.ac.uk` |
| B2 | Same | `title --es text "Probe title"` | `set=true`, form reads `Probe title`. If false: `BookingNavigator.setTitle` needs another way (click + IME) |
| B3 | Time row → picker | `screen` | `Time picker, wheels=false` (drag mode as it opens) |
| B4 | Tap the picker's mode button → Choose Time | `screen` | `wheels=true`, wheel values `Mon 12 Oct`, `8`, `05`; previous/next rows |
| B5 | Same | `wheel_step --ei wheel 1 --es dir forward` (scroll) and again with `--es how click` | Which moves the hour by one; `8 → 9`. Record time per step |
| B6 | Hour at 23 | `wheel_step --ei wheel 1` | Does it wrap to 0? (`stepWheel` takes the long way if not) |
| B7 | Same | `wheel_text --ei wheel 2 --es text 35` | Does `ACTION_SET_TEXT` set a minute **and keep it after Done**? If yes, the wheels could be set in one action each (faster) |
| B8 | Add People | `people --es text "nobody@example.com, nobody2@example.com,"` | Two chips from one action? Their addresses (`chips 2: [...]`). **Each chip must show its address** (description `<…>` or text): a booking that tells people fails otherwise, by design. Close with **✕**, not Done |
| B9 | Description editor | `paste` | `ok=true`; does the editor keep bold and the link? Then `set_description` (plain fallback). Record what the **form's** Description row shows after Done |
| B10 | Alert sheet | `screen` | `Alert sheet open` |
| B11 | Location → Room Finder → KS-Rooms | `screen` | Rooms with `FREE/BUSY`. Note any **other status text** (loading, "Free until …"): `RoomChoice.status` treats it as unknown = busy |
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
| C4 | STOP during a booking | Form discarded, Outlook back on the calendar, results say *not done* |
| C5 | An event at a time when every listed room is busy | *No room in your list was free*; nothing left in Outlook |
| C6 | The app comes back to the results after the run; Back → Room booking | — |
| C7 | Recent shortcut off (Settings) vs on | Same room chosen; time saved |
| C8 | Answer the wizard for a **test event**, then move it in Outlook before *Book Rooms* | Results: *Not done: it was moved, changed or deleted after you answered*; no form opened for it |

Only after C1–C7 pass: a dry run with **Notify = Yes** (the people's addresses are typed into the
draft, which is discarded; nothing is sent). Confirm it stays a draft (C2).

## D. Real bookings (**need the user's go-ahead**)

| # | Check | Expect / record |
|---|---|---|
| D1 | One booking, room only (Notify = No), on a quiet slot | Save: is there a prompt? (`save()` fails on any question, logging it.) Record the button's description (`Save` or `Send`) |
| D2 | The room's reply | Seconds until the provider shows the room `attendeeStatus` 1 (results say Reserved); Outlook's details say *Reserved* |
| D3 | Manage bookings → Change room | Edit Event form opens; *Clear location*; next free room; any "send update" prompt? |
| D4 | Manage bookings → Delete booking | Prompt text and buttons with a room on the event (`DELETE_CONFIRMATIONS` lists the labels accepted); the room's cancellation; booking marked deleted |
| D5 | Edit people (needs a consenting colleague) | Adding works by typing; **removing** a person: what tapping a chip offers (`removePerson` looks for Remove/Delete) |
| D6 | Notify one consenting colleague | They receive `Room Booking - …` with the room; their reply doesn't disturb the app |
| D7 | Take the room off a booking in Outlook (D1's, before deleting it) | 15 minutes after the change: Manage bookings says *No room on it* with a warning, and the event is offered again |
| D8 | D1's booking: Manage bookings → Change room, and press STOP just after Save | Results say what changed; Manage bookings shows the new room (recorded before the run tidied up) |
| D9 | Two bookings with the same title, time and room (e.g. D1 made twice), then Delete one in Manage bookings | Nothing is deleted: *2 events at … are '…', and Outlook doesn't show which is the booking* |

## E. Data to collect for the tests

Save as fixtures (`app/src/test/resources/fixtures/booking/`) with uiautomator dumps or the service's
`DEBUG_DUMP` (now unredacted on booking screens):

- the form with people added (what the People row shows), with a description (the Description row),
  with Alert None;
- Choose Time wheels and the drag picker, from the service (to replace the reconstructed fixtures);
- Room Finder showing a status other than Free/Busy, if one exists;
- any prompt after Save with a room (D1), the delete prompt with a room (D4), the chip menu (D5).

## F. Where the guesses are in the code

| Guess | Where | If wrong |
|---|---|---|
| ACTION_SET_TEXT works on the Compose title | `BookingNavigator.setTitle` | click the field, then set text again (already tried once) |
| NumberPicker steps with ACTION_SCROLL_FORWARD/BACKWARD; the virtual rows click | `stepWheel` | B5 decides which first |
| "a,b," in one ACTION_SET_TEXT makes chips | `typePeople` (falls back to one address at a time) | B8 |
| HTML paste into the WebView editor | `setDescription` (plain text fallback) | B9 |
| The form's Description row shows the text once set | `EventFormReader.descriptionRow` (found by position too) | a note "may not have been copied" in results |
| No prompt after Save | `save()` | D1: add the prompt to `PromptReader` and answer it |
| Delete prompt labels | `deleteBooking`, `DELETE_CONFIRMATIONS` | D4 |
| Removing a person from a chip | `removePerson` | D5 |
| Room statuses are exactly Free/Busy | `RoomChoice.status` | B11 |
| Outlook goes back to the Day view on the same day after Save | `ensureOnDay` before each booking | — (it's checked) |
| The booking event shows in the provider under its title at the original's start | `BookingStore.matches`, `BookingStore.pair`, reply check | D2 |
| After saving an **edited** event Outlook shows its details (the app closes them) or the calendar | `BookingNavigator.save` | D3: if it lands elsewhere, the change is reported as "may have been saved" |
| Closed sub-screens (Add Location, Room Finder, Add People, picker) leave the tree, or at least stop being visible | the `visibleId` checks in `BookingReaders.kt`, `onlyForm` | every step would time out with "…didn't close" |
| Chips show their person's address | `typePeople`, `removePerson` (strict) | B8: bookings that tell people fail (Q18) |
| The details screen's time reads "HH:MM to HH:MM, duration: …" (gives the end) | `DetailsReader.read` → `end` | without it, Manage bookings tells events apart by title, start and room only |
| The form's account row reads "Name, address" | `EventFormReader.accountAddress` | no account check (logged); a non-LSHTM account would only be refused when read |
| A visible overlay asking for portrait holds the screen upright | `ScanOverlay` (`screenOrientation`) | A10: Q17's fallback |
| Opening a booking for Change room / Edit people / Delete finds the right one: exact title, start, recorded room | `BookingNavigator.openForEdit` | D3–D5, also with two bookings at the same time (a re-booking after a decline) |
