# Questions for the user

Raised while building room booking on 2026-10-05 without the phone and without the user live
("do not ask questions, defer them"). **All answered on 2026-10-07**, before the phone checks
([PHONE-CHECKS.md](PHONE-CHECKS.md)); each answer says what the code does.

## Room booking

**Q1. An event whose location names a room, but with no room among its invitees** (e.g. a
colleague's meeting with "KS-103D" typed as the location). Hidden as "already has a room", or
offered? **Hidden** (answered 2026-10-07), like every event that already has a room, and logged.
`RoomCover.cover`, the `LocationNamesRoom` case.

**Q2. Deleting a booking in Manage bookings**: should later runs ask about that event again?
**Yes** (answered 2026-10-06). Deleting is temporary: nothing about it is remembered, and the event
is offered again, like any event without a room. `ManageController.apply`.

**Q3. Other Outlook calendars** (e.g. the "TB Modelling Group" group calendar). **Main calendar
only** (answered 2026-10-07): only the LSHTM account's "Calendar" (with another account in Outlook,
its "Calendar" is never read; two LSHTM accounts are refused as unclear). Every Day view visited is
compared with the provider and differences are logged (PHONE-CHECKS A5).

**Q4. Events you declined**: offered for alarms or rooms? **No**, for both (answered 2026-10-07).

**Q5. The booking event's "Show as"**: **Busy**, Outlook's default (answered 2026-10-07).

**Q6. People told about a booking**: **Required** invitees (answered 2026-10-07).

**Q7. People who declined the original meeting**: **never offered** (answered 2026-10-07).

**Q8. Dry run with "Notify the others? Yes"**: the people's addresses are typed into the draft,
which is then discarded, so nothing is sent. **Fine** (answered 2026-10-07; on the phone, only
dummy `example.com` addresses are typed while invitees aren't allowed in tests).

**Q9. The clipboard**: the description is pasted through the clipboard (keeps links and formatting),
which is then cleared. **Paste, then clear** (answered 2026-10-07).

**Q10. Rooms whose Free/Busy hasn't shown after 4 seconds**: **treated as busy** (answered
2026-10-07): a room is only booked when Outlook said it was free.

**Q11. No room free**: **only reported** (answered 2026-10-07), not which other KS-Rooms were free.

**Q12. "This week"**: **today to Friday** (answered 2026-10-07), today's events not yet started.

**Q13. Real bookings for testing** (answered 2026-10-07): events may be created, with a room
booking, as long as they are deleted afterwards; **no invitees yet**. Test bookings go in quiet
slots (before 08:00 or after 18:00, the next day) in whichever room the app picks, and are deleted
within minutes.

## The app

**Q14. When the launcher shows**: **whenever the user opens the app** (answered 2026-10-07), not
only fresh or after 15 minutes away. When the app brings itself back after an Outlook run, it
returns to that run's screen (wizard, results, Manage bookings) instead. `MainActivity`.

**Q15. Your own addresses** (`eiderwhi@…`, `richard.white@…`): **never offered** for notifying
(answered 2026-10-07).

**Q16. Label cache lifetime**: **30 days** (answered 2026-10-07), while the event's change key is
the same.

**Q17. Keeping the screen upright**: the run's STOP strip asks Android for portrait, which works
like auto-rotate off for the length of the run, with nothing to switch back. **To be checked by
the user** (PHONE-CHECKS A10 needs the phone turned by hand); if the screen still turns, the
fallback is to switch Android's auto-rotate off during runs, which needs the *Modify system
settings* permission.

**Q18. People whose chip doesn't show an address** (answered 2026-10-07): **remove just the people
who couldn't be verified, book the room anyway, and say who wasn't added.** Before, the whole
booking failed. `BookingNavigator.typePeople`, the results' notes.
