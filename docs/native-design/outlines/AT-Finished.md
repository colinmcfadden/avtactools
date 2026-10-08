# Android tablet · Finished

- Platform: Android tablet, 1280 x 800 dp, landscape
- Screenshot: `../png/AT-Finished.png`  ·  Source: `../screens/AT-Finished.dc.html`
- Web screen it adapts: 16 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    {"Navigation rail"}
    [button "Workspace: Mission Pack OP DK, finished. Change workspace": groups lock OP DK arrow_drop_down ] [button: file_open Import ]
      [button "Pack": inventory_2 Pack ] [button "LZ/PZ, 3 in this pack" current: flight_land LZ/PZ ] [button "Routes, 2 in this pack": route Routes ] [button "Threats, 2 on the map": crisis_alert 2 Threats ] [button "Imports, 5 items": move_to_inbox 5 Imports ]
    [button: collections_bookmark Library ] [button "Account, Colin McFadden": CM ]
    {"Pack finished"}
    lock
      {role=status}
        OP DK was finished by Colin McFadden on Oct 5 at 14:20.
        It is read-only for everyone.
      [button: Reopen pack ] [button: content_copy Duplicate as new pack ] [button: library_add Save a copy to Library ]
    {"LZ/PZ panel"}
          LZ/PZ
          OP DK · finished · read-only
      [button "Collapse panel": left_panel_close ]
      [button "People in this pack: CM, SB, JR and 1 more. Read-only for everyone. Show members": CM SB JR +1 Read-only for everyone chevron_right ]
          In OP DK
        3
            LZ IBIS (OWEST)
          16S GC 26318 55907
          check_circle Analyzed lock Read-only
          [button: view_in_ar 3D ] [button: Save a copy ] [button "More actions for LZ IBIS (OWEST)": more_vert ]
          flag Finished by Colin M. on Oct 5 at 14:20
        [button: LZ HAWK (ROCK) 16S GC 31204 58811 check_circle Analyzed collections_bookmark From Library ] [button "Open LZ HAWK (ROCK) in 3D": view_in_ar ]
        [button: LZ BLUEBIRD (TWMILE) 16S GC 28110 52476 check_circle Analyzed ] [button "Open LZ BLUEBIRD (TWMILE) in 3D": view_in_ar ]
        info Nothing in OP DK can be changed. Save a copy to keep working on an LZ/PZ, or long-press the map to copy a grid.
    {"Map"}
      [button "Search a grid or lat/long": search ]
        MGRS target
      [text "16S GC 26318 55907"] [button "Go to this grid": arrow_forward ]
      [button "Map layers": layers ] [button "My location": my_location ]
      [button "Grid under the crosshair, 16S GC 26318 55907. Tap to copy": add 16S GC 26318 55907 ]
    {"LZ IBIS (OWEST) plan"}
          LZ IBIS (OWEST)
          Read-only · OP DK is finished
      [button "Close the LZ IBIS (OWEST) pane": close ]
        Aircraft UH-60L — UH-60L Black Hawk lock
        76 m spacing · 100 kt [button "Manage aircraft profiles": Manage ]
        LZ/PZ analysis
        check_circle Analyzed [button disabled: refresh Re-analyze ]
            Slope map
          [button role=switch checked=false: ]
            LZ box
          [button role=switch checked=true: check ]
          LZ/PZ tools
        lock Off in a finished pack
        [button disabled: Draw LZ ] [button disabled: Helo ] [button disabled: PZ ] [button disabled: Sector ] [button disabled: Unit ] [button disabled: L-GA ] [button disabled: R-GA ]
        Export
        [button: Set capture area ] [button: Export LZ card ]
        Routes
      [button disabled: route Sketch a route ]
```
