# Android tablet · Pack live

- Platform: Android tablet, 1280 x 800 dp, landscape
- Screenshot: `../png/AT-PackLive.png`  ·  Source: `../screens/AT-PackLive.dc.html`
- Web screen it adapts: 10 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    {"Navigation rail"}
    [button "Workspace: OP DK, Mission Pack. Change workspace": groups OP DK arrow_drop_down ] [button: file_open Import ]
      [button "Pack, OP DK" current: inventory_2 Pack ] [button "LZ/PZ": flight_land LZ/PZ ] [button "Routes": route Routes ] [button "Threats, 1 on the map": crisis_alert 1 Threats ] [button "Imports, 3 items": move_to_inbox 3 Imports ]
    [button: collections_bookmark Library ] [button "Account, Colin McFadden": CM ]
    {"Pack panel, OP DK"}
          groups
            OP DK
          Active
          Mission Pack · you are the owner
      [button "Collapse panel": left_panel_close ]
        North Georgia night LZ/PZ and route planning for B Co 2-10 AVN.
        [button "4 members, 2 here now: Colin M. and Sam B. Show members": CM SB JR MW 4 members 2 here now ] [button: person_add Invite ]
        [button: check Items ] [button: History ]
        hexagon
          LZ/PZ
        3
        [button current: SB LZ IBIS (OWEST) Sam B. · 4 min ago , open on the map ] [button "More actions for LZ IBIS (OWEST)": more_vert ]
          [button: JR LZ HAWK (ROCK) Jess R. · 13:21 , changed in the pack since you looked ] [button "More actions for LZ HAWK (ROCK)": more_vert ]
          From Library · Original changed [button "Update LZ HAWK (ROCK) from its Library original": Update ]
        [button: CM LZ BLUEBIRD (TWMILE) Colin M. · Oct 4 ] [button "More actions for LZ BLUEBIRD (TWMILE)": more_vert ]
        route
          Routes
        2
        [button: SB OP DK INGRESS 2 routes · Sam B. · 13:38 , changed in the pack since you looked ] [button "More actions for OP DK INGRESS": more_vert ]
        [button: CM OP DK EGRESS 1 route · Colin M. · Oct 4 ] [button "More actions for OP DK EGRESS": more_vert ]
        scatter_plot
          Point sets
        1
        [button: CM NORTH GA POINTS 1,234 points · Colin M. · 09:40 ] [button "More actions for NORTH GA POINTS": more_vert ]
      [button: library_add Add from Library ] [button: task_alt Finish pack ]
    {"Map"}
      [button "Search a grid or lat/long": search ]
        MGRS target
      [text "16S GC 26318 55907"] [button "Go to this grid": arrow_forward ]
    [button "OP DK is live, all changes synced. Colin M. and Sam B. here now. Show members": CM SB cloud_done Live · all changes synced ]
      [button "Map layers": layers ] [button "My location": my_location ]
      [button "Grid under the crosshair, 16S GC 26318 55907. Tap to copy": add 16S GC 26318 55907 ]
    {"LZ IBIS (OWEST), open item"}
          LZ IBIS (OWEST)
          Aircraft, analysis and tools
      [button "Close the LZ IBIS (OWEST) pane": close ]
        16S GC 26318 55907
        check_circle Analyzed check Synced
        SB Sam B. is also here
        [button: view_in_ar 3D ] [button "Rename LZ IBIS (OWEST)": edit ] [button "More actions for LZ IBIS (OWEST)": more_vert ]
        groups Saved to OP DK as you work. 4 members can see it.
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
