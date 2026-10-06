# Calendar Autopilot: room booking, and less time in Outlook

Status: **built 2026-10-05 without the phone; not yet run on it.** Everything compiles, lint is
clean and the unit tests pass, including the screen readers against the Outlook screens captured
for this plan; what still has to be tried on the Pixel is in [PHONE-CHECKS.md](PHONE-CHECKS.md),
and the questions that came up while building, with the answers the code assumes meanwhile, are in
[QUESTIONS.md](QUESTIONS.md). How the build differs from this plan: §9. The app was renamed (display
name *Calendar Autopilot*, formerly *Calendar Event Timers*; the package id is unchanged). The alarm
feature and the Outlook findings it rests on are in [PLAN.md](PLAN.md). The Outlook screens and
calendar data captured for this plan are in [reference/room_booking/](reference/room_booking/README.md).

## 0. Summary

The app gets a second feature, **Room booking**. For the Moveable/Immoveable events of next week
(or today, tomorrow, the rest of this week), it asks per event whether to book a room and whom to
tell, then creates a separate Outlook event, `Room Booking - {title}`, at the same time with the same
description, the people chosen, and the first free room from an ordered list of KS rooms, picked
through Outlook's Room Finder. Opening the app shows two buttons: **Timers** and **Room booking**.

To keep Outlook on screen as briefly as possible, both features move onto a shared engine. Event
lists, invitees with their email addresses, descriptions and the rooms' replies come from
Android's calendar provider, which Outlook keeps in sync and which costs no screen time. Outlook
is opened only to read the labels of events that are new or have changed since the app last read
them, and to make or change bookings.

## 1. Requirements, with the answers given on 2026-10-05

| # | Requirement | How it is met |
|---|---|---|
| R1 | Opening the app offers **Timers** or **Room booking** | New launcher screen (§3.1). Today's home screen becomes the Timers screen |
| R2 | Minimise the time the app takes over the screen; reuse recent runs | Shared engine (§3.2–3.3): provider data, labels cached per event version, Outlook opened only for what is new. Estimates in §3.17 |
| R3 | Book for **next week**; redo for **today**, **tomorrow** and **this week** for new events | Range buttons (§3.4). Re-runs ask only about events with no answer yet (§3.7) |
| R4 | Same list as the timers: events labelled Moveable or Immoveable, each presented | Same label rule as the alarms (`EventParser.matchLabel`) |
| R5 | Per event: *Book a room?* (default **yes**); if yes, *Notify the others?* (default **no**); if yes, the invitees with an **✕** to deselect | Wizard (§3.8). Remembered answers override the defaults per recurring event (§3.7) |
| R6 | Only invitees whose address is at LSHTM | **Any LSHTM person**: `lshtm.ac.uk`, `student.`, `hon.`, `alumni.lshtm.ac.uk`; never `lists.lshtm.ac.uk` (mailing lists), rooms, or the user (§3.6) |
| R7 | A more hidden screen to **edit and delete** bookings | Room booking ⋮ → **Manage bookings** (§3.12) |
| R8 | The booking is a **separate event**: same time and description, title `Room Booking - {original event name}`, the people to notify; Location → *Or browse with Room Finder* → KS-Rooms → the first available room of the list, in order | §3.9–3.10 |
| R9 | Settings: the room list can be **altered, added to and reordered** | Settings screen (§3.13). Default: the 25 rooms below |
| R10 | No room available → **report at the end** | The draft is discarded and listed in the results (§3.11) |
| R11 | Events one after the other, then a **Book Rooms** button | Wizard → summary → Book Rooms (§3.8) |
| R12 | **Memory** so fewer presses are needed each time (e.g. who has an LSHTM address) | Addresses come straight from the provider; labels, answers, decisions and people are remembered (§3.7) |
| R13 | Rename the app | **Calendar Autopilot**, done |
| — | Week days | **Mon–Fri** |
| — | Data source | **Read Android's calendar provider** (`READ_CALENDAR`) |

Default room list, in order: KS-103D, KS-117, KS-119a, KS-121, KS-123, KS-129, KS-136, KS-182,
KS-183, KS-184, KS-185, KS-207A, KS-233, KS-248b, KS-259, KS-260, KS-265, KS-481, KS-482, KS-483,
KS-G19, KS-G20, KS-G21, KS-G22, KS-G31. All 25 exist in Room Finder's KS-Rooms list.

---

## 2. What the phone showed (2026-10-05)

Pixel 8a, Android 17, Outlook 5.2638.1 (unchanged since 2026-10-04), dark theme. Everything
below was looked at without sending anything: drafts were discarded, and the one saved test event
had no invitees and was deleted.

### 2.1 Android's calendar provider has nearly everything except labels

Details in [calendar_provider.md](reference/room_booking/calendar_provider.md).

- **Events** of Outlook's main calendar (`name = Calendar`, owner access), expanded into
  occurrences by the `instances` table: title, start, end, location, organiser, recurrence, the
  user's own reply.
- **Attendees with email addresses** (`attendees` table): name, address, type (`1` required,
  `2` optional, **`3` resource = room**), reply (`1` accepted … `2` declined …). So the app never
  needs to open contact cards to learn addresses.
- **Descriptions** as HTML (`events.description`).
- **Rooms' replies**: the room on a booking is a type-3 attendee; status `1` is what Outlook shows
  as *Reserved*, `2` is a decline. Booking results can be checked without opening Outlook.
- **Identity and versions**: `_sync_id` is stable; `sync_data3` is Exchange's **change key**. A test
  event's `sync_data3` changed when **only its category** was changed in Outlook (`…AAj96EhN` →
  `…AAj96Ehu`, in the provider about 6 s later). A label read once can be reused until the change
  key changes.
- **Not synced: categories** (as found on 2026-10-04: `eventColor` NULL, the same `displayColor`
  everywhere). Labels still have to be read in Outlook.
