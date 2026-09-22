# Google Photos

## Structure

- Photos / Search / Library tabs over a date-grouped grid. An item opens to a viewer with
  share, edit, info and delete.

## Traps

- The grid is virtualised; ids do not survive scrolling, so re-observe.
- Items live in the cloud as well as on the device, and deleting can remove both.
- Long-press starts multi-select, which changes what every subsequent tap does. Check
  whether selection mode is active before tapping anything.

## Safety

- Never share or delete. Sharing creates a link that leaves the device.
