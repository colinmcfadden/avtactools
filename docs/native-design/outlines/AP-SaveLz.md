# Android phone · Save LZ/PZ

- Platform: Android phone, 412 x 915 dp
- Screenshot: `../png/AP-SaveLz.png`  ·  Source: `../screens/AP-SaveLz.dc.html`
- Web screen it adapts: 04 (see README, Screens)
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
              LZ/PZ 1
            [button "Rename LZ/PZ 1": edit ] [button "More actions for LZ/PZ 1": more_vert ]
            16S GC 28864 55349
            check_circle Analyzed Unsaved changes
            [button: save Save ] [button: view_in_ar 3D ] Saves to your Library
        Also open
        flight_land
            LZ HAWK (ROCK)
            16S GC 31204 58811 · Saved · Oct 3
        [button "Open LZ HAWK (ROCK) in 3D": view_in_ar ]
        flight_land
            LZ/PZ 3
            16S GC 27390 54102 · Analyze to save
        [button "Open LZ/PZ 3 in 3D": view_in_ar ]
      {"Panels"}
      [button current: flight_land LZ/PZ ] [button: route Routes ] [button "Threats, 1 on the map": crisis_alert 1 Threats ] [button "Imports, 3 items": move_to_inbox 3 Imports ]
    {role=dialog "Save LZ/PZ"}
      Save LZ/PZ
      Name this LZ/PZ so you can find it in your Library.
        Name
      [text "LZ/PZ 1"] [button "Clear name": cancel ]
      You can rename it any time.
      What is saved
          check Boundary, target 16S GC 28864 55349 and slope analysis
          check 3 helicopters, 2 sectors, doghouses and flight data
        info Threats and routes are not included. Routes are saved on their own.
        collections_bookmark Saves to your Library
      [button: Cancel ] [button: save Save ]
```
