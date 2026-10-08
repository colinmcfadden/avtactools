# Android phone · Finished

- Platform: Android phone, 412 x 915 dp
- Screenshot: `../png/AP-Finished.png`  ·  Source: `../screens/AP-Finished.dc.html`
- Web screen it adapts: 16 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    [button "Search a grid or lat/long": search ]
      MGRS target
    [text "16S GC 28864 55349"] [button "Account, Colin McFadden": CM ]
    [button "Workspace: Mission Pack OP DK, finished and read-only. Change workspace": groups OP DK · Finished arrow_drop_down ] [button: file_open Import ] [button: collections_bookmark Library ]
    {"LZ/PZ panel"}
          LZ/PZ
          lock OP DK · finished · read-only
        {"People in this pack, read-only for everyone"}
          CM
          SB
          JR
          +1
      [button "Collapse panel": expand_more ]
        {role=status}
          lock
            OP DK was finished by Colin McFadden on Oct 5 at 14:20. It is read-only for everyone.
          [button "Save a copy of OP DK to your Library": library_add Save a copy to Library ]
          [button: Duplicate as new pack ] [button: lock_open Reopen pack ]
          You own OP DK, so only you can reopen it.
      groups In OP DK 3
            LZ IBIS (OWEST)
          [button "Rename LZ IBIS (OWEST), not available in a finished pack" disabled: edit ] [button "More actions for LZ IBIS (OWEST)": more_vert ]
          16S GC 26318 55907
          check_circle Analyzed lock Read-only
          [button "Open LZ IBIS (OWEST) in 3D": view_in_ar 3D ] [button "Save a copy of LZ IBIS (OWEST) to your Library": library_add Save a copy ]
          Finished by Colin M. on Oct 5 at 14:20
      flight_land
          LZ HAWK (ROCK) collections_bookmark From Library
          16S GC 31204 58811 · Analyzed
      [button "Open LZ HAWK (ROCK) in 3D": view_in_ar ]
      flight_land
          LZ BLUEBIRD (TWMILE)
          16S GC 28110 52476 · Analyzed
      [button "Open LZ BLUEBIRD (TWMILE) in 3D": view_in_ar ]
    {"Panels"}
    [button: groups Pack ] [button current: flight_land LZ/PZ ] [button: route Routes ] [button "Threats, 2 on the map": crisis_alert 2 Threats ] [button "Imports, 5 items": move_to_inbox 5 Imports ]
```
