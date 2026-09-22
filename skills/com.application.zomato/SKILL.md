# Zomato

## Structure

- Restaurant/dish search over a location-scoped list. A restaurant opens to a menu; items
  add to a cart; the cart leads to address, payment and place-order.

## Traps

- The delivery location at the top decides everything below it. Read it before trusting
  availability or prices.
- Item customisation sheets appear between "add" and the cart, and default to preselected
  options that cost money.
- Offers and membership prompts are injected into the ordering flow.

## Safety

- Placing an order spends real money and results in food arriving at somebody's door. It is
  a financial action -- report the cart total and stop for approval.
- Browsing menus and prices is read-only and is usually what is actually wanted.
