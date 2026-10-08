# iPad · Imports

- Platform: iOS iPad, 1210 x 834 pt, landscape
- Screenshot: `../png/IT-Imports.png`  ·  Source: `../screens/IT-Imports.dc.html`
- Web screen it adapts: 09 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    EZ/PZ, Imports
      {role=search "MGRS target"}
        MGRS target
      [text "16S GC 28864 55349"] [button: Go ]
        [button "Import": ] [button "Library": ]
      [button "Account, Colin McFadden": CM ]
      [button "Map layers": ] [button "My location": ]
      [button "Grid under the crosshair, 16S GC 29028 55427. Tap to copy": 16S GC 29028 55427 ]
    {"Sidebar"}
      [button "Hide sidebar": ] [button "Workspace: Personal. Change workspace": Personal ]
      {"Panels"}
      [button: LZ/PZ ] [button: Routes ] [button "Threats, 1 on the map": Threats 1 ] [button "Imports, 5 items" current: Imports 5 ]
            Imports
          5 items on the map
                Drop files here, or anywhere on the map
                .msnx · .LPS · .ths · KMZ · GeoTIFF · image + world file
            [button: Import… ] ⌘I
            Local points 2
                NORTH GA POINTS
                1,234 points Library
              [button "Hide NORTH GA POINTS": ] [button "More actions for NORTH GA POINTS": ]
                KGVL TAXI POINTS
                46 points This session
              [button "Show KGVL TAXI POINTS": ] [button "More actions for KGVL TAXI POINTS": ]
            Map overlays 1
                KJZP_CHART.tif
                GeoTIFF · 2.4 MB · georeferenced This session
              [button "Hide KJZP_CHART.tif": ] [button "More actions for KJZP_CHART.tif": ]
              Opacity
              [range "60"]
            60%
            Mission files 1
                GOAT SUCKER.msnx
                2 routes · 91 points Library
          [button: Open in Routes ]
            Threat files 1
                THREATS_OCT.ths
                7 threats · listed under Threats This device only
          [button: Open in Threats ]
          Threats stay on this device only, in an encrypted file that is wiped 48 hours after your last change and when you sign out. They are never saved to your account or shared with a Mission Pack.
    {role=menu "Import"}
      Import a file ⌘I
    [button role=menuitem: Mission file AMPS .msnx ]
    [button role=menuitem: Local points AMPS .LPS ]
    [button role=menuitem: Threats .ths · stays on this device ]
    [button role=menuitem: Map or overlay KMZ · GeoTIFF · image + world file ]
      You can also drag files onto the map or the Imports panel, or open a file in another app and choose EZ/PZ.
```
