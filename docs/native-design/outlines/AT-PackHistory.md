# Android tablet · Pack history

- Platform: Android tablet, 1280 x 800 dp, landscape
- Screenshot: `../png/AT-PackHistory.png`  ·  Source: `../screens/AT-PackHistory.dc.html`
- Web screen it adapts: 14 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    {"Navigation rail"}
    [button "Workspace: Mission Pack OP DK. Change workspace": groups OP DK arrow_drop_down ] [button: file_open Import ]
      [button "Pack" current: folder_shared Pack ] [button "LZ/PZ": flight_land LZ/PZ ] [button "Routes": route Routes ] [button "Threats, 2 on the map": crisis_alert 2 Threats ] [button "Imports, 5 items": move_to_inbox 5 Imports ]
    [button: collections_bookmark Library ] [button "Account, Colin McFadden": CM ]
    {"Pack panel, history"}
            OP DK
          Active
          Mission Pack · you are the owner
      [button "Collapse panel": left_panel_close ]
        {"Here now in OP DK"}
          CM Colin McFadden, you
          SB Sam B.
          JR Jess R.
          +1 and 1 more here now
      Live · all changes synced
        [button: Items ] [button: check History ]
        [button "Show changes by: Everyone": group Everyone arrow_drop_down ] [button "Show changes to: All items": filter_list All items arrow_drop_down ]
        New since you looked · 3
      {"New since you looked"}
        SB
            Sam B. moved Chalk 2 on LZ IBIS (OWEST)
            13:42 · LZ IBIS (OWEST)
        SB
            Sam B. added route RED 1 (copied from Library)
            13:38 · OP DK INGRESS
        JR
            Jess R. changed the landing heading to 270° on LZ HAWK (ROCK)
            13:21 · LZ HAWK (ROCK)
        Earlier today
      {"Earlier today"}
        CM
            Colin M. updated LZ HAWK (ROCK) from Library
            11:05 · LZ HAWK (ROCK)
        sync_problem
            Edit skipped: sector S-1 on LZ IBIS (OWEST) was already deleted. Jess R. ’s change was not applied.
            10:48 · LZ IBIS (OWEST)
        MW
            Marcus W. joined as Viewer
            10:30 · Members
        CM
            Colin M. invited marcus.webb@army.mil as Viewer
            10:12 · Members
        CM
            Colin M. imported NORTH GA POINTS (1,234 points)
            09:40 · NORTH GA POINTS
    {"Map"}
      [button "Search a grid or lat/long": search ]
        MGRS target
      [text "16S GC 28864 55349"] [button "Go to this grid": arrow_forward ]
      [button "Map layers": layers ] [button "My location": my_location ]
      [button "Grid under the crosshair, 16S GC 28864 55349. Tap to copy": add 16S GC 28864 55349 ]
    {"LZ IBIS (OWEST) tools"}
          LZ IBIS (OWEST)
          cloud_done Synced to OP DK
      [button "Close the LZ IBIS (OWEST) pane": close ]
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
