# iPad · Routes

- Platform: iOS iPad, 1210 x 834 pt, landscape
- Screenshot: `../png/IT-Routes.png`  ·  Source: `../screens/IT-Routes.dc.html`
- Web screen it adapts: 02 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    EZ/PZ, Routes
      {role=search "MGRS target"}
        MGRS target
      [text "16S GC 28864 55349"] [button: Go ]
        [button "Import": ] [button "Library": ]
      [button "Account, Colin McFadden": CM ]
      [button "Map layers": ] [button "My location": ]
      [button "Grid under the crosshair, 16S GC 28906 55282. Tap to copy": 16S GC 28906 55282 ]
    {"Sidebar"}
      [button "Hide sidebar": ] [button "Workspace: Personal. Change workspace": Personal ]
      {"Panels"}
      [button: LZ/PZ ] [button current: Routes ] [button "Threats, 1 on the map": Threats 1 ] [button "Imports, 3 items": Imports 3 ]
            Route sets 2
          this session
              SKETCHED ROUTES
            [button "Rename SKETCHED ROUTES": ]
            Unsaved changes 1 route · 4 points
            [button: Save ] [button: Export .msnx ]
            Include threats (1) as a .ths file [button role=switch checked=true: ]
            The .ths file is built on this device.
              [button "ROUTE 1, 4.2 nm, 2:30, 40 lb. Plan shown in the inspector" current: ROUTE 1 4.2 nm · 2:30 · 40 lb ] [button "More actions for ROUTE 1": ]
              [button "Open ROUTE 1 in ForeFlight": Open in ForeFlight ] [button "Hide ROUTE 1 on the map": Hide ]
            Saves to your Library ⌘S
              GOAT SUCKER.msnx
            [button "Rename GOAT SUCKER.msnx": ]
            Imported Saved · Oct 4
          [button: 2 routes · 91 points ]
            [button disabled: Saved ] [button: Export .msnx ]
          [button: Sketch a route ] [button: Import .msnx ]
          Long-press the map to add a route point. You can also drop a .msnx file on the map to import it.
    {"Inspector, ROUTE 1 plan"}
          ROUTE 1
          SKETCHED ROUTES · plan and nav log
      [button "Close inspector": ]
            Route plan
          [button "Date, not set. Choose a date": Date mm/dd/yyyy ]
              Temp °C
            [text "15"]
              Fuel lb/hr
            [text "960"]
          [button: Winds ]
            Nav log
          4 points
            Point Alt Speed Wind Time
            {"Points of ROUTE 1"}
              [button ".TGT, start. Start altitude 50 ft AGL, 0:00, clock not set. Edit": .TGT 50 AGL — — 0:00 Start --:--:-- ]
              [button ".SP. Altitude to 50 ft AGL, speed to 100 kt GS, wind 0°T at 0 kt. Leg 0.8 nm, 096°T, 100 kt, 0:30, clock not set": .SP 50 AGL 100 GS 0°/0 kt 0:30 0.8 nm · 096°T · 100 kt --:--:-- ]
                {role=group "Edit .SP"}
                      Alt to
                    [text "50"]
                  [button "Altitude reference for .SP, AGL. Change": AGL ]
                      Speed to
                    [text "100"]
                  [button "Speed reference for .SP, GS. Change": GS ]
                      Wind °T
                    [text "0"]
                      KT
                    [text "0"]
                  [button "Clock time at .SP, not set. Set clock time": Clock --:--:-- ]
              [button ".RP. Altitude to 50 ft AGL, speed to 100 kt GS, wind 0°T at 0 kt. Leg 2.8 nm, 251°T, 100 kt, 2:10, clock not set. Edit": .RP 50 AGL 100 GS 0°/0 kt 2:10 2.8 nm · 251°T · 100 kt --:--:-- ]
              [button ".TGT. Altitude to 50 ft AGL, speed to 100 kt GS, wind 0°T at 0 kt. Leg 0.6 nm, 327°T, 100 kt, 2:30, clock not set. Edit": .TGT 50 AGL 100 GS 0°/0 kt 2:30 0.6 nm · 327°T · 100 kt --:--:-- ]
            Total 4.2 nm · 2:30 · 40 lb
      [button: Fetch elevations ]
        UH-60L Black Hawk · speeds, altitudes and winds are “to” each point and export to AMPS
```
