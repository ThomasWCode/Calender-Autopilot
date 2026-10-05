# Room booking: reference captures (2026-10-05)

Outlook 5.2638.1 on the Pixel 8a (1080 × 2400, portrait, dark theme), captured while planning
[PLAN-ROOM-BOOKING.md](../../PLAN-ROOM-BOOKING.md). `.xml` files are `uiautomator dump`s (only
on-screen nodes; the app's accessibility service sees more), `.png` files are screenshots.

Nothing was saved except one test event with no invitees and no room, which was deleted again.
The drafts used for these captures were discarded. `nobody@example.com` is a dummy address typed
into an unsaved draft.

| File | Screen | What matters |
|---|---|---|
| `new_event_form.xml/.png` | Form after the Day view's **New event** button (desc `New event`) | Jetpack Compose, **no view ids**. Title `EditText` (placeholder child text `Title`), rows found by their texts: `People`, `All Day Event`, `Date`/`Mon 12 Oct`, `Time (GMT+1)`/`08:05 ▸ 09:00`, `Time zone`, `Location`, `Online Meeting` (off), `Description`, `Attachments`, `Repeat`, `Alert`/`15 minutes before`, `Show as`/`Busy`, `Private`. Top bar: desc `Cancel`, desc `Save`, account desc `Richard White, richard.white@lshtm.ac.uk`. Date defaults to the day selected in the calendar; time to 08:05–09:00 here |
| `new_event_form_with_room.xml` | Same form after choosing a room | The Location row's text becomes `KS-103D`; People stays empty (the room is added as a resource when saved) |
| `time_picker_drag_mode.png` | Time row → picker, as it opens | Drag editor on a day grid: not used |
| `time_picker_wheels_start.png`, `time_picker_wheels_end.png` | After `#action_picker_mode`: **Choose Time** | `Start time` / `End time` tabs; date, hour and minute wheels |
| `time_picker_service_dumps.txt` | Both picker modes as the service sees them | uiautomator can't see this screen. `#action_picker_mode`, `#action_done`, `#date_time_picker`, `#start_end_time_tab`, three `NumberPicker`s with `#numberpicker_input` |
| `add_location_recent.xml/.png` | Location row → **Add Location** | `#text_input` (`Enter a place`), `#room_finder` (desc `Or browse with Room Finder`), `#book_workspace`, `#recent_locations_label` `Recent`, rows in `#location_picker_results`: `#row_location_picker_result_name` + `#row_location_picker_result_detail` (`Free`). At 09:05 the Recent list showed only free rooms |
| `room_finder_buildings.xml/.png` | **Room finder**: buildings | `#search_edit_text` (`Enter a building`), `#building_list`, `#list_label` (`Recent`, `All`), rows `#text_room_name`: `KS-Rooms`, `TP2-Rooms`, … |
| `room_finder_ks_rooms_all_free.xml/.png` | **KS-Rooms** at Mon 12 Oct 08:05–09:00 | `#room_list` (RecyclerView, scrollable); rows: `#row_location_picker_result_name` `KS-103D`, detail `Free` |
| `room_finder_ks_rooms_some_busy.xml/.png` | KS-Rooms at 09:05–10:00 | Detail `Busy` for KS-105c, KS-106, KS-121, KS-123 |
| `room_finder_ks_rooms_page2.xml`, `…_page3.xml`, `…_end.xml` | The rest of the list after scrolling | Alphabetical, about 40 rooms, ends at `KS-LG05`. Includes rooms not in the user's list (`KS-105c (EPH only)`, `KS-162A (LAORS Training only)`, `KS-G04`, `KS-G47 (Exec Office)`, …) |
| `add_location_room_chip.xml/.png` | Back on Add Location after tapping a room | Chip `#location_chip_text` `KS-103D`, `#multiple_location_picker_clear_symbol` (desc `Clear location`), `#action_done` (desc `Done`) |
| `add_people_typed.xml/.png` | People row → **Add People**, address typed | `#add_people_required_tab` / `#add_people_optional_tab`, `AutoCompleteTextView #text_input` (`Type a name or an email address`), `Search Directory` suggestion. Enter does nothing |
| `add_people_chip.xml/.png` | After typing a comma | The address becomes a chip: `#contact_chip_text`, row desc `<nobody@example.com>`; `#text_input` is empty again |
| `description_editor.xml/.png` | Description row | Dialog: `#rich_edit_field` with a **WebView** `#rich_edit_text` (rich text), formatting toolbar, `#action_done` (desc `Done button`) |
| `alert_sheet.xml/.png` | Alert row | Bottom sheet: `None`, `At time of event`, `5 / 10 / 15 / 30 minutes before`, `1 hour before`, … |
| `discard_prompt.xml` | Cancel on a changed form | `Discard event?` with `Discard` / `Cancel`. An unchanged form closes without asking |
| `booking_details_reserved.xml/.png` | Details of the user's own booking `call` at KS-121 | `#event_details_location_name` `KS-121`, `#event_details_location_response` **`Reserved`**, caption desc `at location KS-121, Reserved`; buttons `Email KS-121`, `Forward Invitation` |
| `edit_event_form_bottom.xml/.png` | Details → Edit (`#action_edit`), scrolled down | Same Compose form titled `Edit Event`; **`Delete event`** at the bottom |
| `delete_prompt.xml` | Delete event, for an event with no invitees | `Delete the event?` with `#button2` `Cancel` / `#button1` `Delete` |
| `calendar_provider.md` | Android's calendar provider | What Outlook syncs: events, attendees (with emails, rooms as resources and their replies), HTML descriptions, the change key, sync timing |

Not captured yet (they need a real booking with a room, so a meeting request; see the plan's
open items): the Save/Send step and any prompt when a room or people are on the event, the
room's reply arriving, and the delete prompt for an event with attendees.
