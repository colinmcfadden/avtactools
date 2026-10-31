# Android phone · LZ/PZ plan

- Platform: Android phone, 412 x 915 dp
- Screenshot: `../png/AP-LzPlan.png`  ·  Source: `../screens/AP-LzPlan.dc.html`
- Web screen it adapts: 01 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    [button "Search a grid or lat/long": search ]
      MGRS target
    [text "16S GC 28864 55349"] [button "Account, Colin McFadden": CM ]
    [button "Workspace: Personal. Change workspace": person Personal arrow_drop_down ] [button: file_open Import ] [button: collections_bookmark Library ]
    {"LZ/PZ 1 plan and tools"}
      [button "Back to the open LZ/PZs, 3 open": arrow_back ]
          LZ/PZ 1
          16S GC 28864 55349
      [button "Collapse panel": expand_more ]
      check_circle Analyzed Unsaved changes
      [button: save Save ] [button: view_in_ar 3D ] [button "More actions for LZ/PZ 1": more_vert ] Saves to your Library
      [button: flight Aircraft UH-60L — UH-60L Black Hawk 76 m spacing · 100 kt arrow_drop_down ] [button "Manage aircraft profiles": Manage ]
            LZ/PZ analysis
            check_circle Analyzed. Planning graphics, export and save are unlocked.
        [button: refresh Re-analyze ]
        [button role=switch checked=false: Slope map ] [button role=switch checked=true: LZ box check ]
        LZ/PZ tools
        [button: Draw LZ ] [button: Helo ] [button: PZ ] [button: Sector ] [button: Unit ] [button: L-GA ] [button: R-GA ]
        Tools place at the crosshair. Done and Cancel finish the mode.
        Export
        Routes
      [button: crop_free Set capture area ] [button: share Export LZ card ] [button: Sketch a route ]
    {"Panels"}
    [button current: flight_land LZ/PZ ] [button: route Routes ] [button "Threats, 1 on the map": crisis_alert 1 Threats ] [button "Imports, 3 items": move_to_inbox 3 Imports ]
```