- **Freshness**: changes made in Outlook reach the provider within seconds (a new event in under
  15 s). Outlook also syncs it every 15 minutes.
- Five room calendars are also synced (KS-207A, KS-121, KS-117, KS-Room103D, KS-106), not all 25,
  so Room Finder stays the source of room availability.

### 2.2 How rooms are booked by hand today

The calendar already holds the pattern this feature automates: a separate event at the same time
with the room as a resource attendee, sometimes with colleagues invited, and no reminder:

- `call` with KS-121 at the same time as `TB Vx modelling 2-weekly call` (5 Oct, 14:05–15:00),
  and another `call` with KS-121 on 12 Oct whose details say *Reserved*;
- `Room booking for High-level call` with KS-103D and three people;
- recurring `Room booking for … for 180days` with KS-117/KS-123: **declined** by the rooms
  (beyond their booking window), which is why weekly single bookings are wanted;
- events renamed `… [room added]` with the room on the event itself.

The app's "already has a room" check (§3.5) recognises all of these.

### 2.3 Outlook's new-event form

Reached with the **New event** button (desc `New event`) at the bottom right of the Day view,
next to Copilot. Captured in `new_event_form.xml/.png`.

- It is **Jetpack Compose with no view ids**. Rows are found by their texts: the title
  `EditText` (placeholder child text `Title`), `People`, `All Day Event`, `Date` (`Mon 12 Oct`),
  `Time (GMT+1)` (`08:05 ▸ 09:00`, `Duration: 55 minutes`), `Time zone`, `Location`,
  `Online Meeting` (Zoom, **off** by default), `Description`, `Attachments`, `Repeat` (`Never`),
  `Alert` (`15 minutes before`), `Show as` (`Busy`), `Private` (off). Top bar: desc `Cancel`,
  desc `Save`, and the account as desc `Richard White, richard.white@lshtm.ac.uk`.
- The **date defaults to the day selected in the calendar**. The time defaulted to 08:05–09:00 on
  both days tried, which looks like Outlook's "start meetings 5 minutes late" setting (the user's
  events often start at :05). The app always sets the time.
- **Cancel** on a changed form asks `Discard event?` (`Discard` / `Cancel`); an unchanged form
  closes without asking.
- After **Save** the Day view stayed on the event's day; after a **discarded** draft it went back
  to today once. The navigator checks the selected day before every booking.

### 2.4 The time picker: use the wheels, not the drag editor

The Time row opens a **drag editor** on a day grid every time (`time_picker_drag_mode.png`). Its
toolbar button `#action_picker_mode` switches to **Choose Time** (`time_picker_wheels_*.png`):

- `Start time` / `End time` tabs; three `NumberPicker` wheels each: date (`Mon 12 Oct`), hour (`8`),
  minute (`05`, one-minute steps), each with a `#numberpicker_input` showing the value;
  `#action_done` applies.
- Tapping a value did not open a keyboard; tapping the row below steps a wheel by one.
- **Changing the start moves the end with it** (duration kept), so usually only the start is set
  and the end is checked.
- `uiautomator dump` shows nothing of this screen; the app's accessibility service sees all of it
  (`time_picker_service_dumps.txt`).

### 2.5 Location, Room Finder and the KS rooms

- Location row → **Add Location** (`add_location_recent.xml`): `#text_input` *Enter a place*,
  **`#room_finder`** (desc *Or browse with Room Finder*), `#book_workspace`, and a **Recent** list
  (`#location_picker_results`) whose rows show a room and its status for the form's time
  (`#row_location_picker_result_name` / `_detail` = `Free`). At 09:05, when KS-121 was taken, the
  Recent list left KS-121 out: it seems to list only free rooms.
- Room Finder (`room_finder_buildings.xml`): `#search_edit_text` *Enter a building*, then
  `#building_list` with `Recent` and `All` sections; rows `#text_room_name` = `KS-Rooms`,
  `TP2-Rooms`, …
- **KS-Rooms** (`room_finder_ks_rooms_*.xml`): `#room_list`, a RecyclerView of about **40 rooms in
  alphabetical order**, about four screens long, so it must be scrolled. Each row has the name and
  **`Free` or `Busy` for the form's time slot**. It includes rooms that are not in the user's list
  and carry suffixes: `KS-105c (EPH only)`, `KS-106 (EPH staff only)`, `KS-162A (LAORS Training
  only)`, `KS-G47 (Exec Office)`, …
- Tapping a room returns to Add Location with a chip (`#location_chip_text` `KS-103D`,
  `#multiple_location_picker_clear_symbol` *Clear location*); `#action_done` returns to the form,
  whose Location row then reads `KS-103D`. People stays empty: the room becomes a resource attendee
  when the event is saved.

### 2.6 People, description, alert

- **Add People** (`add_people_*.xml`): `Required` / `Optional` tabs, `#text_input` *Type a name or
  an email address*. **Enter does nothing; a comma turns the typed address into a chip**
  (`#contact_chip_text`). An unknown address offers *Search Directory*.
- **Description** (`description_editor.xml`): a dialog with a **WebView** rich-text editor
  (`#rich_edit_text`), a formatting toolbar and `#action_done` (desc *Done button*). Pasting the
  original's HTML should keep its links and formatting (to be confirmed, §7).
- **Alert** (`alert_sheet.xml`): a sheet with `None`, `At time of event`, `5/10/15/30 minutes
  before`, `1 hour before`, … The user's manual bookings have no reminder.

### 2.7 A booked room, editing, deleting

- The details of the user's `call` booking show the room with its reply:
  `#event_details_location_name` `KS-121` and `#event_details_location_response` **`Reserved`**
  (`booking_details_reserved.xml`). No overflow menu: only `#action_edit` (Edit).
- Edit opens the same Compose form titled `Edit Event`, with **`Delete event`** at the bottom
  (`edit_event_form_bottom.xml`) → `Delete the event?` → `Delete` (`delete_prompt.xml`; event without
  invitees).

