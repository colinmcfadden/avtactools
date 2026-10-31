# iPad · Save routes

- Platform: iOS iPad, 1210 x 834 pt, landscape
- Screenshot: `../png/IT-SaveRoutes.png`  ·  Source: `../screens/IT-SaveRoutes.dc.html`
- Web screen it adapts: 05 (see README, Screens)
- Interactive prototype: yes (state in the DCLogic script at the end of the source)

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    EZ/PZ, Routes, Save routes
      {role=search "MGRS target"}
        MGRS target
      [text "16S GC 28864 55349"] [button: Go ]
        [button "Import": ] [button "Library": ]
      [button "Account, Colin McFadden": CM ]
      [button "Map layers": ] [button "My location": ]
      [button "Grid under the crosshair, 16S GC 28864 55349. Tap to copy": 16S GC 28864 55349 ]
    {"Sidebar"}
      [button "Hide sidebar": ] [button "Workspace: Personal. Change workspace": Personal ]
      {"Panels"}
      [button: LZ/PZ ] [button current: Routes ] [button "Threats, 1 on the map": Threats 1 ] [button "Imports, 3 items": Imports 3 ]
            Route Sets 2
          this session
          {current}
              SKETCHED ROUTES
            [button "Rename SKETCHED ROUTES": ]
            Unsaved changes 1 route · 4 points
            [button: Save ] [button: Export .msnx ]
            Include threats (1) as a .ths file Built on this device [button role=switch checked=true: ]
              [button: ROUTE 1 ] [button "Open ROUTE 1 in ForeFlight": ] [button "Hide ROUTE 1": ] [button "More actions for ROUTE 1": ]
              4.2 nm · 2:30 · 40 lb
            Saves to your Library ⌘S
              GOAT SUCKER.msnx
            [button "Rename GOAT SUCKER.msnx": ]
            Imported Saved · Oct 4
            2 routes · 91 points
            [button disabled: Saved ] [button: Export .msnx ]
          [button: Sketch a route ] [button: Import .msnx ]
          Long-press the map to add a route point, or drag a .msnx file onto the map to import it.
    {"Inspector, ROUTE 1"}
        ROUTE 1
      [button "Close inspector": ]
        In SKETCHED ROUTES
            Distance
            4.2 nm
            Time
            2:30
            Fuel
            40 lb
            Nav Log
          4 points
            [button: TGT Target ]
            [button: SP Start point ]
            [button: RP Release point ]
            [button: TGT Target ]
          A row's speed, altitude and wind are for the leg that arrives at that point.
        [button: Edit plan ] [button: Open in ForeFlight ]
      {role=dialog "Save routes"}
        [button "Cancel": ]
          Save routes
          [button: Save ] [button disabled: Save ]
        Saves the sketched routes with their plans.
            Name
            [text "{{ draft }}"] [button "Clear name": ]
            Return saves. You can rename it any time. Give it a name to save.
            What Is Saved
                  1 route, 4 points, 4.2 nm
                  ROUTE 1 · 2:30 · 40 lb
                  Each route's plan
                  Speeds, altitudes, winds, fuel flow and the start time
                  Threats are never saved
                  Export them as a .ths if you need them next to the mission.
            Threats stay on this device only, in an encrypted file that is wiped 48 hours after your last change and when you sign out. They are never saved to your account or shared with a Mission Pack.
          Saves to your Library Personal
```
