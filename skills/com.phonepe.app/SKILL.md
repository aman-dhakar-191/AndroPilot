# PhonePe (UPI)

Money. The same rule as any payments app applies, without exception.

## The rule

Transfers, bill payments, recharges, and autopay setups are financial actions. The device's
safety policy will ask a human. Report that and stop. Never enter a UPI PIN -- that keypad
belongs to the person holding the phone.

## Structure

- A dense home screen of payment categories and recent contacts, with tabs along the
  bottom. Categories (recharge, bills, insurance) each open their own flow.
- Flows end in an amount screen, a confirmation, and then the PIN screen.

## Traps

- The home screen is full of promotional tiles that look like actions. Read what a tile
  says before tapping; several start a payment flow directly.
- Recharge and bill flows pre-fill an amount from history. Pre-filled is not confirmed --
  report the number rather than accepting it.
- Similar contact names and truncated ids make the wrong recipient easy and irreversible.

## What is safe

- Transaction history and payment status are read-only. Prefer them.
