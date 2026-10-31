# Android tablet · Routes

- Platform: Android tablet, 1280 x 800 dp, landscape
- Screenshot: `../png/AT-Routes.png`  ·  Source: `../screens/AT-Routes.dc.html`
- Web screen it adapts: 02 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    {"Navigation rail"}
    [button "Workspace: Personal. Change workspace": person Personal arrow_drop_down ] [button: file_open Import ]
      [button "LZ/PZ, unsaved changes": flight_land LZ/PZ ] [button "Routes, unsaved changes" current: route Routes ] [button "Threats, 1 on the map": crisis_alert 1 Threats ] [button "Imports, 3 items": move_to_inbox 3 Imports ]
    [button: collections_bookmark Library ] [button "Account, Colin McFadden": CM ]
    {"Routes panel"}
          Routes
          2 route sets in this session
      [button "Collapse panel": left_panel_close ]
          Route sets
        2
          [button "SKETCHED ROUTES, hide its routes": SKETCHED ROUTES expand_less ] [button "Rename SKETCHED ROUTES": edit ]
          Unsaved changes 1 route · 4 points
          [button: save Save ] [button: upload_file Export .msnx ]
          [button role=checkbox checked=true: check ]
            Include threats (1) as a .ths file Built on this device; not saved to your account.
          [button "ROUTE 1, 4.2 nm, 2:30, 40 lb. Its plan is open beside the map" current: ROUTE 1 4.2 nm · 2:30 · 40 lb ] [button "Share ROUTE 1 as GPX or FPL": share ] [button "Hide ROUTE 1": visibility ] [button "More actions for ROUTE 1": more_vert ]
          folder Saves to your Library Ctrl S
          [button "GOAT SUCKER.msnx, show its 2 routes": description GOAT SUCKER.msnx expand_more ] [button "Rename GOAT SUCKER.msnx": edit ]
          move_to_inbox Imported check Saved · Oct 4
          2 routes · 91 points
          [button disabled: check Saved ] [button: upload_file Export .msnx ]
        [button: route Sketch a route ] [button: file_open Import .msnx ]
        touch_app Long-press the map to add a route point there. You can also drag a .msnx file onto the map to import it.
    {"Map"}
      [button "Search a grid or lat/long": search ]
        MGRS target
      [text "16S GC 28864 55349"] [button "Go to this grid": arrow_forward ]
      [button "Map layers": layers ] [button "My location": my_location ]
      [button "Grid under the crosshair, 16S GC 28906 55288. Tap to copy": add 16S GC 28906 55288 ]
    {"ROUTE 1 plan"}
          ROUTE 1
          Plan · in SKETCHED ROUTES
      [button "Close the ROUTE 1 plan": close ]
          Whole route
        [button: air Winds ]
        [button "Date, not set. Choose a date": Date mm/dd/yyyy calendar_month ]
            Temp °C
          [text "15"]
            Fuel lb/hr
          [text "960"]
          Nav log
        4 points
        Tap a point or a value to change it.
          {"ROUTE 1 nav log"}
            Point Alt to Spd to Wind to Time
            [button "Edit point .TGT, the start, clock time not set": .TGT --:--:-- ] [button "Start altitude, 50 ft AGL": 50 AGL ] Start 0:00
            south Leg to .SP: 0.8 nm · 096°T · 100 kt
            [button "Edit point .SP, clock time not set": .SP --:--:-- ] [button "Altitude to .SP, 50 ft AGL": 50 AGL ] [button "Speed to .SP, 100 kt ground speed": 100 GS ] [button "Wind to .SP, from 0 degrees true at 0 kt": 0°T 0 kt ] 0:30
            south Leg to .RP: 2.8 nm · 251°T · 100 kt
            [button "Edit point .RP, clock time not set": .RP --:--:-- ] [button "Altitude to .RP, 50 ft AGL": 50 AGL ] [button "Speed to .RP, 100 kt ground speed": 100 GS ] [button "Wind to .RP, from 0 degrees true at 0 kt": 0°T 0 kt ] 2:10
            south Leg to .TGT: 0.6 nm · 327°T · 100 kt
            [button "Edit point .TGT, the end, clock time not set": .TGT --:--:-- ] [button "Altitude to .TGT, 50 ft AGL": 50 AGL ] [button "Speed to .TGT, 100 kt ground speed": 100 GS ] [button "Wind to .TGT, from 0 degrees true at 0 kt": 0°T 0 kt ] 2:30
            Total 4.2 nm · 40 lb 2:30
      [button: landscape Fetch elevations ]
        info UH-60L Black Hawk · speeds, altitudes and winds are “to” each point and export to AMPS
```
