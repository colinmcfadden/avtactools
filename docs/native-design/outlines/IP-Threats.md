# iPhone · Threats

- Platform: iOS iPhone, 393 x 852 pt
- Screenshot: `../png/IP-Threats.png`  ·  Source: `../screens/IP-Threats.dc.html`
- Web screen it adapts: 03 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
  [button "Workspace: Personal. Change workspace": Personal ]
      {role=group "Import and Library"}
      [button "Import a file": ] [button "Library": ]
    [button "Account, Colin McFadden": CM ]
    {role=group "Map controls"}
    [button "Map layers": ] [button "My location": ]
    [button "Grid under the crosshair, 16S GC 28719 55450. Tap to copy": 16S GC 28719 55450 ]
    {"Threats panel"}
          Threats
          2 on the map · this device only
      [button "Expand the Threats panel": ]
          Threats stay on this device only, in an encrypted file that is wiped 48 hours after your last change and when you sign out. They are never saved to your account or shared with a Mission Pack.
        [button "Add threat at the crosshair": Add threat ] [button: Import .ths ]
        Adds one at the crosshair. You can also long-press the map and choose Add threat here.
          On the Map
        2
                Threat 1
                Detect 25 nm · Engage 15 nm
            Terrain mask on [button "Hide Threat 1": ] [button "Edit Threat 1": ] [button "Remove Threat 1": ]
                Threat 2
                Detect 8 nm · Engage 5 nm
            Rings only [button "Hide Threat 2": ] [button "Edit Threat 2": ] [button "Remove Threat 2": ]
        Export
        [button: Export .ths for AMPS Built on this device ]
        [button "Share KMZ (ForeFlight, ATAK, Aero)": Share KMZ ForeFlight, ATAK, Aero ]
        Showing a terrain mask or building a KMZ sends threat positions to the server once. Nothing is kept.
      [button: Remove all threats ]
    {"Panels"}
    [button: LZ/PZ ] [button: Routes ] [button "Threats, 2 on the map" current: 2 Threats ] [button "Imports, 3 items": 3 Imports ]
```