### 2.8 Not seen yet

These need a real booking, which sends a meeting request to a room (§7): Save with a room or people
on the form ("Save" or "Send"? any prompt?), how quickly the room replies, the delete and edit
prompts for an event with attendees.

---

## 3. Design

### 3.1 Screens

```
Launcher (every launch)                      Room booking
┌──────────────────────────────────────┐     ┌──────────────────────────────────────┐
│ Calendar Autopilot                 ⋮ │     │ ←  Room booking                    ⋮ │
│ [Finish setting up]  (if needed)     │     │  [ Book rooms for next week       ]  │
│ ┌──────────────────────────────────┐ │     │     Mon 12 – Fri 16 October          │
│ │ Timers                           │ │     │  [ Today ] [ Tomorrow ] [ This week ]│
│ │ Next alarm: Tue 09:00            │ │     │                                      │
│ └──────────────────────────────────┘ │     │ This week: 4 booked · 1 without room │
│ ┌──────────────────────────────────┐ │     │ Next week: not booked yet            │
│ │ Room booking                     │ │     │ 2 rooms waiting for a reply          │
│ │ Next week: not booked yet        │ │     └──────────────────────────────────────┘
│ └──────────────────────────────────┘ │     ⋮ = Manage bookings · Settings · Dry run
└──────────────────────────────────────┘
⋮ = Settings · Setup checklist · Activity log
```

- **Timers**: today's home screen, unchanged apart from a back arrow and the shared engine
  underneath (§3.14).
- **Room booking**: range buttons named with their dates; *Today*, *Tomorrow* and *This week* are
  disabled (with the reason) when they hold no working day. One status line per week; the list
  with edit and delete is in **Manage bookings**, deliberately one level down.
- The wizard, summary, results and Manage bookings screens are sketched in §3.8, §3.11 and §3.12.
- The app still comes back to the screen that started a run (review, wizard or results) after
  Outlook, as today.

### 3.2 Where each piece of data comes from

| Needed | Source | Outlook screen time |
|---|---|---|
| Events in a range: title, start, end, location, organiser, recurrence, own reply | Provider `instances` | none |
| Labels (categories) | Outlook's details screen; **cached per event version** | only for new or changed events, about 2.5 s each; a recurring series is read once |
| Invitees and their addresses | Provider `attendees` + `organizer` | none |
| Description | Provider `events.description` (HTML) | none |
| Rooms already booked; rooms' replies | Provider: type-3 attendees and their status | none |
| Room availability | Outlook Room Finder, for the booking's own time | part of each booking |
| Creating, changing, deleting bookings | Outlook's event form | about 15–25 s per booking |

Nothing is ever written to the provider: every change goes through Outlook's screens, as the user
does by hand, so Exchange sends the room request, invitations and cancellations itself.

### 3.3 The shared engine

| Part | Responsibility |
|---|---|
| `calendar/CalendarStore` | Provider queries: finds the main calendar (account type `com.microsoft.office.outlook.USER_ACCOUNT`, owner access, name `Calendar`; never a hard-coded id), lists occurrences in a range, their attendees, descriptions, sync ids and change keys. Maps rows to plain Kotlin types (`CalEvent`, `Attendee`), so the logic is unit-testable |
| `data/LabelCache` | `syncId → (changeKey, categories, readAt)`. A hit needs the **same change key** and a read within **30 days** (the column is undocumented, so entries also expire). No change key → always read |
| `OutlookNavigator.readLabels(targets)` | New mode next to today's whole-day scan: for each day with targets (in date order) go to the day, then open **only** the target events (found by start time + title in the block's description, as now), read the category row, close. One occurrence per series; recurring series are read on a day already being visited |
| Label pass | provider → cache → `readLabels` for the misses → `LabelledEvent(event, categories, label)`. If nothing misses, Outlook isn't opened at all |
| Cross-check | On every day it visits anyway, the navigator compares the Day view's blocks with the provider's events and logs differences (diagnostics for calendars or sync gaps; no behaviour change) |

`ScanController` becomes a runner for three kinds of Outlook job, one at a time, all with the
overlay strip and STOP: **label pass**, **booking run**, **booking change** (Manage bookings). The
timers' review keeps working as now.

### 3.4 Ranges and candidate events

Ranges (Mon–Fri, device time zone):

| Button | Days |
|---|---|
| Book rooms for next week | the coming Monday–Friday (on a Sunday that starts tomorrow) |
| Today | today, if a weekday; only events that haven't started |
| Tomorrow | tomorrow, if a weekday |
| This week | today (if a weekday) to Friday; disabled on Saturday and Sunday |

An occurrence is a **candidate** if it is in the main calendar, timed (not all-day), starts and ends
on the same day, isn't cancelled or declined by the user, isn't one of the app's own
`Room Booking - ` events, and has the label **Moveable** or **Immoveable** (same rule as the alarms).

### 3.5 Events that already have a room

Checked from the provider, so free of screen time. In order:

1. **An app booking** for the meeting's whole time: a `bookings` row for this occurrence that isn't
   deleted, or a provider event of the user's titled `Room Booking - {title}` starting at the same
   time (finds bookings the database doesn't know, e.g. after a reinstall; a colleague's event of
   that name doesn't count). Its room's reply gives Reserved / Waiting / **Declined**. A booking
   shorter than the meeting (made longer since) doesn't count; it shows as a note.
2. **A room on the event itself**: a type-3 attendee, or an attendee whose name or address matches
   a room in the list, that hasn't declined (`… [room added]`, a colleague's meeting in KS-103D).
   Also when the location names a room in the list that isn't an attendee ("Location says
   KS-103D", unconfirmed); a room that declined stays in the location, so it doesn't count there.
3. **Another of the user's events** covering the whole time with an **accepted** room (`call` with
   KS-121, `Room booking for …`). Partial cover is shown as a note only.

