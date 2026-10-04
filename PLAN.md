# Calendar Event Timers — Plan & Findings

On-phone Android app that reads **today's or tomorrow's** Outlook calendar events
labelled **Moveable** or **Immoveable**, extracts *title, location, label, start time*,
and sets an alarm for each one. Per event the user chooses:

| Option | Default |
|---|---|
| Set an alarm for this event? | **Yes** |
| Alarm 5 minutes before start (instead of at start)? | **No** (alarm at start time) |

Home screen has two buttons: **Set alarms for today** and **Set alarms for tomorrow**.

Status: **planning only — nothing built yet.** See [Decisions](#decisions).

**Form factor (decided):** an Android app that reads Outlook's Day view through an
AccessibilityService and **schedules its own exact alarms** (date + time) with
`AlarmManager.setAlarmClock()`, showing its own ringing screen. The Google Clock app is
**not** used, because its `SET_ALARM` intent can't take a date (see §3.1).

---

## 1. Environment findings (captured 2026-10-04)

| Item | Value |
|---|---|
| Device | Google Pixel 8a (`akita`), serial `43251JEKB14052`, Android 17 |
| Screen | 1080 × 2400, **always portrait** while the app runs (no landscape handling needed) |
| Time | 24-hour clock, timezone `Europe/London` |
| Outlook | `com.microsoft.office.outlook` v5.2638.1, main activity `com.acompli.acompli.CentralActivity` |
| Outlook account | `eiderwhi@lshtm.ac.uk`, calendar shown as `Calendar (eiderwhi@lshtm.ac.uk)` |
| Clock app | `com.google.android.deskclock` v9.1 — handles `SET_ALARM` via `com.android.deskclock.HandleSetApiCalls` (investigated, **not used**, see §3.1) |
| Build tooling on PC | OpenJDK 17.0.20, Android SDK at `%LOCALAPPDATA%\Android\Sdk`, Android Studio installed |
| ADB gotcha | Git Bash rewrites `/sdcard/...` into a Windows path → set `MSYS_NO_PATHCONV=1` |
| Python gotcha | Windows console is cp1252; Outlook text contains `▸` (U+25B8) → force UTF-8 output |

### 1.1 Why UI automation is required (data source investigation)

Outlook **does** sync its calendars into Android's `CalendarContract` provider
(e.g. `_id=34`, `account_type=com.microsoft.office.outlook.USER_ACCOUNT`,
display name `Calendar`). Title, location and start time are all available there.

**However, categories (labels) are NOT synced:**
- `eventColor` is `NULL` and `displayColor` is identical (`-52`) on every Outlook event.
- `extendedproperties` contains only `hangoutLink`, `secretEvent`,
  `shared:calendarProviderEventType` (all Google), nothing from Outlook.

So the label can only come from **(a)** the Outlook app UI, or **(b)** the Microsoft
Graph API (`/me/calendarView` returns a `categories` array). This plan uses (a).

---

## 2. Outlook UI map (portrait, 1080 × 2400)

> Prefer selecting by **resource-id / content-desc**, not coordinates. The portrait
> coordinates below are a useful reference, but taps and swipes should still use the
> live node bounds, because event positions depend on the day's events and the scroll position.

### 2.1 Bottom navigation (always visible on main screens)

| Element | Selector | Bounds (portrait) |
|---|---|---|
| Mail tab | `content-desc="Mail"` | `[0,2171][360,2337]` |
| **Calendar tab** | `content-desc="Calendar"` | `[360,2171][720,2337]` → tap `(540,2254)` |
| Apps tab | `id=menu_more`, desc `Apps` | `[720,2171][1080,2337]` |

Outlook opens on **Inbox** by default. The Calendar tab must be tapped every run.

### 2.2 Calendar header & week strip

| Element | Selector | Notes |
|---|---|---|
| Month title / day picker | `id=calendar_month_title_button` | desc e.g. `October 2026, day picker` |
| **View switcher** | `id=menu_calendar_views` | desc `Switch away from Day view` → **current view is in the desc** |
| Search | `content-desc="Search"` | |
| Week strip day buttons | `Button`, text = day number, desc = date + flags | row `y∈[395,549]`, 7 buttons of width ~155 (Mon … Sun) |

Week-strip desc variants observed:

| Desc | Meaning |
|---|---|
| `Sunday 4 October, today, Selected` | today, currently shown |
| `Sunday 4 October, today` | today, not shown |
| `Monday 5 October, Selected` | another day, currently shown |
| `Monday 28 September` | plain |

**View switcher menu** (popup after tapping `menu_calendar_views`), items are
`TextView id=title`:

| Text | Centre (portrait) |
|---|---|
| Agenda | (695, 320) |
| **Day** | (695, 446) |
| 3 Day | (695, 572) |
| Month | (695, 698) |

⚠ The selected view **does not reliably persist**: after leaving the calendar it came
back in Day view once and Agenda another time. The app must check the
`menu_calendar_views` desc and switch to **Day** if it does not say `...from Day view`.

### 2.3 Navigating to today / tomorrow (tested)

Outlook remembers the last day viewed (it reopened on Monday 5 Oct after the user
browsed there), so **always navigate explicitly**.

| Action | Result |
|---|---|
| Swipe week strip **right** (`150→950` at strip y, 300 ms) | Shows previous week; **selection unchanged** |
| Swipe week strip **left** (`950→150`, 300 ms) | Shows next week; selection unchanged |
| Tap a week-strip button | That day is shown in Day view; its desc gains `, Selected` ✅ |
| **Fast fling** on Day grid (`950→100` at y=1300, **120 ms**) | Moves to **next day**; strip follows into next week if needed ✅ |
| Slow swipe on Day grid (300 ms) | No change ❌ |

**Chosen algorithm (deterministic):**
1. `target = today` or `today + 1` (device timezone); `label = "EEEE d MMMM"` in
   `Locale.UK` (e.g. `Monday 5 October`).
2. Look for a week-strip Button whose desc **starts with** `label`. If it isn't there,
   swipe the strip left (for tomorrow) or right (going back), then look again. Max 3 tries.
3. Click it. Verify its desc now contains `Selected`.
4. Fallback if step 2 fails: select today (`, today`), then fling the Day grid once for
   tomorrow.

Tested: Sunday 4 Oct (today) → strip swipe left → tap `Monday 5 October` →
`Monday 5 October, Selected` with 16 events listed (`reference/day_view_tomorrow.xml`).

### 2.4 Day view (the view to use)

Each event is a clickable `android.widget.Button` with an empty `text` and a
content-desc that summarises the event. Inside it is a `TextView id=event_title`
(often truncated) and sometimes `id=event_location`.

Content-desc format observed:

```
<Weekday> <D> <Month>, <HH:MM> to <HH:MM>, <Title>[, at location <Location>][ with <Attendees>][, private event][, in N mins]
<Weekday> <D> <Month>, <HH:MM> to <Weekday> <D> <Month>, <HH:MM>, <Title> ...        (spans midnight)
<Weekday> <D> <Month> to <Weekday> <D> <Month>, All Day, <Title>                      (all-day)
<Weekday> <D> <Month>, Work location: <...>                                          (work-location chip, not an event)
```

Real examples:
- `Sunday 4 October, 14:00 to 14:55, Zumba, at location Pancras Square Leisure, 5 Pancras Square, London, Camden, N1C 4AG`
- `Monday 5 October, 17:35 to 18:00, Kristian pdr, at location https://lshtm.zoom.us/j/859…&from=addon with Kristian Godfrey`
- `Saturday 3 October, 22:00 to Sunday 4 October, 06:00, T no comp with Sally Oldfield (Not work), Thomas White`
- `Tuesday 4 August, 12:00 to Friday 30 October, 12:30, Rui ZHANG-PhD in lshtm` (long-running, sits in the all-day strip)

Notes:
- The content-desc is **good for listing and filtering** (date prefix, start time) but
  **titles can contain commas**, so parse title/location from the details screen instead.
- The Day grid scrolls vertically; events outside the viewport are not in the node
  tree. Collect across scroll positions and dedupe by content-desc.
- Overlapping events are drawn side-by-side and narrow; tap the **centre** of the
  Button's bounds (a tap just above the bounds missed).
- Some events have an envelope icon (created from an email). **Calendar only:
  don't open the linked email.** Tapping the event block opens the event details,
  which is fine.

### 2.5 Event details screen

| Field | Selector | Example |
|---|---|---|
| Close (back) | `ImageButton content-desc="Close"` | bounds `[0,121][147,268]` → `(73,194)` |
| Edit | `id=action_edit` | (don't touch) |
| **Title** | `id=event_details_title` | `Zumba` |
| Calendar | `id=event_details_calendar` | `Calendar (eiderwhi@lshtm.ac.uk)` |
| **Date** | `id=event_details_start_date` | `Sunday, 4 October 2026` (includes year, so use it to verify the target date) |
| **Time (same-day event)** | `id=event_details_end_date` | text `14:00 ▸ 14:55 (55 minutes)`; desc `14:00 to 14:55, duration: 55 minutes` |
| Time (multi-day event) | `id=event_details_start_time` / `id=event_details_end_date` + `id=event_details_end_time` | `22:00` … `Sunday, 4 October 2026` `6:00` |
| **Location** | `id=event_details_location_name` | absent when no location |
| Alert | `id=meeting_selected_alert_text` | `None` |
| **Category row** | `id=row_event_detail_category` | container |
| Category (none set) | `id=meeting_selected_category_title`=`Categorise`, `id=meeting_selected_category_text`=`None` | |
| **Category (set)** | **TextView with NO resource-id** inside `row_event_detail_category` | `Immoveable` |

⚠ Key parsing rule: read **all TextView texts under `row_event_detail_category`**.
If the list is `['Categorise','None']` there is no label; otherwise each text is a
category name.

⚠ The time field's resource-id is misleading: for a same-day event the start
time is in `event_details_end_date` as `HH:MM ▸ HH:MM (…)`. Parse the content-desc
`HH:MM to HH:MM` for robustness.

### 2.6 Categories observed

| Event | Date | Start | Category |
|---|---|---|---|
| Zumba | Sun 4 Oct | 14:00 | **Immoveable** |
| Kristian pdr | Mon 5 Oct | 17:35 | **Moveable** |
| Phone cat | Sun 4 Oct | 16:00 | Urgent |
| Invitation to contribute to a Liber Amicorum… | Sun 4 Oct | 15:35 | BCC BCC BCC |
| alvaro, Re: Sorry to ask…, wvc Confirm…, think bra modelling…, need to book meeting rooms…, plan until after union, T no comp | Sun 4 Oct | — | none |

Confirmed spellings: **`Moveable`** and **`Immoveable`**. Matching must be **exact**
on the category name, because other categories exist (`Urgent`, `BCC BCC BCC`, …).
Compare case-insensitively as a safety margin. Match if *any* of an event's
categories is a target label.

Location can be a long **Zoom URL** (`https://lshtm.zoom.us/j/859…&from=addon`) or
`URL; Room` (e.g. `https://…; KS-121`). See the alarm-label rule in §3.3.

---

## 3. Alarms

### 3.1 Why not the Google Clock app

The Clock app's `AlarmClock.ACTION_SET_ALARM` intent accepts only `HOUR`, `MINUTES`,
`MESSAGE`, `DAYS` (weekly repeat), `SKIP_UI`, etc. There is **no date**. The alarm
rings at the *next* occurrence of HH:MM, so a "tomorrow 17:35" alarm set at 15:00 today
would ring **today** at 17:35. A weekly-repeat workaround would ring again every week.
Decision: the app owns its alarms.

### 3.2 App-owned alarms

- **Scheduling:** `AlarmManager.setAlarmClock(AlarmClockInfo(triggerAtMillis,
  showIntent), firePendingIntent)`. This fires at an exact time even in Doze. It appears as
  the system "next alarm" (status-bar alarm icon, lock screen, Quick Settings).
  One `PendingIntent` per alarm with a unique `requestCode` (the alarm's DB id).
- **Ringing:** `AlarmReceiver` → starts `AlarmRingService` (foreground service, type
  `systemExempted`, which is allowed for apps holding the exact-alarm permission;
  fallback `mediaPlayback`). The service:
  - plays the default alarm sound (`RingtoneManager.TYPE_ALARM`, `AudioAttributes.USAGE_ALARM`)
    so it obeys the alarm volume and sounds even in silent/DND-for-alarms mode;
  - vibrates; holds a wake lock;
  - posts a high-priority notification with a **full-screen intent** →
    `AlarmRingActivity` (`showWhenLocked`, `turnScreenOn`) showing
    `Title (Label) @ Location`, the event start time, and **Dismiss** / **Snooze**.
    When the phone is in use, it shows as a heads-up notification with the same buttons.
  - Defaults: **snooze 5 min**; **auto-silence after 10 min** (then a missed-alarm notification).
- **Persistence:** every alarm is stored in `AlarmStore` (Room or DataStore).
  `AlarmManager` alarms are wiped by reboot and force-stop, so `RescheduleReceiver`
  re-registers all future alarms on `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`,
  `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED`.
- **Visibility:** because these alarms aren't in the Clock app, the home screen shows
  an **Upcoming alarms** list (time, label) with a **Cancel** action per alarm. Fired or
  past alarms are pruned automatically, and nothing else is ever deleted.

### 3.3 Alarm label (decided): `Title (Label) @ Location`

e.g. `Zumba (Immoveable) @ Pancras Square Leisure, 5 Pancras Square…`
- No location → `Title (Label)`.
- Location contains a Zoom/Teams/Meet URL → replace the URL with `Zoom` / `Teams` /
  `Meet`, keeping any room after `; ` → `Kristian pdr (Moveable) @ Zoom`,
  `Chat about funding (Moveable) @ Zoom; KS-121`.
- Truncate to ~60 chars with `…` for notification/list display. The ring screen
  can show the full text.

---

## 4. Architecture: on-phone Android app

Kotlin + Jetpack Compose, `minSdk 33`, `targetSdk` = Android 17 platform in the SDK.
Built in Android Studio on this PC and sideloaded with `adb install`.

### 4.1 Components

| Component | Responsibility |
|---|---|
| `MainActivity` (Compose) | Home: **Set alarms for today** / **Set alarms for tomorrow** buttons + Upcoming alarms list. Review screen after a scan. Setup checklist if permissions are missing |
| `OutlookReaderService` (`AccessibilityService`) | Drives Outlook and reads nodes by `viewIdResourceName` / `contentDescription` (prefix `com.microsoft.office.outlook:id/`) |
| `OutlookNavigator` | State machine: launch → Calendar tab → ensure Day view → **go to target date (§2.3)** → scroll & enumerate → open each event → read → Close |
| `OutlookSelectors` | All IDs/descs from §2 in one place |
| `EventParser` | Pure Kotlin: content-desc → (date, start); details-screen texts → title/location/categories. Unit-tested against `./reference` XML fixtures |
| `AlarmScheduler` | Builds labels (§3.3); `setAlarmClock` / cancel; computes trigger = event date + start − (5 min if chosen) |
| `AlarmReceiver`, `AlarmRingService`, `AlarmRingActivity` | Ringing, snooze, dismiss (§3.2) |
| `RescheduleReceiver` | Re-register alarms after reboot / update / time change |
| `AlarmStore` | Alarms the app has scheduled: `id, eventDate, eventStart, title, label, location, offsetMin, triggerAt, state`. Used for de-dup, reschedule and the Upcoming list |

No background scanning. The user opens the app and taps a button.

### 4.2 Accessibility service config

```xml
<accessibility-service
    android:packageNames="com.microsoft.office.outlook"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"
    android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows"
    android:canRetrieveWindowContent="true"
    android:canPerformGestures="true"
    android:notificationTimeout="100" />
```

- `flagReportViewIds` is essential: without it `viewIdResourceName` is null.
- `packageNames` limits the service to Outlook, so it never sees other apps.
- Clicks: `node.performAction(ACTION_CLICK)`; fallback `dispatchGesture` tap at the
  live bounds' centre.
- Week-strip swipe and Day-grid fling: `dispatchGesture` using the live bounds of the
  strip buttons / grid (fling duration ≈ 120 ms; 300 ms did nothing on the grid).
- Vertical scrolling: `ACTION_SCROLL_FORWARD` on the scrollable ancestor of the Day grid
  (fallback: swipe gesture).
- Waiting: poll `rootInActiveWindow` for the expected node (e.g.
  `event_details_title`) with a timeout, instead of fixed sleeps.

### 4.3 Flow

1. User opens the app (phone unlocked). If any setup item is missing (§4.4), show the
   checklist with buttons to the relevant settings pages.
2. User taps **Set alarms for today** or **Set alarms for tomorrow** → `target` date.
3. App launches Outlook via `getLaunchIntentForPackage` (declare `<queries><package
   android:name="com.microsoft.office.outlook"/></queries>`) and shows a small
   "Reading Outlook…" overlay/notification.
4. Navigator: click `desc=Calendar` → ensure Day view → navigate to `target` (§2.3).
5. Enumerate event Buttons whose desc starts with `"<target label>, HH:MM to "`,
   scrolling until no new ones appear. This excludes `Work location:` chips,
   **all-day events** and events that started the day before.
   For **today** only: **skip events whose start time has already passed.** If only the
   5-min-before time has passed, list the event but disable "5 min before".
6. For each candidate: click → wait for `event_details_title` → verify
   `event_details_start_date` equals `target` → read title / time /
   `event_details_location_name` / texts under `row_event_detail_category` → click
   `desc=Close` → re-find the Day view (re-navigate if the date changed).
7. Keep events whose categories include `Moveable` or `Immoveable`.
8. Bring `MainActivity` back with the review screen:

   ```
   ┌──────────────────────────────────────────────┐
   │ Tomorrow · Monday 5 October                  │
   │ 17:35  Kristian pdr          [Moveable]      │
   │        @ Zoom                                │
   │        [✓] Set alarm   [ ] 5 min before      │
   │ 09:00  Example event         [Immoveable]    │
   │        ✓ Alarm already set (09:00)           │
   │ ...                                          │
   │          [ Cancel ]     [ Set 3 alarms ]     │
   └──────────────────────────────────────────────┘
   ```
   Defaults: **Set alarm = on**, **5 min before = off**.
   Events already in `AlarmStore` (same date + start + title) are shown as
   "✓ Alarm already set" with no checkboxes.
9. User confirms → `AlarmScheduler` schedules one alarm per ticked event and saves it.
10. Summary (set / already set / skipped / failed) → back to Home with the updated
    Upcoming alarms list.

Home screen:

```
┌──────────────────────────────────────────────┐
│ Calendar Event Timers                        │
│  [ Set alarms for today ]                    │
│  [ Set alarms for tomorrow ]                 │
│                                              │
│ Upcoming alarms                              │
│  Sun 4 Oct 14:00  Zumba (Immoveable) @ …  ✕  │
│  Mon 5 Oct 17:35  Kristian pdr (Moveable)…✕  │
└──────────────────────────────────────────────┘
```

### 4.4 Permissions & one-off setup on the phone

| Item | How it's granted |
|---|---|
| Accessibility service | Settings → Apps → *Calendar Event Timers* → ⋮ → **Allow restricted settings** (Android 13+ blocks this for sideloaded apps), then Settings → Accessibility → enable |
| `USE_EXACT_ALARM` | Granted at install (Android 13+). Required by `setAlarmClock` |
| `POST_NOTIFICATIONS` | Runtime prompt on first launch (ringing notification) |
| `USE_FULL_SCREEN_INTENT` | Android 14+: check `NotificationManager.canUseFullScreenIntent()`; if false, open `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SYSTEM_EXEMPTED` | Install-time |
| `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`, `VIBRATE` | Install-time |

Install: `adb install -r app-debug.apk`.

### 4.5 Testing

- `EventParser` unit tests use the XML dumps in `./reference` as fixtures
  (moveable, immoveable, no category, multi-day, today/tomorrow Day views).
- `AlarmScheduler` unit tests: trigger-time maths, including the 5-min offset, events
  just after midnight, and BST/GMT changeover (25 Oct 2026 in `Europe/London`).
- On-device tests on the Pixel 8a:
  - today and tomorrow scans against the real calendar;
  - a test alarm 2 minutes ahead while the phone is **locked**, **unlocked and in use**,
    and in **DND**;
  - snooze / dismiss / auto-silence;
  - reboot with a pending alarm → still rings;
  - run the same day twice → no duplicates.
- A "dry run" toggle that lists events but schedules nothing.

---

## 5. Risks

- **Outlook UI updates** may rename IDs. All selectors live in `OutlookSelectors`; the
  XML fixtures catch regressions. The app fails loudly ("Couldn't find Day view, Outlook
  may have changed") rather than silently setting nothing.
- **Wrong day read**: Outlook remembers the last day viewed. Mitigated by explicit
  navigation and by checking `event_details_start_date` against the target.
- **Alarm reliability is now the app's job**: reboot, force-stop, app update, and
  battery savers. Mitigated by `setAlarmClock` (Doze-exempt), `RescheduleReceiver`, and
  the on-device tests above. A **force-stop** clears alarms until the app is next opened,
  so the app should re-sync from `AlarmStore` on every launch.
- **Full-screen permission** may be revoked by the OS. Checked at every launch.
- **Duplicate alarms**: mitigated by `AlarmStore`. If an event's time or title changes in
  Outlook after its alarm was set, it counts as a new event, and the old alarm stays
  (visible and cancellable in Upcoming alarms).
- **Timing**: Outlook needs time to sync after launch. Wait for event nodes to appear.
- **Email-linked events** (envelope icon): only the event block is tapped, never the email.
- **Recurring events** appear individually in Day view, so no special handling.

---

## Decisions

| Topic | Decision |
|---|---|
| Form factor | On-phone Android app (AccessibilityService) |
| Buttons | **Set alarms for today** and **Set alarms for tomorrow** → scan → review → confirm |
| Alarm mechanism | **App-owned exact alarms** (`setAlarmClock`) with own ring screen. Not the Clock app |
| Calendar view | **Day view** (not Agenda) |
| Labels | `Moveable`, `Immoveable` (exact, case-insensitive) |
| Fields | title, location, label, start time |
| Defaults | set alarm = **yes**; 5 min before = **no** |
| Past events (today) | **Skipped** |
| Alarm label | `Title (Label) @ Location` (URLs shortened, ~60 chars in lists) |
| Data scope | Calendar only. Never open emails |
| Trigger | **Manual**: user opens the app |
| Re-runs | Events already given an alarm by the app are **skipped** |
| Old alarms | Left alone. Fired alarms simply drop off the Upcoming list |
| All-day events | **Ignored** |
| Snooze / auto-silence | 5 min / 10 min (defaults, easy to change) |
| Orientation | Always portrait. No landscape handling |

## Open questions

None at present.

---

## Reference files

`./reference/` contains screenshots and `uiautomator` XML dumps from exploration:
- `day_view.png` / `.xml`: Day view for today (Sun 4 Oct), portrait
- `day_view_tomorrow.xml`: Day view after navigating to tomorrow (Mon 5 Oct)
- `view_switcher_menu.png`: Agenda / Day / 3 Day / Month popup
- `event_details_immoveable.png` / `.xml`: event with `Immoveable` category (Zumba)
- `event_details_moveable.png` / `.xml`: event with `Moveable` category (Kristian pdr)
- `event_details_no_category.xml`: event with no category
- `event_details_multiday.xml`: overnight event (separate start/end time fields)
