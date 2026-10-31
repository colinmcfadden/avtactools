# iPad · Threats

- Platform: iOS iPad, 1210 x 834 pt, landscape
- Screenshot: `../png/IT-Threats.png`  ·  Source: `../screens/IT-Threats.dc.html`
- Web screen it adapts: 03 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    EZ/PZ, Threats
      {role=search "MGRS target"}
        MGRS target
      [text "16S GC 28864 55349"] [button: Go ]
        [button "Import": ] [button "Library": ]
      [button "Account, Colin McFadden": CM ]
      [button "Map layers": ] [button "My location": ]
      [button "Grid under the crosshair, 16S GC 28864 55349. Tap to copy": 16S GC 28864 55349 ]
    {"Map menu at 16S GC 28802 55559"}
      16S GC 28802 55559
    [button: Add threat here ]
    [button: New LZ/PZ here ]
    [button: Add route point here ]
    [button: Copy grid ]
    {"Sidebar"}
      [button "Hide sidebar": ] [button "Workspace: Personal. Change workspace": Personal ]
      {"Panels"}
      [button: LZ/PZ ] [button: Routes ] [button "Threats, 2 on the map" current: Threats 2 ] [button "Imports, 3 items": Imports 3 ]
          Threats stay on this device only, in an encrypted file that is wiped 48 hours after your last change and when you sign out. They are never saved to your account or shared with a Mission Pack.
        [button: Add threat ] [button: Import .ths ]
        Or long-press the map and choose Add threat here, or drag a .ths file onto the map.
            On the map 2
          [button: Remove all threats ]
              Threat 1
            [button "Hide Threat 1": ] [button "Edit Threat 1": ] [button "Remove Threat 1": ]
            Detect 25 nm · Engage 15 nm
            Terrain mask on
              Threat 2
            [button "Hide Threat 2": ] [button "Edit Threat 2": ] [button "Remove Threat 2": ]
            Detect 8 nm · Engage 5 nm
            Rings only
            Export
            [button: Export .ths for AMPS Built on this device ]
            [button: Share as KMZ ForeFlight, ATAK, Aero ]
          Showing a terrain mask or building a KMZ sends threat positions to the server once. Nothing is kept.
```
