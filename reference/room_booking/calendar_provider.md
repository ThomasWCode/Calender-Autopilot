# What Outlook puts in Android's calendar provider

Captured on the Pixel 8a with Outlook 5.2638.1 on 2026-10-05, with
`adb shell content query --uri content://com.android.calendar/...`. People's names and addresses
below are replaced by placeholders; rooms are real.

The app will read this with the `READ_CALENDAR` permission (decided 2026-10-05). It never writes
to it: every change goes through Outlook's own screens.

## Calendars

Outlook's account (`account_type = com.microsoft.office.outlook.USER_ACCOUNT`,
`account_name = eiderwhi@lshtm.ac.uk`) syncs these calendars:

| `_id` | `name` / display name | `calendar_access_level` | Notes |
|---|---|---|---|
| 34 | `Calendar` | 700 (owner) | **The main calendar**: the one Outlook's event details call `Calendar (eiderwhi@lshtm.ac.uk)` |
| 32 | `TB Modelling Group` | 700 | A group calendar |
| 28, 30 | `United Kingdom holidays`, `Birthdays` | 200 (read) | |
| 29, 31, 33, 35, 36 | `KS-207A`, `KS-121`, `KS-117`, `KS-Room103D`, `KS-106 (EPH staff only)` | 200 | Room calendars added in Outlook; read-only. Only these five, not all 25 KS rooms |
| 37 | `{7C77…}01` | 200 | Unknown |

Ids are local to the phone. The app must find the main calendar by account type, owner access
and name, never by a hard-coded id.

## Events and instances

`content://com.android.calendar/instances/when/<beginMs>/<endMs>` expands recurring events into
occurrences (one row per occurrence, `event_id` pointing at the series' row). Useful columns:

| Column | Example | Meaning for the app |
|---|---|---|
| `event_id`, `begin`, `end` | `87261`, `1791205500000` | One occurrence; epoch ms |
| `title` | `TB Vx modelling 2-weekly call` | Same text as Outlook shows |
| `eventLocation` | `https://lshtm.zoom.us/j/…`, `KS-103D`, `Hybrid` | Same as the Day view's "at location" |
| `allDay` | `0` / `1` | All-day events are ignored, as now |
| `organizer` | `eiderwhi@lshtm.ac.uk` or another address | Who owns the meeting |
| `hasAttendeeData` | `1` | Attendee rows exist |
| `selfAttendeeStatus` | `1` accepted, `4` tentative, `2` declined | Skip events the user declined |
| `availability` | `0` busy, `1` free, `2` tentative | |
| `rrule` | `FREQ=WEEKLY;WKST=MO;BYDAY=TU` | Recurring series |

From the `events` table (by `_id`):

| Column | Example | Meaning |
|---|---|---|
| `_sync_id` | `4172d8ef-d1d8-425b-b4e6-979bf125ee1c` | Stable id of the event (series) |
| `sync_data3` | `DwAAABYAAAAk7cNWBRhESbQNLIf7gigPAAj96EhN` | **Exchange change key** (see below) |
| `sync_data1` | `AQAAADMACQ…` | Item id, opaque |
| `original_id`, `original_sync_id` | `87355`, `801dec05-…` | Set on a changed single occurrence of a series |
| `description` | `<html …><body …>…</body></html>` | **The event body, as HTML** (Word-filtered HTML for Outlook-made events) |
| `eventColor` / `displayColor` | `NULL` / `-52` on every event | **Categories are not synced** (as found on 2026-10-04) |
| `dirty` | `0` | |

### The change key changes when the category changes (tested)

A test event (no invitees, deleted afterwards) was created in Outlook, then given the category
Moveable in Outlook's details screen:

| When | `sync_data3` |
|---|---|
| Created 21:42:28, visible in the provider by 21:42:43 | `…AAj96EhN` |
| Category set 21:43:59, provider by 21:44:05 | `…AAj96Ehu` |

So `(_sync_id, sync_data3)` identifies a version of an event, and a label read from Outlook can be
reused for as long as both are unchanged. The column is undocumented, so the cache also expires
after a while and can be cleared by hand (PLAN-ROOM-BOOKING.md §3.3).

### Sync timing

- Changes made in the Outlook app reach the provider within seconds (6–15 s above).
- Outlook also runs a periodic sync job for the provider every 15 minutes
  (`dumpsys content`: `PERIODIC Reason=Periodic (period=15m00s)`), plus syncs on change.

## Attendees

`content://com.android.calendar/attendees`, by `event_id`:

| Column | Values seen |
|---|---|
| `attendeeName` | Display name, or the address again, or `NULL` (for the user themself) |
| `attendeeEmail` | Mixed case: `First.Last@lshtm.ac.uk`, `First.Last@LSHTM.ac.uk`, `first.last@lshtm.ac.uk` |
| `attendeeType` | `1` required, `2` optional, **`3` resource (a room)** |
| `attendeeStatus` | `0` none, `1` accepted, `2` declined, `3` invited (no reply), `4` tentative |

Examples (anonymised):

```
event 87323 (organizer person.k@lshtm.ac.uk):  Person A <Person.A@lshtm.ac.uk> type 1 status 1
                                               Person B <person.b@gmail.com>    type 2 status 1
                                               Person C <Person.C1@student.lshtm.ac.uk> type 1 status 3
event 118397 "call", 12 Oct 09:05-09:30:       KS-121 <ks-121@lshtm.ac.uk>      type 3 status 1   <- Reserved
event 106116 "… [room added]":                 KS-207A <ks-207a@lshtm.ac.uk>    type 3 status 2   <- room declined
event 88824 "Room booking for TBMod …":        KS-103D <KS-103D@lshtm.ac.uk>    type 3 status 1
                                               Person D <Person.D@LSHTM.ac.uk>  type 1 status 3
                                               (user) <eiderwhi@lshtm.ac.uk>    type 2 status 1   <- the user, name NULL
```

Address domains containing `lshtm` across all attendee rows on the phone:

| Domain | Rows (type 1 / 2 / 3) | Counted as "LSHTM person" (decided) |
|---|---|---|
| `lshtm.ac.uk` | 7430 / 3253 / 625 | yes (type 3 rows are rooms: excluded as rooms) |
| `student.lshtm.ac.uk` | 245 / 43 / 0 | yes |
| `hon.lshtm.ac.uk` | 6 / 0 / 0 | yes |
| `alumni.lshtm.ac.uk` | 1 / 2 / 0 | yes |
| `lists.lshtm.ac.uk` | 28 / 7 / 0 | **no** (mailing lists) |
| `lshtm.onmicrosoft.com` | 0 / 4 / 15 | no (resources) |

The organizer is not always among the attendee rows; use the `organizer` column as well.

## How the user books rooms by hand today

All visible in the provider, which is how the app will spot events that already have a room:

| Pattern | Example | Room |
|---|---|---|
| A separate event at the same time | `call`, Mon 12 Oct 09:05–09:30, location `KS-121` (also 5 Oct 14:05–15:00) | `KS-121` type 3, status 1, no reminder (`hasAlarm=0`) |
| `Room booking for …` | `Room booking for High-level call`, location `KS-103D`, with three people | `KS-103D` type 3, status 1 |
| Recurring `Room booking for … for 180days` | KS-117 / KS-123 | type 3, **status 2: declined** (beyond the room's booking window) |
| The event itself, renamed | `… [room added]` | KS-207A, status 2 |
