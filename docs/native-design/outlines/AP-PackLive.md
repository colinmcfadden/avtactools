# Android phone · Pack live

- Platform: Android phone, 412 x 915 dp
- Screenshot: `../png/AP-PackLive.png`  ·  Source: `../screens/AP-PackLive.dc.html`
- Web screen it adapts: 10 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    [button "Search a grid or lat/long": search ]
      MGRS target
    [text "16S GC 28864 55349"] [button "Account, Colin McFadden": CM ]
    [button "Workspace: Mission Pack OP DK. 4 people here now: Colin McFadden, Sam B., Jess R. and 1 more. Change workspace": groups OP DK CM SB JR +1 arrow_drop_down ] [button: file_open Import ] [button: collections_bookmark Library ]
    {role=status}
    cloud_done Live · all changes synced
    [button "Map layers": layers ] [button "My location": my_location ]
    [button "Grid under the crosshair, 16S GC 28864 55349. Tap to copy": add 16S GC 28864 55349 ]
    {"LZ/PZ panel"}
          LZ/PZ
          OP DK · 3 items · live
      [button "Open the LZ/PZ list full height": expand_less ]
          LZ IBIS (OWEST)
        [button "Rename LZ IBIS (OWEST)": edit ] [button "Open LZ IBIS (OWEST) in 3D": view_in_ar 3D ] [button "More actions for LZ IBIS (OWEST)": more_vert ]
        16S GC 26318 55907
        check_circle Analyzed cloud_done Synced SB Sam B. is also here
        groups Saved to OP DK as you work. 4 members can see it.
      flight_land
          LZ HAWK (ROCK)
          16S GC 31204 58811 · Analyzed
          collections_bookmark From Library update Original changed
      [button "Open LZ HAWK (ROCK) in 3D": view_in_ar ]
      flight_land
          LZ BLUEBIRD (TWMILE)
          16S GC 28110 52476 · Analyzed
          JR Jess R. editing
      [button "Open LZ BLUEBIRD (TWMILE) in 3D": view_in_ar ]
      add_circle New LZ/PZs you start while OP DK is open are added to it.
    {"Panels"}
    [button: groups Pack ] [button current: flight_land LZ/PZ ] [button: route Routes ] [button "Threats, 1 on the map": crisis_alert 1 Threats ] [button "Imports, 3 items": move_to_inbox 3 Imports ]
```
