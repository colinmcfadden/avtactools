# Android phone · Saved

- Platform: Android phone, 412 x 915 dp
- Screenshot: `../png/AP-Saved.png`  ·  Source: `../screens/AP-Saved.dc.html`
- Web screen it adapts: 19 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    [button "Search a grid or lat/long": search ]
      MGRS target
    [text "16S GC 28864 55349"] [button "Account, Colin McFadden": CM ]
    [button "Workspace: Personal. Change workspace": person Personal arrow_drop_down ] [button: file_open Import ] [button: collections_bookmark Library ]
    [button "Map layers": layers ] [button "My location": my_location ]
    [button "Grid under the crosshair, 16S GC 28864 55349. Tap to copy": add 16S GC 28864 55349 ]
    {"LZ/PZ panel"}
          LZ/PZ
          3 open · this session
      [button "Open the LZ/PZ list full height": expand_less ]
            LZ HAWK (BETA)
          [button "Rename LZ HAWK (BETA)": edit ] [button "More actions for LZ HAWK (BETA)": more_vert ]
          16S GC 28864 55349
          check_circle Analyzed done Saved · 14:05
          [button disabled: done Saved ] [button: view_in_ar 3D ] Saves to your Library
      Also open
      flight_land
          LZ HAWK (ROCK)
          16S GC 31204 58811 · Saved · Oct 3
      [button "Open LZ HAWK (ROCK) in 3D": view_in_ar ]
      flight_land
          LZ/PZ 3
          16S GC 27390 54102 · Analyze to save
      [button "Open LZ/PZ 3 in 3D": view_in_ar ]
        Recent in Library
      [button: Browse all ]
      flight_land
          LZ HAWK (SHOPE)
          Updated Aug 25 · 17:34
      [button "Open LZ HAWK (SHOPE)": Open ]
      flight_land
          LZ RAZORBILL
          Updated Aug 25 · 17:27
      [button "Open LZ RAZORBILL": Open ]
    {role=status}
    Saved “LZ HAWK (BETA)” to your Library [button: Open Library ]
    {"Panels"}
    [button current: flight_land LZ/PZ ] [button: route Routes ] [button "Threats, 1 on the map": crisis_alert 1 Threats ] [button "Imports, 3 items": move_to_inbox 3 Imports ]
```
