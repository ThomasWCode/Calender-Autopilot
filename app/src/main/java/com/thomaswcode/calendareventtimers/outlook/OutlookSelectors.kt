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
    const val CATEGORY_ROW = ID + "row_event_detail_category"
}