Covered → **not shown at all** (the user's decision on 2026-10-05; first planned as shown with
**Book a room? = No**). Only the log names them. A booking whose room **declined**, or that no
longer has a room, isn't covered: the event is offered again, with Yes.

### 3.6 People to notify

From the original's attendee rows plus its organiser:

- keep addresses whose domain, ignoring case, is `lshtm.ac.uk`, `student.lshtm.ac.uk`,
  `hon.lshtm.ac.uk` or `alumni.lshtm.ac.uk` (addresses come in mixed case: `@LSHTM.ac.uk`);
- leave out `lists.lshtm.ac.uk` (mailing lists), type-3 attendees and room addresses
  (`ks-121@lshtm.ac.uk` is at `lshtm.ac.uk`), the user's own addresses (`eiderwhi@lshtm.ac.uk` from
  the provider's account; `richard.white@lshtm.ac.uk` from the form's account description, learnt
  on the first booking run and kept), and people who **declined** the original;
- one entry per address (lower case), named as in the attendee row (else the address), sorted by
  name.

If nobody is left, the wizard says "No LSHTM invitees to notify" and skips the question.

### 3.7 Memory

**Fewer presses in Outlook** (screen time):

| Remembered | Key | Saves |
|---|---|---|
| Labels | `_sync_id` + change key (§3.3) | opening each event, about 2.5 s; whole label passes when nothing changed |
| Addresses of invitees | come with the event from the provider | opening contact cards: never needed. Kept in `people` for names in Manage bookings |
| The user's own addresses | learnt once | — |
| First-choice room | Add Location's **Recent** list shows the first room of the list as `Free` → take it there | Room Finder's three screens (about 3 s). Only the first room: Recent leaves busy rooms out, so it can't show that the rooms before a later one are busy. Switch in Settings |
| Day already on screen | the navigator keeps the selected day between bookings | navigation |

**Fewer presses in the app**:

| Remembered | Key | Effect |
|---|---|---|
| *Book a room?*, *Notify?*, people removed | **series key**: the series' `_sync_id` for a recurring event (`original_sync_id` for a changed occurrence), else the normalised title | the wizard is pre-filled with last time's answers, marked "as last time" |
| What each occurrence got | **occurrence key**: `_sync_id` + start | Today / Tomorrow / This week ask only about **new** events (a moved event counts as new); "No room was free" is retried |

Memory is never silent: remembered answers are labelled, and Settings can forget them.

### 3.8 Wizard, summary, Book Rooms

1. Tap a range → label pass in Outlook if needed (overlay as now) → back in the app.
2. **Wizard**: one screen per candidate that isn't covered and has no answer yet, pre-filled, so an
   unchanged event is one **Next**. *Keep the rest as suggested* jumps to the summary, the
   remaining events keeping their suggested answers (renamed from *Use these answers for the rest*,
   which promised copying the current answers, 2026-10-06).

   ```
   ┌──────────────────────────────────────────────┐
   │ ←  Next week                     Event 3 of 8│
   │ Tue 13 Oct · 11:05–12:00                     │
   │ Weekly modelling meeting        [Moveable]   │
   │ @ Zoom · 6 invitees, 2 at LSHTM              │
   │                                              │
   │ Book a room?            [ Yes ]  [ No ]      │
   │ Notify the others?      [ Yes ]  [ No ]      │
   │   Person A   person.a@lshtm.ac.uk        ✕   │
   │   Person B   person.b@lshtm.ac.uk        ✕   │
   │   Removed: Person C   ↩                      │
   │   (answers as last time)                     │
   │                                              │
   │ [ Back ]                          [ Next ]   │
   │              Keep the rest as suggested ›    │
   └──────────────────────────────────────────────┘
   ```
3. **Summary**: every candidate in the range, including ones answered before (but never ones
   that already have a room); tapping a row reopens its wizard screen. It names everyone who will be notified and estimates
   the time Outlook will be on screen. Button: **Book Rooms (n)**.

   ```
   ┌──────────────────────────────────────────────┐
   │ ←  Next week · 8 events                      │
   │ Mon 12 09:00  Planning meeting  Room · tell 2│
   │ Mon 12 14:00  Core group call   No room      │
   │ Tue 13 11:05  Weekly modelling  Room         │
   │ Thu 15 14:05  1:1 with Person D Has KS-121 ✓ │
   │ …                                            │
   │ Notifies: Person A, Person B                 │
   │ Outlook will be on screen for about 2 min.   │
   │ [ Cancel ]                  [ Book Rooms (3)]│
   └──────────────────────────────────────────────┘
   ```
4. **Booking run** in Outlook (§3.9), day by day, in time order. STOP never leaves a half-made
   event: the open form is discarded, and what was already saved is kept and reported.
5. **Results** (§3.11).

**Dry run** (the existing ⋮ toggle) applies here too: every booking is filled in completely,
room included, then **discarded**. Results say which room would have been booked. This is how the
flow is tested without sending anything.

### 3.9 Making one booking in Outlook

Before: Outlook's calendar in Day view on the event's day (existing navigator). The soft keyboard
is hidden for the whole run (`softKeyboardController.showMode = SHOW_MODE_HIDDEN`, restored
afterwards) so it never covers lists. Node actions are used throughout; a gesture tap only as a
fallback on visible bounds, because other apps' overlays (the Wispr Flow bubble) float over Outlook.

