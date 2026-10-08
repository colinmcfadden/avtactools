# Android phone · Save routes

- Platform: Android phone, 412 x 915 dp
- Screenshot: `../png/AP-SaveRoutes.png`  ·  Source: `../screens/AP-SaveRoutes.dc.html`
- Web screen it adapts: 05 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    [button "Search a grid or lat/long": search ]
      MGRS target
    [text "16S GC 28864 55349"] [button "Account, Colin McFadden": CM ]
    [button "Workspace: Personal. Change workspace": person Personal arrow_drop_down ] [button: file_open Import ] [button: collections_bookmark Library ]
    [button "Map layers": layers ] [button "My location": my_location ]
    [button "Grid under the crosshair, 16S GC 28864 55349. Tap to copy": add 16S GC 28864 55349 ]
    {"Routes panel"}
          Routes
          2 route sets in this session
      [button "Open the Routes list full height": expand_less ]
            SKETCHED ROUTES
          [button "Rename SKETCHED ROUTES": edit ]
          Unsaved changes 1 route · 4 points
          [button: save Save ] [button: upload Export .msnx ]
          [checkbox checked]
            Include threats (1) as a .ths file
              ROUTE 1
              4.2 nm · 2:30 · 40 lb
          [button "Share ROUTE 1 as GPX or FPL": share ] [button "Hide ROUTE 1": visibility ] [button "More actions for ROUTE 1": more_vert ]
            GOAT SUCKER.msnx
          [button "Rename GOAT SUCKER.msnx": edit ]
          check_circle Saved · Oct 4 Imported · 2 routes · 91 points
    {"Panels"}
    [button: flight_land LZ/PZ ] [button current: route Routes ] [button "Threats, 1 on the map": crisis_alert 1 Threats ] [button "Imports, 3 items": move_to_inbox 3 Imports ]
      {role=dialog}
        Save routes
        Saves the sketched routes with their plans.
            Name
          ROUTE 1 [text "ROUTE 1"] [button "Clear the name": cancel ]
          You can rename it any time.
        What is saved
          check_circle 1 route, 4 points, 4.2 nm
          check_circle Speeds, altitudes, winds, fuel flow and the start time
          info Threats are never saved. Export them as a .ths if you need them next to the mission.
        folder Saves to your Library [button: Cancel ] [button: Save ]
```
