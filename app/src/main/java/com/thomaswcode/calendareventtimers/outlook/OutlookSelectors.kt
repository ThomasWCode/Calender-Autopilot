package com.thomaswcode.calendareventtimers.outlook

/**
 * Every Outlook view id and description the app relies on (PLAN.md §2), in one place. When an
 * Outlook update renames something, this file and the XML fixtures in the tests are what change.
 */
object OutlookSelectors {
    const val PACKAGE = "com.microsoft.office.outlook"
    private const val ID = "$PACKAGE:id/"

    // Bottom navigation
    const val NAV_LABEL = ID + "label"
    const val DESC_CALENDAR_TAB = "Calendar"

    // Calendar header and week strip
    const val VIEW_SWITCHER = ID + "menu_calendar_views"
    /** The switcher's description names the current view: "Switch away from Day view". */
    const val DAY_VIEW_MARKER = "from Day view"
    const val VIEW_MENU_ITEM_TITLE = ID + "title"
    const val VIEW_MENU_DAY = "Day"
    const val WEEK_STRIP_CONTAINER = ID + "calendar_view"
    const val STRIP_FLAG_TODAY = ", today"
    const val STRIP_FLAG_SELECTED = ", Selected"

    // Day view
    const val DAY_VIEW_CONTAINER = ID + "multiday_view"
    const val CALENDAR_VIEWS_CONTAINER = ID + "two_mode_calendar_view"
    const val ALL_DAY_CONTAINER = ID + "multi_day_view_all_day_view_container"

    // Event details
    const val DETAILS_ROOT = ID + "event_details_root"
    const val DESC_CLOSE = "Close"
    const val DETAILS_SCROLLVIEW = ID + "event_details_scrollview"
    const val DETAILS_TITLE = ID + "event_details_title"
    const val DETAILS_START_DATE = ID + "event_details_start_date"
    /** Only on multi-day events; same-day events put "HH:MM ▸ HH:MM (…)" in [DETAILS_END_DATE]. */
    const val DETAILS_START_TIME = ID + "event_details_start_time"
    const val DETAILS_END_DATE = ID + "event_details_end_date"
    const val DETAILS_LOCATION_NAME = ID + "event_details_location_name"
    const val DETAILS_LOCATION_CAPTION = ID + "event_details_location_caption"
    /** A room's reply under its name: "Reserved". */
    const val DETAILS_LOCATION_RESPONSE = ID + "event_details_location_response"
    const val CATEGORY_ROW = ID + "row_event_detail_category"
    const val DETAILS_EDIT = ID + "action_edit"

    // ---- Room booking (PLAN-ROOM-BOOKING.md §2.3–2.7, reference/room_booking) ----

    /** The Day view's floating button, next to Copilot. */
    const val DESC_NEW_EVENT = "New event"

    // The event form is Jetpack Compose: no view ids, so rows are found by their texts (UK English).
    const val FORM_NEW_TITLE = "New Event"
    const val FORM_EDIT_TITLE = "Edit Event"
    const val DESC_SAVE = "Save"
    /** Possibly the button's description once there are invitees (to confirm on the phone). */
    const val DESC_SEND = "Send"
    const val DESC_CANCEL = "Cancel"
    const val TEXT_TITLE = "Title"
    const val TEXT_PEOPLE = "People"
    const val TEXT_ALL_DAY = "All Day Event"
    const val TEXT_DATE = "Date"
    /** "Time (GMT+1)" */
    const val TEXT_TIME_PREFIX = "Time"
    const val TEXT_TIME_ZONE = "Time zone"
    const val TEXT_LOCATION = "Location"
    const val TEXT_ONLINE_MEETING = "Online Meeting"
    const val TEXT_DESCRIPTION = "Description"
    const val TEXT_ATTACHMENTS = "Attachments"
    const val TEXT_ALERT = "Alert"
    const val TEXT_DELETE_EVENT = "Delete event"
    const val TEXT_DISCARD_PROMPT = "Discard event?"
    const val TEXT_DISCARD = "Discard"

    // Time picker
    const val PICKER_MODE = ID + "action_picker_mode"
    const val ACTION_DONE = ID + "action_done"
    /** Only in the wheel ("Choose Time") mode. */
    const val DATE_TIME_PICKER = ID + "date_time_picker"
    const val START_END_TABS = ID + "start_end_time_tab"
    const val NUMBER_PICKER_INPUT = ID + "numberpicker_input"
    const val TEXT_START_TIME = "Start time"
    const val TEXT_END_TIME = "End time"
    const val PICKER_ROOT = ID + "root_calendar_views_container"

    // Add Location
    const val LOCATION_ROOT = ID + "location_picker_root"
    const val LOCATION_INPUT = ID + "text_input"
    const val ROOM_FINDER = ID + "room_finder"
    const val LOCATION_RESULTS = ID + "location_picker_results"
    const val RESULT_NAME = ID + "row_location_picker_result_name"
    const val RESULT_DETAIL = ID + "row_location_picker_result_detail"
    const val LOCATION_CHIP_TEXT = ID + "location_chip_text"
    const val LOCATION_CLEAR = ID + "multiple_location_picker_clear_symbol"

    // Room Finder
    const val BUILDING_SEARCH = ID + "search_edit_text"
    const val BUILDING_LIST = ID + "building_list"
    const val BUILDING_NAME = ID + "text_room_name"
    const val ROOM_LIST = ID + "room_list"

    // Add People
    const val PEOPLE_ROOT = ID + "add_people_root"
    const val PEOPLE_REQUIRED_TAB = ID + "add_people_required_tab"
    const val PEOPLE_INPUT = ID + "text_input"
    const val CONTACT_CHIP = ID + "contact_chip"
    const val CONTACT_CHIP_TEXT = ID + "contact_chip_text"

    // Description
    const val DESCRIPTION_FIELD = ID + "rich_edit_field"
    const val DESCRIPTION_EDITOR = ID + "rich_edit_text"

    // Alert sheet (Compose, no ids)
    const val TEXT_ALERT_NONE = "None"
    const val TEXT_ALERT_AT_TIME = "At time of event"

    // Platform alert dialogs (the delete prompt)
    const val DIALOG_MESSAGE = "android:id/message"
    const val DIALOG_POSITIVE = "android:id/button1"
    const val DIALOG_NEGATIVE = "android:id/button2"
    const val TEXT_DELETE = "Delete"
}