| # | Step | How | Check before going on |
|---|---|---|---|
| 1 | New event | click desc `New event` | the form: desc `Save` and the title `EditText` |
| 2 | **Time** | click the row whose text starts `Time`; if `#date_time_picker` is absent, click `#action_picker_mode`; **Start time**: step the date, hour and minute wheels to the event's start (`ACTION_SCROLL_FORWARD`/`BACKWARD` on each `NumberPicker`, the shorter way round, reading `#numberpicker_input` after each step; `ACTION_SET_TEXT` tried first); **End time**: check, step if needed; `#action_done` | the form's date (`EEE d MMM`) and `HH:mm ▸ HH:mm` match |
| 3 | **Room** (after the time: availability is for the form's time) | click row `Location`; Recent shortcut (§3.7) or `#room_finder` → building row `KS-Rooms` → choose (§3.10) → click the row; `#action_done` | Location row reads the room. **No room free**: `Close` back to the form, Cancel → Discard, report |
| 4 | Title | `ACTION_SET_TEXT` on the title field: `Room Booking - {title}` | text equal |
| 5 | People (only if notifying) | click row `People`; Required tab; set `#text_input` to the addresses, each followed by a comma; if chips don't appear, one address at a time with its directory suggestion; `#action_done` | one `#contact_chip_text` per person |
| 6 | Description (only if the original has one) | click row `Description`; focus the WebView's editable node; put the HTML (with a plain-text alternative) on the clipboard and `ACTION_PASTE`; fallback `ACTION_SET_TEXT` with plain text; `#action_done`; clear the clipboard | the row shows the start of the text |
| 7 | Alert → None | click row `Alert`, then `None` | row says `None` |
| 8 | Re-read the whole form | title, date, time, room, number of people, description, alert | anything wrong → Cancel → Discard, report *failed* with the reason |
| 9 | Save | click desc `Save` (or `Send`, §7); answer any prompt (§7) | back on the Day view within ~6 s, else dump and report |
| 10 | Record | `bookings` row written immediately (§3.15) | |

Order matters: time before room (Room Finder shows availability for the form's time), room before
everything else (an unavailable room wastes least work). Before each booking the navigator makes
sure the event's day is selected (it usually still is), so the form's date already matches and the
date wheel normally needs no steps.

The booking event otherwise keeps Outlook's defaults: Show as Busy, not private, no online
meeting, no repeat. The original's location is not copied: the room is the location, and any
meeting link is in the copied description.

### 3.10 Choosing the room

```
prefs = Settings' room list, in order
seen  = {}                                        // Room Finder name → Free | Busy
loop over pages of #room_list (at most 6):
    wait until every visible row's status reads Free or Busy
    record the visible rows in seen
    for room in prefs:                            // walk the preferences in order
        status = seen[match(room)] ?: break       // not seen yet: need the next page
        if status == Free: return room
    if !scrollForward(#room_list): break          // end of the list
return first Free room of prefs in seen, else NONE
```

- **Names match** when equal ignoring case, or when the Room Finder name is the setting followed by
  ` (` (`KS-106 (EPH staff only)`). So `KS-G32` would never match `KS-G32A`.
- Rooms in Settings that Room Finder doesn't list are reported once ("KS-999 isn't in Room
  Finder") and otherwise skipped.
- The default list is in the same alphabetical order as Room Finder, so the first screen usually
  decides.

### 3.11 After the run: checking and reporting

```
┌──────────────────────────────────────────────┐
│ Rooms for next week                          │
│ ✓ Mon 09:00 Planning     KS-103D  Reserved   │
│ ✓ Tue 11:05 Weekly mod.  KS-117   Waiting…   │
│ ✗ Thu 16:00 Reports      No room free        │
│ ! Wed 10:00 Staff disc.  Failed: wrong time  │
│ Notified: Person A, Person B (Planning)      │
│                                    [ Done ]  │
└──────────────────────────────────────────────┘
```

- Immediately: booked (room; waiting for its reply), **no room free**, failed (reason), not done
  because of STOP.
- Then the app watches the provider (a content observer plus a check every 5 s for up to 2
  minutes while the screen is open): it finds each booking event (title + start), stores its
  `_sync_id`, and reads the room's reply: `1` **Reserved**, `2` **Declined**, `4` **Tentative**
  (a room that needs someone's approval), otherwise Waiting. A declined room is offered
  *Change room*.
- Replies are refreshed whenever the Room booking or Manage bookings screens open.

### 3.12 Manage bookings (Room booking ⋮)

```
┌──────────────────────────────────────────────┐
│ ←  Bookings                                  │
│ Tue 6 October                                │
│  14:05–15:00  Weekly modelling call          │
│    KS-121 · Reserved · notified 2        ⋮   │
│ Mon 12 October                               │
│  09:00–09:30  Planning meeting               │
│    KS-103D · Declined                    ⋮   │
│    ⚠ The event now starts at 10:00           │
└──────────────────────────────────────────────┘
⋮ per booking: Change room · Edit people · Delete booking
```

- Lists the app's bookings from today on: database rows with their replies from the provider, plus
  provider events titled `Room Booking - ` that the database doesn't know (marked "found in the
  calendar").
- Warnings: the room declined; the original event has moved, been cancelled or lost its label
  (the booking is never deleted automatically).
- Each action is a short Outlook run with the overlay: go to the day → open the block
  `Room Booking - {title}` at its time → `#action_edit` → the same Compose form (`Edit Event`):
  - **Change room**: Location → *Clear location* → room choice as §3.10, or a room picked from the
    list → Done → Save;
  - **Edit people**: People → remove chips / add addresses → Done → Save;
  - **Delete booking**: scroll to `Delete event` → confirm. Outlook sends the cancellations to the
    room and the people notified.

### 3.13 Settings (launcher ⋮, also from Room booking ⋮)

- **Rooms, in order of preference**: ▲ ▼ to reorder (drag can come later), ✕ to remove (with
  Undo), a field to **add** a room (not empty, no duplicates ignoring case), *Reset to the default
  list*. Stored in preferences as a JSON list.
- Room Finder building: `KS-Rooms`.
- *Use the Recent list for the first-choice room* (on).
- Memory: what is remembered, counted; **Clear memory** (everything); *Forget remembered answers*;
  *Read all labels again next time*.
- My addresses (learnt, editable): left out of notify lists.

The LSHTM domains and Mon–Fri are fixed in code (decided above), not settings.

### 3.14 The timers on the shared engine

- *Set alarms for today / tomorrow* runs the label pass for that day: when every event's label is
  cached, the review appears **without opening Outlook**. After Sunday's booking run, Monday's
  alarms usually need no Outlook time at all.
- The review, alarms, de-duplication and "already started" rules are unchanged; times come from
  the provider.
- The whole-day Day-view scan stays as the **fallback** (calendar permission refused, provider
  error).
- Today's scan reads whatever calendars Outlook's Day view shows; the engine reads the main
  calendar only. The cross-check (§3.3) logs any event the Day view has and the provider's main
  calendar hasn't, so a missing calendar would show up.

### 3.15 Data

A new Room database, `autopilot.db`, in normal (credential-protected) storage. The alarm database
is untouched (it lives in device-protected storage so alarms survive a reboot before unlock).

| Table | Columns | For |
|---|---|---|
| `label_cache` | `syncId` (PK), `changeKey`, `categories` (JSON), `title`, `readAt` | labels per event version |
| `bookings` | `id` (PK), `occurrenceKey`, `seriesKey`, `originalTitle`, `date`, `start`, `end`, `bookingTitle`, `room`, `notified` (JSON), `state` (`SAVED`, `NO_ROOM`, `FAILED`, `DELETED`), `roomReply` (`WAITING`, `RESERVED`, `DECLINED`), `bookingSyncId`, `problem`, `createdAt`, `checkedAt` | the app's bookings |
| `answers` | `occurrenceKey` (PK), `seriesKey`, `date`, `answer` (`BOOKED`, `NO_ROOM_WANTED`, `NO_ROOM_FREE`, `FAILED`), `at` | what each occurrence got: re-runs ask about new events only |
| `series_memory` | `seriesKey` (PK), `bookRoom`, `notify`, `removedEmails` (JSON), `updatedAt` | last answers per series |
| `people` | `email` (PK, lower case), `name`, `lastSeen` | names for addresses |

`occurrenceKey = _sync_id + "@" + start millis`; `seriesKey = original_sync_id ?: (recurring ?
_sync_id : "title:" + normalised title)`. Rows older than 8 weeks are pruned.

### 3.16 Permissions and setup

| Item | Why | How |
|---|---|---|
| `READ_CALENDAR` (new) | the engine | runtime prompt on first use of Room booking or the timers; a setup checklist item. Read only: no `WRITE_CALENDAR` |
| Accessibility service (existing) | driving Outlook | unchanged config. Its description must now say that it also **creates, changes and deletes room-booking events** in Outlook when asked |
| Clipboard | pasting the description | written during a booking, then cleared (the user's previous clip is lost; noted in the README) |
| Keyboard | hidden during runs | the service's `SoftKeyboardController`, no permission |

The debug dump blanks texts outside the calendar (to keep mail out of logs). The event form,
pickers, Add Location, Room Finder, Add People and the description dialog are calendar screens too,
and must be recognised as such, so a failed booking leaves a readable dump.

### 3.17 Screen-time estimates

| Run | Outlook on screen |
|---|---|
| Next week, the very first time | label pass: about 30–40 distinct series × 2.5 s + about 3 s per day ≈ **1.5–2 min**; then about **15–25 s per booking** |
| Next week, later weeks | label pass only for new or changed events (often under 20 s); bookings as above |
| Today / Tomorrow / This week | usually no label pass; bookings for new events only |
| Timers, today or tomorrow | **none** when the labels are cached; else about 2.5 s per new or changed event |
| Manage bookings, one change | about 10–20 s |

Rejected: running in the background or at night (driving Outlook needs the screen on and
unlocked); inferring labels from block colours in a screenshot (past events are dimmed, colours
aren't unique, more than one label shows one colour); writing bookings into the provider (Exchange
must send the room request; the user asked for Room Finder).

---

## 4. Implementation phases

Each phase: unit tests pass, an on-phone check, a commit.

1. **Engine.** `READ_CALENDAR` and its setup item; `CalendarStore` and its types; `autopilot.db`
   with `label_cache`; `OutlookNavigator.readLabels`; label pass; cross-check logging; the timers
   moved onto it with the old scan as fallback.
   *Tests*: provider rows → types (fixtures shaped like `content query` output), main-calendar
   choice, cache hit/miss/expiry, one read per series, label targets per day.
   *Phone*: scan tomorrow twice; the second run doesn't open Outlook. Change a label; the next run
   reads just that event.
2. **Screens and settings.** Launcher, Timers screen, Room booking screen, Settings with the room
   list editor. *Tests*: room-list operations, preferences round trip.
3. **Booking logic, pure Kotlin.** Ranges (Mon–Fri, weekends, the DST week of 25 Oct), candidates,
   cover detection, people filter, keys, memory defaults, room choice and name matching, wheel-step
   arithmetic, title. *Tests* for each.
4. **Outlook writers.** New selectors (form texts, picker, Add Location, Room Finder, Add People,
   description, alert, prompts) in `OutlookSelectors`; pure readers tested against
   `reference/room_booking` (copied into the test fixtures); `BookingNavigator.createBooking()`
   with **dry run** (fill, then discard); debug dumps for the new screens.
   *Phone*: dry-run a whole week; time it.
5. **Wizard, summary, run, results**, including STOP (discard the open form) and the provider check
   of replies. *Phone*, with the user's permission: one real booking with a room and nobody to
   notify, then delete it (§7).
6. **Manage bookings**: list, replies, warnings, change room, edit people, delete. *Phone*, with
   permission: change a test booking's room, then delete it.
7. **Docs**: README (the new screens, the permission, the clipboard), PLAN files updated with what
   the phone showed while building.

## 5. Risks

- **Outlook updates**, now with a Compose form that has no ids and is found by its UK-English
  texts. All selectors in `OutlookSelectors`, readers tested against fixtures, failures stop the
  booking with a dump rather than guessing.
- **Telling people by mistake.** Notify defaults to No; the summary names everyone who will be
  told; dry run; people are only added for events where the user chose to notify.
- **Double bookings.** The booking row is written right after Save; cover detection also reads the
  provider; re-runs ask only about new events.
- **Room Finder said Free, the room declines** (a race, or rules such as the 180-day window). The
  reply check shows Declined and offers Change room.
- **The change key is undocumented.** 30-day expiry, *Read all labels again*, and no caching when
  the key is missing. If a label read in Outlook ever disagrees with a cached one for an unchanged
  key, the log says so and the cache is cleared.
- **Provider lag or other calendars.** Outlook pushes changes at once and syncs every 15 minutes;
  the cross-check logs gaps.
- **Half-made events.** A form is only saved after every field has been re-read and matches; STOP
  or any failure discards it.
- **Wheel stepping** is slow (up to ~30 steps for minutes) or misses a step: every step is read
  back, steps are capped, and a time that won't stick fails that booking.
- **Description paste** may lose formatting or fail in the WebView: plain-text fallback; the
  clipboard is overwritten during runs.
- **Overlays** from other apps over Outlook: node actions, not coordinates.
- **Long recurring bookings are not attempted**: one booking per occurrence, which the rooms accept.
- **DST** (25 Oct 2026): all times are local wall-clock times set in Outlook's own picker; the
  range and trigger maths use `java.time` and are tested.

## 6. Decisions

| Topic | Decision |
|---|---|
| App name | **Calendar Autopilot**: display name only; package id, theme and log tag unchanged (done) |
| Opening the app | Launcher with **Timers** and **Room booking**, every time |
| Data | **Android calendar provider** (read only) for events, invitees and addresses, descriptions, room replies; **Outlook** for labels (cached per change key) and for every change |
| Days | **Mon–Fri**. Next week = the coming Mon–Fri; also Today, Tomorrow, This week |
| Events offered | Moveable/Immoveable; timed; same-day; not cancelled or declined; today: not started |
| Defaults | Book a room = **Yes**; Notify = **No**; last answers per series override |
| Events with a room | **Not shown at all** (user, 2026-10-05): an app booking holding its room, a room on the event or in its location, or another of the user's events with an accepted room over the whole time |
| People | `lshtm.ac.uk`, `student.`, `hon.`, `alumni.lshtm.ac.uk`; never `lists.lshtm.ac.uk`, rooms, the user, or people who declined |
| Booking event | `Room Booking - {title}`; same date, start, end; same description (formatted); people as **Required**; **Alert None** (like the manual bookings); otherwise Outlook's defaults; location = the room only |
| Room | First **Free** room in the Settings order through Location → *Or browse with Room Finder* → KS-Rooms; Recent list used only for the first-choice room (switchable) |
| No room free | Draft discarded; listed at the end; retried by later runs |
| Time input | **Choose Time wheels** via the picker's mode button; never the drag editor |
| Re-runs | Ask only about events with no answer yet; the summary shows the rest |
| Manage bookings | Room booking ⋮ → Manage bookings: change room, edit people, delete; warnings for declined rooms and moved events |
| Settings | Room list (add, remove, reorder, reset), building, Recent shortcut, forget answers, read labels again, my addresses |
| Memory | Labels per event version; answers per series; what each occurrence got; names per address; own addresses |
| Timers | Same engine: no Outlook when labels are cached; whole-day scan as fallback |
| Recurring events | One booking per occurrence, never a recurring booking |
| Testing | **Dry run** fills and discards. Real bookings only with the user's permission. Test events have no invitees and are deleted afterwards |

## 7. Open items

To settle while implementing. They need **real bookings**, which send meeting requests to a room,
so each needs the user's go-ahead:

1. Saving with a room (and with people): is the button still `Save` or `Send`; is there a prompt?
2. How long the room takes to reply (provider status `1`/`2`).
3. Deleting an event with attendees: the prompt, and whether it asks for a cancellation message.
4. Editing an event with attendees: the "send update" prompt.
5. Whether `ACTION_SET_TEXT` works on the wheel inputs (fewer steps) and on the people field with
   commas (one action for all addresses).
6. Whether the HTML paste keeps links and formatting in the description.

Proposed test: one booking with a room only, on a quiet slot, deleted straight after; then one
that notifies a single colleague who has agreed to receive a test invitation (the user's own
addresses are never offered for notifying).

## 8. Reference files

- [reference/room_booking/README.md](reference/room_booking/README.md): every capture and the
  selectors in it.
- [reference/room_booking/calendar_provider.md](reference/room_booking/calendar_provider.md): the
  provider's calendars, events, attendees, change key and sync timing.
- [reference/room_booking/time_picker_service_dumps.txt](reference/room_booking/time_picker_service_dumps.txt):
  the picker as the service sees it.

## 9. As built (2026-10-05, without the phone)

Built in five commits after this plan (engine; booking rules; Outlook automation; screens; manage
bookings). Where it differs from, or adds to, the sections above:

- **Code map.** `calendar/` (CalendarStore, CalendarRows: provider queries and pure row mapping),
  `engine/` (LabelPass, label cache policy, which occurrence to open per series), `booking/`
  (Ranges, Rooms, People, Cover, Planner, FormText: pure rules; BookingController and
  ManageController: the runs), `outlook/BookingReaders.kt` (one reader per booking screen),
  `outlook/BookingNavigator.kt` (the form), `outlook/OutlookSession.kt` (one Outlook run at a time:
  the STOP strip, the keyboard hidden, the app brought back to the run's screen),
  `outlook/DebugProbes.kt`, `data/AutopilotDatabase.kt` and `data/BookingStore.kt`, and the screens
  in `ui/` (launcher, Timers, Room booking, wizard and summary, results, Manage bookings, Settings).
- **Events with a room are hidden** (§3.5, changed by the user). "Another event with a room" counts
  only when the user organised it (their `call` / `Room booking for …` events): a colleague's seminar
  in a room at the same time doesn't hide the user's meeting (found in review).
- **Deleting a booking** in Manage bookings is temporary: nothing about it is remembered, so later
  runs offer the event again (the user's answer to QUESTIONS.md Q2, 2026-10-06).
- **Clear memory** (Settings, 2026-10-06): one button forgets everything remembered (answers per
  meeting and per event, labels read in Outlook, people's names), with the counts shown above it and
  a confirmation; bookings, alarms and settings stay. Forgetting only the answers or only the labels
  is still offered.
- **Dry runs record no bookings or answers** (occurrence answers and series memory); labels read in
  Outlook and people's names are remembered as in any run. Manage bookings is always real.
- **Events declined by the user** are left out of both features (QUESTIONS.md Q4), and the engine
  reads Outlook's main calendar only (Q3); the Day view is compared with the provider on every day
  visited and differences are logged.
- **Label cache self-check.** If a label read in Outlook ever differs from a remembered one with the
  same change key, the whole cache is dropped and the log says so.
- **Reply check.** Each booking's room reply (Reserved, Tentative, Declined) is read from the
  provider when the Room booking, results and Manage screens open, and every 5 s for two minutes on
  the results screen. A booking not found in the calendar 15 minutes after saving is "not found";
  one found without a room 15 minutes after it was made or its room changed is "no room" (taken off
  in Outlook), which doesn't hold the event's room.
- **Codex review fixes (2026-10-06).** Events sharing a day, a start and a title look the same in
  the Day view, so their labels are read from every block that may be one of them and kept only if
  all agree (never one event's labels for another). Rooms are matched by whole name, so KS-103
  never passes for KS-103D when Manage bookings picks the event to change or delete. Just before
  each booking, the event is checked in the provider again: one moved, renamed, cancelled, declined,
  started or given a room since the wizard isn't booked ("not done", run again), and people no
  longer invited aren't told. A form that can't be left without saving fails its booking instead of
  being reported as discarded.
- **Second round (2026-10-06): Codex's second review and another agent's review.**
  - *Edits act on one event only.* Manage bookings opens every event that could be the booking and
    acts only when exactly one fits its title, start, end (from the details screen) and room; two
    that fit stop it, as Outlook doesn't show which is which.
  - *STOP can't lose a change.* What a Manage change did is recorded inside the Outlook run, before
    it tidies up; a confirmed delete runs to its end like Save.
  - *"Maybe saved" is said so.* If Outlook goes somewhere unexpected after Save, the result says to
    check Outlook (not "not booked" or "nothing changed"); a new booking is kept as one until the
    calendar shows whether it was saved (missing after the sync grace if not).
  - *A form that can't be closed after a failed step stops the run*, so nothing else goes into it.
  - *Replies belong to rooms.* Only the booking's own room's reply counts; after a room change the
    calendar may still show the old room for a while, and after the sync grace a room changed in
    Outlook is followed. A booking whose event has been seen is followed by its sync id only, at its
    own time: moved or gone, it is missing, never given a lookalike. Cancelled events are ignored.
  - *Cover is the user's own and whole* (§3.5), and the check before booking uses the same rules,
    except for bookings made earlier in the same run.
  - *People.* Each person told must show on a chip by address before Save, and a removal is checked
    the same way (by the chip's description or its text); the user's own form address is never added.
  - *The form's account must be an LSHTM one* (several accounts in Outlook), and the run's own
    addresses grow as the form shows them.
  - *Labels.* If the change key proves unreliable mid-run, the labels the run took from memory are
    left out too (with a message to run again), not only forgotten for next time.
  - *Small ones.* Only a plain "Free" is free; an invalid `&#…;` in a description is kept as
    written; the people dialog scrolls; week summaries count only bookings holding a room as
    booked and name missing ones; the wizard's shortcut is renamed (§3.8); Manage bookings lists
    the user's booking events it has no record of.
  - *Not done:* a fake-driver harness for testing whole booking and edit sequences (the other
    review's I09); the pure rules and readers are tested, and the sequences are in PHONE-CHECKS.md.
- **Third round (Codex, 2026-10-06).** A booking missing from the calendar gets the same sync grace
  after a change as one missing its room (keeping its last reply meanwhile); only the user's own
  `Room Booking - …` events are paired with the app's bookings; a booking made shorter or longer in
  Outlook takes its event's end, so cover follows it; answering yes clears an earlier "no room" for
  that occurrence at once; the clipboard is cleared even when STOP lands mid-paste.
- **The screen stays upright** during every Outlook run (the user's request, 2026-10-06): the STOP
  strip asks for portrait, which Android honours for any visible window, as if auto-rotate were off.
  Nothing to restore: the hold goes with the strip, even if the app crashes. If the run starts
  sideways, it waits for the screen to turn first. The system auto-rotate setting is never touched
  (that would need the *Modify system settings* permission and restoring; QUESTIONS.md Q17).
- **Code review fixes (2026-10-05).** STOP no longer leaves Outlook marked busy; Change room leaves
  the booking's own room out; Change room, Edit people and Delete open each candidate event and act
  only on the one with the exact title, start and recorded room; once Save is pressed it runs to the
  end, and an edited event counts as saved when its details come back; replies are paired with
  bookings by sync id, then room, never one event for two bookings; reply checks run off the main
  thread; the Alert sheet is no longer mistaken for a form whose default alert is "At time of event".
- **Prompts are never guessed.** Any question Outlook asks after Save fails that booking (the form
  is discarded, the question logged); the delete prompt is answered only with a known label.
- **Time picker fixtures are reconstructed**: uiautomator shows nothing of the picker, so its test
  fixtures were rebuilt from the service's dump and the screenshots (PHONE-CHECKS.md B and E replace
  them with real dumps).
- **Debug probes** try one booking step at a time on whatever Outlook screen is showing, from adb,
  for the phone checks; logged dumps no longer blank the booking screens.
- **Navigation.** A cold start, or coming back after 15 minutes away, opens the launcher; after an
  Outlook run the app returns to that run's screen (QUESTIONS.md Q14).
