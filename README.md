# Calendar Autopilot (Android)

Formerly *Calendar Event Timers*. Sets alarms for your Outlook events labelled **Moveable** or **Immoveable**. Tap **Set alarms for
today** or **Set alarms for tomorrow**; the app reads that day in the Outlook app, lists the
labelled events, and after you confirm, sets one alarm per event: at the start time, or 5 minutes
before if you tick that. The alarms are the app's own exact alarms (with a date, unlike the Clock
app's), ring on the alarm stream, and show over the lock screen.

The design and the reasoning behind it are in [PLAN.md](PLAN.md).

## Build and install

Needs JDK 17+ (Android Studio's bundled JDK works) and the Android SDK with platform 37.

```bash
./gradlew assembleDebug
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Unit tests (parsers, readers against the real Outlook dumps in `app/src/test/resources/fixtures`,
alarm label, trigger times including BST/GMT changes):

```bash
./gradlew testDebugUnitTest
```

## First-time setup on the phone

Open the app; a **Finish setting up** card lists anything missing, and **⋮ → Setup checklist**
has a button for each item.

1. **Notifications**: allow when asked (a ringing alarm is a notification).
2. **Outlook reader**: Settings → Accessibility → Calendar Autopilot → On. If Android calls it a
   restricted setting: Settings → Apps → Calendar Autopilot → ⋮ → *Allow restricted settings*,
   then try again. The service only works inside Outlook, and only when you tap a scan button.
3. **Full-screen alarms** and **exact alarms** are normally granted at install; the checklist
   says if not.
4. Keep the alarm volume up: the alarms use it, as the Clock app's do.

## Using it

- **Set alarms for today / tomorrow** opens Outlook and reads the day (a strip over the status bar
  says what it is doing; **STOP** ends it). Don't touch the screen meanwhile. A 28-event day takes
  about 70 seconds.
- The **review** lists the Moveable/Immoveable events still to come, each with *Set alarm* (on) and
  *5 min before* (off). Events that already have an alarm from this app show *✓ Alarm already set*.
  Today's events that have started are left out.
- **Upcoming alarms** on the home screen lists what is set; **✕** cancels one (with Undo).
  Ringing alarms offer **Snooze 5 min** and **Dismiss**; unanswered ones stop after 10 minutes and
  leave a *Missed alarm* notification.
- **⋮ menu**: *Dry run* (scan and review without setting anything), *Test alarm in 1 / 2 minutes*,
  the setup checklist and the activity log.

Alarm labels read `Title (Label) @ Location`, with meeting links shortened: `Kristian pdr
(Moveable) @ Zoom`, `Chat about funding/IDM links (Moveable) @ Zoom; KS-121`.

## How it works

| Part | Where |
|---|---|
| Reading Outlook (accessibility service, UI driver, navigation, screen readers, selectors) | `outlook/` |
| Parsing Outlook's texts, alarm labels, trigger times, review rows (pure Kotlin) | `domain/` |
| Scan orchestration and review confirm | `scan/ScanController.kt` |
| Alarm records (Room, device-protected storage) | `data/` |
| Scheduling, ringing, rescheduling after reboot/update | `alarm/` |
| Screens | `ui/` |

Alarms are set with `AlarmManager.setAlarmClock()`, so they are exact in Doze and show as the
phone's next alarm. AlarmManager forgets alarms on reboot and force-stop, so they are re-registered
from the database after boot (also before the first unlock), after an app update or a clock
change, and every time the app opens.

## Troubleshooting

- **A scan fails**: the home screen says why, and nothing is set. *⋮ → Activity log* has every
  step; the window tree at the point of failure is in logcat (`adb logcat -s CET`). Texts are
  blanked there unless the screen is Outlook's calendar.
- **Outlook was updated and scans broke**: its view ids may have changed. They are all in
  `outlook/OutlookSelectors.kt`; refresh the XML fixtures and run the tests.
- **Looking at Outlook's tree while developing**: don't use `uiautomator dump` during a scan. It
  suspends accessibility services while it runs, which stops the scan, and it leaves out off-screen
  nodes the app relies on. In debug builds the service dumps what it sees instead:

  ```bash
  adb shell am broadcast -a com.thomaswcode.calendareventtimers.DEBUG_DUMP
  ```

- **An alarm was silent**: check the alarm volume (the setup checklist warns when it is 0).
