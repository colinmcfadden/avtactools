# Android phone · Routes

- Platform: Android phone, 412 x 915 dp
- Screenshot: `../png/AP-Routes.png`  ·  Source: `../screens/AP-Routes.dc.html`
- Web screen it adapts: 02 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    [button "Search a grid or lat/long": search ]
      MGRS target
    [text "16S GC 28864 55349"] [button "Account, Colin McFadden": CM ]
    [button "Workspace: Personal. Change workspace": person Personal arrow_drop_down ] [button: file_open Import ] [button: collections_bookmark Library ]
    [button "Map layers": layers ] [button "My location": my_location ]
    [button "Grid under the crosshair, 16S GC 28936 55287. Tap to copy": add 16S GC 28936 55287 ]
    {"Routes panel"}
          Routes
          2 route sets in this session
      [button "Open the route sets full height": expand_less ]
            SKETCHED ROUTES
          [button "Rename SKETCHED ROUTES": edit ]
          Unsaved changes 1 route · 4 points
          [button: save Save ] [button: upload_file Export .msnx ] Saves to your Library
          [checkbox checked] check
            Include threats (1) as a .ths file Built on this device; never saved to your account
              ROUTE 1
              4.2 nm · 2:30 · 40 lb
          [button "Share ROUTE 1 as GPX or FPL": share ] [button "Hide ROUTE 1": visibility ] [button "More actions for ROUTE 1": more_vert ] [button "Open the plan for ROUTE 1": chevron_right ]
            GOAT SUCKER.msnx
          [button "Rename GOAT SUCKER.msnx": edit ]
          download Imported check Saved · Oct 4 2 routes · 91 points
          [button disabled: check Saved ] [button: upload_file Export .msnx ]
      [button: draw Sketch a route ] [button: file_open Import .msnx ]
    {"Panels"}
    [button: flight_land LZ/PZ ] [button current: route Routes ] [button "Threats, 1 on the map": crisis_alert 1 Threats ] [button "Imports, 3 items": move_to_inbox 3 Imports ]
```
