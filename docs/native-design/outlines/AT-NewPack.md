# Android tablet · New pack

- Platform: Android tablet, 1280 x 800 dp, landscape
- Screenshot: `../png/AT-NewPack.png`  ·  Source: `../screens/AT-NewPack.dc.html`
- Web screen it adapts: 12 (see README, Screens)
- Interactive prototype: yes (state in the DCLogic script at the end of the source)

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
      {role=dialog "New Mission Pack"}
        groups
          New Mission Pack
        [button "Close": close ]
        A shared space for one operation. Everyone in it edits the same LZ/PZs, routes and point sets live.
          Name
        [text "{{ name }}"]
          Description (optional)
        [text "{{ desc }}"]
        Who can open it
        {role=group}
          [button: check Just me person Just me ]
          [button: check A team groups A team ]
          [button: check Specific people person_add Specific people ]
        lock Only you can open it. You can invite people later from Members.
        [button: Team groups B Co 2-10 AVN · 18 members arrow_drop_down ] [button: They can Edit arrow_drop_down ]
          {{ inviteLabel }}
        person_add
          Name or email address [text "{{ invite }}"]
        [button "Add to the invite list": add ]
        {{ item.initial }}
            {{ item.email }}
            {{ item.role }}
        [button "{{ item.removeLabel }}": close ]
        [button role=checkbox checked={{ startChecked }}: check ]
          Start with items from my Library · choose after creating
        info While a pack is open, everything you create goes into it and its members can see it. Threats are never added.
        [button: Cancel ] [button: Create pack ] [button disabled: Create pack ]
```
