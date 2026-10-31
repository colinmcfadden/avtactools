# Android tablet · Save routes

- Platform: Android tablet, 1280 x 800 dp, landscape
- Screenshot: `../png/AT-SaveRoutes.png`  ·  Source: `../screens/AT-SaveRoutes.dc.html`
- Web screen it adapts: 05 (see README, Screens)
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
            SKETCHED ROUTES
          [button "Rename SKETCHED ROUTES": edit ]
          Unsaved changes 1 route · 4 points
          [button: save Save ] [button: Export .msnx ]
        [button role=checkbox checked=true: check Include threats (1) as a .ths file ]
            [button "Expand ROUTE 1": chevron_right ]
              ROUTE 1
            [button "Share ROUTE 1 as GPX or FPL": share ] [button "Hide ROUTE 1": visibility ] [button "More actions for ROUTE 1": more_vert ]
            4.2 nm · 2:30 · 40 lb
          folder Saves to your Library Ctrl S
          description
            GOAT SUCKER.msnx
          [button "Rename GOAT SUCKER.msnx": edit ]
          input Imported check Saved · Oct 4
          2 routes · 91 points
          [button disabled: check Saved ] [button: Export .msnx ]
        [button: Sketch a route ] [button: Import .msnx ]
        touch_app Long-press the map to add a route point. A .msnx can also be dropped on the map, or opened in another app with EZ/PZ.
    {"Map"}
      [button "Search a grid or lat/long": search ]
        MGRS target
      [text "16S GC 28864 55349"] [button "Go to this grid": arrow_forward ]
      [button "Map layers": layers ] [button "My location": my_location ]
      [button "Grid under the crosshair, 16S GC 28864 55349. Tap to copy": add 16S GC 28864 55349 ]
    {"ROUTE 1 nav log"}
          ROUTE 1
          Nav log · SKETCHED ROUTES
      [button "Close the ROUTE 1 pane": close ]
            Distance
            4.2 nm
            Time
            2:30
            Fuel
            40 lb
          Route points
        4 · plus 2 shaping
      [button: TGT Target · start chevron_right ] [button: SP Start point chevron_right ] [button: RP Release point chevron_right ] [button: TGT Target · end chevron_right ] [button: tune Edit route plan ]
        Hand off
      [button: share Share as GPX or FPL ]
        Through the share sheet, to ATAK or Garmin Pilot.
      {role=dialog "Save routes"}
        Save routes
        Saves the sketched routes with their plans.
            Name
          [text "ROUTE 1"] [button "Clear name": cancel ]
          Press Done or Enter to save. You can rename it any time.
        What is saved
        route 1 route, 4 points, 4.2 nm
        tune Speeds, altitudes, winds, fuel flow and the start time
        crisis_alert Threats are never saved. They stay on this device only. Export them as a .ths if you need them next to the mission.
        folder Saves to your Library [button: Cancel ] [button: Save ]
```
