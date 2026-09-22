# WhatsApp

## Structure

- A chat list, each chat opening to messages with an input row at the bottom. The send
  control is an unlabelled icon at the end of that row -- identify it by role and position,
  not by text.
- Search at the top matches both contacts and message content.

## Traps

- Opening a chat marks it read and the sender sees that. If the goal was only to check
  whether a message arrived, say that reading it changed state.
- Attachment, camera and voice controls sit in the same input row as send. The voice button
  starts recording on press and can send on release -- do not target it speculatively.
- Group chats and individual chats with similar names sit next to each other in the list.
- A message once sent can only be "deleted for everyone" within a time limit; treat sending
  as irreversible.

## Safety

- Composing is fine; sending speaks as the user. Stop for approval unless sending was the
  explicit goal.
- Never read out one-time codes or forward messages.
