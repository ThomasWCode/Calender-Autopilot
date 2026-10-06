# Questions for the user (deferred)

Raised while building room booking on 2026-10-05 without the phone and without the user live
("do not ask questions, defer them"). Each has the **provisional answer the code uses now**, so
nothing waits on it; changing one is a small, local change (where noted). Phone checks are in
[PHONE-CHECKS.md](PHONE-CHECKS.md).

## Room booking

**Q1. An event whose location names a room, but with no room among its invitees** (e.g. a
colleague's meeting with "KS-103D" typed as the location). Hidden as "already has a room", or
offered?
*Now:* **hidden**, like every event that already has a room (your instruction), and logged.
`RoomCover.cover`, the `LocationNamesRoom` case.

**Q2. Deleting a booking in Manage bookings** — should later runs ask about that event again?
**Answered 2026-10-06: yes.** Deleting is temporary: nothing about it is remembered, and the event is
offered again, like any event without a room. `ManageController.apply`.

**Q3. Other Outlook calendars.** The engine reads only Outlook's main calendar ("Calendar
(eiderwhi@…)"). Events of other calendars Outlook may show in its Day view (e.g. the "TB Modelling
Group" group calendar) are no longer offered for alarms or rooms.
*Now:* **main calendar only**, and only the LSHTM account's (with another account in Outlook, its
"Calendar" is never read; two LSHTM accounts are refused as unclear); every Day view visited is
compared with the provider and differences are logged (PHONE-CHECKS A5). If some should count: a
calendar list in Settings.

**Q4. Events you declined** (your reply "declined") — offered for alarms or rooms?
*Now:* **no**, for both. Before, the timers took whatever the Day view showed.

**Q5. The booking event's "Show as"** — left at Outlook's default (**Busy**), or Free?
*Now:* **Busy** (no extra step). Free would take one more tap per booking.

**Q6. People told about a booking: Required or Optional invitees?**
*Now:* **Required** (Outlook's default tab, and what your manual bookings used).

**Q7. People who declined the original meeting** — offered for notifying?
*Now:* **no**, never offered.

**Q8. Dry run with "Notify the others? Yes"** — the people's addresses are typed into the draft,
which is then discarded, so nothing is sent. Fine, or should a dry run never type people in?
*Now:* typed in, then discarded (it tests that step too).

**Q9. The clipboard.** The description is pasted through the clipboard (keeps links and formatting),
which is cleared afterwards, so whatever you had copied before is gone.
*Now:* **paste, then clear**. Plain text without the clipboard is the fallback; it could be the
only way if you prefer.

**Q10. Rooms whose Free/Busy hasn't shown after 4 seconds** — skipped as busy, or waited for longer?
*Now:* **treated as busy** (a room is only booked when Outlook said it was free).

**Q11. No room free.** Reported at the end, as asked. Should the app also say which *other* rooms
in KS-Rooms were free (not on your list)?
*Now:* **no**, only the report.

**Q12. "This week"** covers today (events not yet started) to Friday. Right, or tomorrow to Friday?
*Now:* **today to Friday**.

**Q13. Real bookings for testing** — PHONE-CHECKS part D needs your go-ahead: one booking with a
room and nobody told, on a quiet slot, deleted straight after; then one telling a colleague who
agrees (who?). Until then only dry runs are done.

## The app

**Q14. When the launcher shows.** Opening the app fresh, and coming back after 15 minutes away,
shows Timers / Room booking; coming back sooner returns to where you were, and after an Outlook run
the app returns to that run's screen. Or should it always open on the launcher?
*Now:* as described. `MainActivity.onStart`, `AWAY_MS`.

**Q15. Your own addresses.** Never offered for notifying: the Outlook account's address
(`eiderwhi@…`) always, and `richard.white@…` once learnt from Outlook's form (or added in Settings).
Fine?

**Q16. Label cache lifetime.** A label read in Outlook is reused while the event's change key is
the same, for at most **30 days**. Shorter or longer?

**Q17. Keeping the screen upright** (asked for on 2026-10-06 as "turn off autorotate as it is
working"). *Now:* the run's STOP strip asks Android for portrait, which works like auto-rotate off
for the length of the run, with no permission and nothing to switch back (even after a crash); the
auto-rotate setting itself is untouched. If PHONE-CHECKS A10 shows the screen still turning, the
fallback is to switch Android's auto-rotate off during runs and back on after, which needs the
*Modify system settings* permission (one more setup step).

**Q18. People whose chip doesn't show an address.** A booking only tells people whose addresses
Outlook shows on their chips; otherwise it fails rather than risk inviting someone else.
*Now:* **fail** (PHONE-CHECKS B8 shows whether chips carry addresses on the phone).
