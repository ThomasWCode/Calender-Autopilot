# Calendar Autopilot (Android)

Formerly *Calendar Event Timers*. Two things for your Outlook events labelled **Moveable** or
**Immoveable**, chosen when the app opens:

- **Timers**: alarms at their start (or 5 minutes before), the app's own exact alarms (with a date,
  unlike the Clock app's), ringing on the alarm stream and over the lock screen.
- **Room booking**: for next week (or today, tomorrow, the rest of this week), asks per event whether
  to book a room and whom to tell, then makes a separate Outlook event, `Room Booking - {title}`, at
  the same time with the same description, the people chosen and the first free room of your list,
  picked through Outlook's Room Finder.

Both drive the Outlook app through an accessibility service. To keep Outlook on screen as briefly as
possible, events, invitees and the rooms' replies come from Android's calendar provider (which
Outlook keeps in sync), and Outlook is opened only for labels not read before and for the bookings.

The design and the reasoning behind it are in [PLAN.md](PLAN.md) (alarms) and
[PLAN-ROOM-BOOKING.md](PLAN-ROOM-BOOKING.md) (room booking, the shared engine).

> **Status, 2026-10-05:** room booking and the engine were built without the phone and haven't run
> on it yet. [PHONE-CHECKS.md](PHONE-CHECKS.md) lists what to check there first;
> [QUESTIONS.md](QUESTIONS.md) the open questions and the answers the code assumes meanwhile.

## Build and install

