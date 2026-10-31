# Android tablet · LZ/PZ

- Platform: Android tablet, 1280 x 800 dp, landscape
- Screenshot: `../png/AT-Home.png`  ·  Source: `../screens/AT-Home.dc.html`
- Web screen it adapts: 01 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    {"Navigation rail"}
    [button "Workspace: Personal. Change workspace": person Personal arrow_drop_down ] [button: file_open Import ]
      [button "LZ/PZ, unsaved changes" current: flight_land LZ/PZ ] [button "Routes, unsaved changes": route Routes ] [button "Threats, 1 on the map": crisis_alert 1 Threats ] [button "Imports, 3 items": move_to_inbox 3 Imports ]
    [button: collections_bookmark Library ] [button "Account, Colin McFadden": CM ]
    {"LZ/PZ panel"}
          LZ/PZ
          3 open · this session
      [button "Collapse panel": left_panel_close ]
          Open
        3
            LZ/PZ 1
          [button "Rename LZ/PZ 1": edit ]
          16S GC 28864 55349
          check_circle Analyzed Unsaved changes
          [button: save Save ] [button: view_in_ar 3D ] [button "More actions for LZ/PZ 1": more_vert ]
          folder Saves to your Library Ctrl S
        [button: LZ HAWK (ROCK) 16S GC 31204 58811 check_circle Analyzed check Saved · Oct 3 ] [button "Open LZ HAWK (ROCK) in 3D": view_in_ar ]
        [button: LZ/PZ 3 16S GC 27390 54102 Target set lock Analyze to save ] [button "Open LZ/PZ 3 in 3D": view_in_ar ]
          Recent in Library
        [button: Browse all ]
        hexagon
            LZ HAWK (SHOPE)
            Updated Aug 25 · 17:34
        [button "Open LZ HAWK (SHOPE)": Open ]
        hexagon
            LZ RAZORBILL
            Updated Aug 25 · 17:27
        [button "Open LZ RAZORBILL": Open ]
        hexagon
            PZ OAK (KJZP)
            Updated Aug 25 · 17:02
        [button "Open PZ OAK (KJZP)": Open ]
        touch_app Set a new target in the search bar, or long-press the map, to open another LZ/PZ.
    {"Map"}
      [button "Search a grid or lat/long": search ]
        MGRS target
      [text "16S GC 28864 55349"] [button "Go to this grid": arrow_forward ]
      [button "Map layers": layers ] [button "My location": my_location ]
      [button "Grid under the crosshair, 16S GC 28864 55349. Tap to copy": add 16S GC 28864 55349 ]
    {"LZ/PZ 1 tools"}
          LZ/PZ 1
          Aircraft, analysis and tools
      [button "Close the LZ/PZ 1 pane": close ]
      [button: Aircraft UH-60L — UH-60L Black Hawk arrow_drop_down ]
        76 m spacing · 100 kt [button "Manage aircraft profiles": Manage ]
        LZ/PZ analysis
        check_circle Analyzed. Planning graphics, export and save are unlocked.
      [button: refresh Re-analyze ]
            Slope map
          [button role=switch checked=false: ]
            LZ box
          [button role=switch checked=true: check ]
        LZ/PZ tools
        Places at the crosshair, or where you tap.
        [button: Draw LZ ] [button: Helo ] [button: PZ ] [button: Sector ] [button: Unit ] [button: L-GA ] [button: R-GA ]
        Export
        [button: Set capture area ] [button: Export LZ card ]
        Routes
      [button: route Sketch a route ]
```
