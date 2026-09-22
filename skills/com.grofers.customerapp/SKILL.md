# Blinkit

## Structure

- A category and search driven grocery list scoped to a delivery address. Items have a
  plus control that adds to cart directly from the listing.

## Traps

- The plus control adds immediately with no confirmation, and repeated taps increase
  quantity. Tap once and verify the count.
- Stock and price depend on the delivery address shown at the top.
- The cart applies delivery and handling fees that are only visible at checkout.

## Safety

- Checkout spends real money -- a financial action. Report the total and stop.
- Do not change the delivery address; it decides where physical goods are sent.