Needs JDK 17+ (Android Studio's bundled JDK works) and the Android SDK with platform 37.

```bash
./gradlew assembleDebug
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Unit tests (parsers, the screen readers against real Outlook dumps in
`app/src/test/resources/fixtures`, alarm labels, trigger times across BST/GMT, the calendar
provider's rows, the label cache, and every booking rule):

```bash
./gradlew testDebugUnitTest
```

## First-time setup on the phone

Open the app; a **Finish setting up** card lists anything missing, and **⋮ → Setup checklist**
has a button for each item.

1. **Notifications**: allow when asked (a ringing alarm is a notification).
2. **Outlook reader**: Settings → Accessibility → Calendar Autopilot → On. If Android calls it a
   restricted setting: Settings → Apps → Calendar Autopilot → ⋮ → *Allow restricted settings*,
   then try again. The service only works inside Outlook, and only when you tap a button here.
3. **Calendar access** (read only): Allow. Needed for room booking; without it the timers read the
   whole day in Outlook as before.
4. **Full-screen alarms** and **exact alarms** are normally granted at install; the checklist
   says if not.
5. Keep the alarm volume up: the alarms use it, as the Clock app's do.

## Using it

Opening the app shows **Timers** and **Room booking** (also when you come back after 15 minutes).

### Timers

- **Set alarms for today / tomorrow** checks that day. Events come from the phone's calendar; Outlook
  opens only to read the labels of events it hasn't seen in their current version (a strip over the
  status bar says what it is doing; **STOP** ends it), and not at all when every label is remembered.
- The **review** lists the Moveable/Immoveable events still to come, each with *Set alarm* (on) and
  *5 min before* (off). Events that already have an alarm from this app show *✓ Alarm already set*.
  Today's events that have started are left out.
- **Upcoming alarms** lists what is set; **✕** cancels one (with Undo). Ringing alarms offer
  **Snooze 5 min** and **Dismiss**; unanswered ones stop after 10 minutes and leave a *Missed alarm*
  notification.
- **⋮ menu**: *Dry run*, *Test alarm in 1 / 2 minutes*, the setup checklist and the activity log.

Alarm labels read `Title (Label) @ Location`, with meeting links shortened: `Kristian pdr
(Moveable) @ Zoom`, `Chat about funding/IDM links (Moveable) @ Zoom; KS-121`.

### Room booking

- **Book rooms for next week** (the coming Monday to Friday), or **Today**, **Tomorrow**, **This
  week** for events added since. Events that already have a room (booked by the app, a room on the
  event, or another of your events with a room at that time) aren't shown at all.
- The **wizard** shows one event at a time: *Book a room?* (Yes) and, if yes, *Notify the others?*
  (No); if yes, the LSHTM invitees (staff, student, honorary, alumni addresses; never mailing lists or
  rooms) each with **✕**. Answers for a meeting that comes back are remembered, so next time each
  event is one **Next**; *Keep the rest as suggested* goes straight to the summary, the remaining
  events keeping the answers shown for them (last time's, else Yes and No). Events answered *no
  room* before aren't asked again; the summary lists them.
- The **summary** shows every event, who will be told and how long Outlook will be on screen. **Book
  Rooms** books them in one go: for each, a new Outlook event with the same time (set on Outlook's
  *Choose Time* wheels), the first free room of your list (Location → *Or browse with Room Finder* →
  KS-Rooms), the title `Room Booking - …`, the people, the same description and no alert. Before
  Save, the time, room, title and alert are read back, and each person told must show on a chip by
  their address (the description is checked only roughly); a failed step, or STOP, discards the
  form. Just before each booking the event is checked in the phone's calendar again: one moved,
  renamed, cancelled or given a room since you answered isn't booked (run again), and people no
  longer invited aren't told. A room counts as free only when Room Finder says plain *Free*.
- The **results** say what each event got: booked (with the room's reply as it arrives: *Reserved*,
  *Declined*…), no free room, failed, or *maybe booked* when Outlook didn't show whether it saved
  (check Outlook; the app finds out from the calendar). Rooms not in Room Finder are named.
- **⋮ → Manage bookings**: your bookings from today on, with warnings (room declined or taken off,
  event moved or gone), and any `Room Booking - …` events of yours the app has no record of; per
  booking *Change room*, *Edit people*, *Delete booking* (Outlook sends the cancellations; nothing
  about the deletion is remembered, so the event is offered again next time). The app acts only
  when exactly one event in Outlook fits the booking; changes here are always real.
- **⋮ → Dry run**: every booking is filled in in Outlook, room included, then discarded; no
  bookings or answers are recorded (labels read and people's names are still remembered).
- **Settings**: the rooms, in order (add, remove, reorder, reset to the 25 KS rooms); Room Finder's
  building; the Recent-list shortcut; your own addresses; and **Clear memory**, which forgets every
  remembered answer, label and name (bookings, alarms and settings stay), or only the answers or the
  labels.

While a booking runs, the description is pasted through the clipboard, which is cleared afterwards
(anything you had copied is gone).

Whenever the app is working in Outlook (timers or rooms), the screen stays upright: turning the
phone doesn't turn it to landscape. The strip over the status bar holds it, as if auto-rotate were
off, and lets go when the run ends; your auto-rotate setting itself is never changed.

## How it works

| Part | Where |
|---|---|
| The phone's calendar provider: Outlook's events, invitees, descriptions | `calendar/` |
| Labels remembered per event version; which events to open in Outlook | `engine/` |
| Driving Outlook (accessibility service, UI driver, navigation, readers, the booking form, selectors) | `outlook/` |
| Booking rules (ranges, rooms already held, people, rooms, wheels), run and manage controllers | `booking/` |
| Parsing Outlook's texts, alarm labels, trigger times, review rows | `domain/` |
| Timers scan and review confirm | `scan/ScanController.kt` |
| Alarms (Room, device-protected storage); labels, bookings, answers (`autopilot.db`) | `data/` |
| Scheduling, ringing, rescheduling after reboot/update | `alarm/` |
| Screens | `ui/` |

Alarms are set with `AlarmManager.setAlarmClock()`, so they are exact in Doze and show as the
phone's next alarm. AlarmManager forgets alarms on reboot and force-stop, so they are re-registered
from the database after boot (also before the first unlock), after an app update or a clock
change, and every time the app opens.

## Troubleshooting

- **A scan or booking fails**: the screen says why. *⋮ → Activity log* has every step; the window
  tree at the point of failure is in logcat (`adb logcat -s CET`). Texts are blanked there unless the
  screen is Outlook's calendar or one of the booking screens.
- **Outlook was updated and scans or bookings broke**: its view ids or texts may have changed. They
  are all in `outlook/OutlookSelectors.kt` (the event form is found by its UK-English texts); refresh
  the XML fixtures and run the tests.
- **Looking at Outlook's tree while developing**: don't use `uiautomator dump` during a run. It
  suspends accessibility services while it runs, which stops the run, and it leaves out off-screen
  nodes the app relies on (and shows nothing of the time picker). In debug builds the service dumps
  what it sees instead:

  ```bash
  adb shell am broadcast -a com.thomaswcode.calendareventtimers.DEBUG_DUMP
  ```

- **Trying one booking step** (debug builds): probes act on the Outlook screen showing and never save
  anything; see `outlook/DebugProbes.kt` and [PHONE-CHECKS.md](PHONE-CHECKS.md) part B:

  ```bash
  adb shell am broadcast -a com.thomaswcode.calendareventtimers.DEBUG_PROBE --es probe screen
  ```

- **An alarm was silent**: check the alarm volume (the setup checklist warns when it is 0).
