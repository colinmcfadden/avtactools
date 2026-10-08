# iPhone · Imports

- Platform: iOS iPhone, 393 x 852 pt
- Screenshot: `../png/IP-Imports.png`  ·  Source: `../screens/IP-Imports.dc.html`
- Web screen it adapts: 09 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
  [button "Workspace: Personal. Change workspace": Personal ]
      {role=group "Import and Library"}
      [button "Import a file": ] [button "Library": ]
    [button "Account, Colin McFadden": CM ]
    {role=group "Map controls"}
    [button "Map layers": ] [button "My location": ]
    [button "Grid under the crosshair, 16S GC 28940 55577. Tap to copy": 16S GC 28940 55577 ]
    {"Imports panel"}
          Imports
          5 items on the map
      [button: Import ] [button "Collapse the Imports panel": ]
            Open files in another app and choose EZ/PZ, or tap Import.
            .msnx · .LPS · .ths · KMZ · GeoTIFF · image + world file
        Local Points 2
              NORTH GA POINTS
              1,234 points · Shown
              Library
            [button "Hide NORTH GA POINTS": ] [button "More actions for NORTH GA POINTS": ]
              KGVL TAXI POINTS
              46 points · Hidden
              This session
            [button "Show KGVL TAXI POINTS": ] [button "More actions for KGVL TAXI POINTS": ]
        Library items are saved to your account. This session items are not saved.
        Map Overlays 1
                KJZP_CHART.tif
                GeoTIFF · 2.4 MB · georeferenced
                This session
              [button "Hide KJZP_CHART.tif": ] [button "More actions for KJZP_CHART.tif": ]
              Opacity
              [range "60"]
            60%
        Mission Files 1
              GOAT SUCKER.msnx
              2 routes · 91 points
              Library
              [button: Open in Routes ]
          [button "More actions for GOAT SUCKER.msnx": ]
        Threat Files 1
              THREATS_OCT.ths
              7 threats · listed under Threats
              This device only
              [button: Open in Threats ]
          [button "More actions for THREATS_OCT.ths": ]
        Threats stay on this device only, in an encrypted file that is wiped 48 hours after your last change and when you sign out. They are never saved to your account or shared with a Mission Pack.
    {"Panels"}
    [button: LZ/PZ ] [button: Routes ] [button "Threats, 1 on the map": 1 Threats ] [button "Imports, 5 items" current: 5 Imports ]
```
