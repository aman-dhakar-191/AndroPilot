# File Manager (Xiaomi)

## Structure

- Category tiles (Documents, Images, Video, ...) over a browsable tree. The path is shown
  at the top; read it before acting so you know where you are.
- Long-press selects and reveals bulk actions; a single tap opens.

## Traps

- Long-press and tap do different things on the same row. `long_press` to select,
  `click` to open -- choose by intent.
- Sorting and view mode change the order of rows, so an index-based selector from an
  earlier snapshot can point at a different file. Re-resolve by name.

## Safety

- Delete, move and rename are destructive and there is usually no undo. Do not do them
  unless the goal names the file and the operation.
