# iPhone · Routes

- Platform: iOS iPhone, 393 x 852 pt
- Screenshot: `../png/IP-Routes.png`  ·  Source: `../screens/IP-Routes.dc.html`
- Web screen it adapts: 02 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
  [button "Workspace: Personal. Change workspace": Personal ]
      {role=group "Import and Library"}
      [button "Import a file": ] [button "Library": ]
    [button "Account, Colin McFadden": CM ]
    {role=group "Map controls"}
    [button "Map layers": ] [button "My location": ]
    [button "Grid under the crosshair, 16S GC 28941 55264. Tap to copy": 16S GC 28941 55264 ]
    {"Routes panel"}
            Routes
            2 route sets in this session
        [button "Lower the Routes panel": ]
            SKETCHED ROUTES
          [button "Rename SKETCHED ROUTES": ]
          Unsaved changes 1 route · 4 points
          [button "Save SKETCHED ROUTES": Save ] [button "Export .msnx, SKETCHED ROUTES": Export .msnx ]
              Include threats (1) as a .ths file
              Built on this device, never saved to your account
          [button role=switch checked=true: ]
        [button "ROUTE 1, 4.2 nm, 2:30, 40 lb. Open its plan": ROUTE 1 4.2 nm · 2:30 · 40 lb ]
          [button "Open in ForeFlight: ROUTE 1": Open in ForeFlight ] [button "Hide ROUTE 1": ] [button "More actions for ROUTE 1": ]
            GOAT SUCKER.msnx
          [button "Rename GOAT SUCKER.msnx": ]
          Imported Saved · Oct 4 2 routes · 91 points
          [button disabled: Saved ] [button "Export .msnx, GOAT SUCKER.msnx": Export .msnx ]
        [button: Sketch a route ] [button: Import .msnx ]
        Or long-press the map and choose Add route point here.
    {"Panels"}
    [button: LZ/PZ ] [button current: Routes ] [button "Threats, 1 on the map": 1 Threats ] [button "Imports, 3 items": 3 Imports ]
```
