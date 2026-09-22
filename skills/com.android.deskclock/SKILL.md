# Clock

Alarms, timers, stopwatch, world clock.

## Structure

- Tabs for Alarm, Clock, Timer, Stopwatch. Alarms are a list of rows, each with a time, a
  repeat description, and a switch on the right.
- Adding an alarm opens a time picker. Pickers are the least semantic part of Android:
  observe carefully, and prefer typing into a keypad input if the picker offers one.

## Traps

- The switch on an alarm row toggles it; tapping the row body opens it for editing. These
  are different targets with different consequences -- pick deliberately.
- A new alarm is not saved until the picker is confirmed. Verify the row exists afterwards.
- AM/PM is a separate control from the hour. Check both before confirming.

## Safety

- Do not delete or disable existing alarms while adding one. If a conflicting alarm exists,
  report it rather than changing it.
