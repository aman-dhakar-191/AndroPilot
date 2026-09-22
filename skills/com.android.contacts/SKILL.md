# Contacts

Looking up people, and the source of truth for who a message is going to.

## Structure

- An alphabetical list with search at the top. A contact's detail page shows numbers,
  email, and actions (call, message).
- Search matches name and number, and matches partial strings.

## Traps

- Duplicate contacts with the same name are common, often from different accounts. Two
  matches is `ambiguous_target`, not a coin flip -- report both with their numbers.
- A contact can have several numbers (mobile, work, home). Picking the wrong one sends the
  message somewhere else. Read the label on the number.

## Safety

- Reading is safe. Editing, merging, and deleting are not -- do none of them unless the
  goal says so explicitly.
- Never place a call as a side effect of "finding" someone. Call actions sit right next to
  the number on the detail page.
