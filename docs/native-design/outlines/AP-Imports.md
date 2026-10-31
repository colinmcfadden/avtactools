# Android phone · Imports

- Platform: Android phone, 412 x 915 dp
- Screenshot: `../png/AP-Imports.png`  ·  Source: `../screens/AP-Imports.dc.html`
- Web screen it adapts: 09 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    [button "Search a grid or lat/long": search ]
      MGRS target
    [text "16S GC 28864 55349"] [button "Account, Colin McFadden": CM ]
    [button "Workspace: Personal. Change workspace": person Personal arrow_drop_down ] [button: file_open Import ] [button: collections_bookmark Library ]
    {"Imports panel"}
          Imports
          5 items on the map
      [button: file_open Import ] [button "Lower the Imports panel": expand_more ]
      info
          Open files in another app and choose EZ/PZ, or tap Import.
          .msnx · .LPS · .ths · KMZ · GeoTIFF · image + world file
      scatter_plot Local points 2
          NORTH GA POINTS
          1,234 points collections_bookmark Library
      [button "Hide NORTH GA POINTS": visibility ] [button "More actions for NORTH GA POINTS": more_vert ]
          KGVL TAXI POINTS
          46 points · hidden schedule This session
      [button "Show KGVL TAXI POINTS": visibility_off ] [button "More actions for KGVL TAXI POINTS": more_vert ]
      layers Map overlays 1
          KJZP_CHART.tif
          GeoTIFF · 2.4 MB · georeferenced
          schedule This session
      [button "Hide KJZP_CHART.tif": visibility ] [button "More actions for KJZP_CHART.tif": more_vert ]
        Opacity of KJZP_CHART.tif
        [range "60"]
      60%
      route Mission files 1
          GOAT SUCKER.msnx
          2 routes · 91 points collections_bookmark Library
      [button: Open in Routes ]
      crisis_alert Threat files 1
          THREATS_OCT.ths
          7 threats · listed under Threats
          lock This device only
      [button: Open in Threats ]
    {"Panels"}
    [button: flight_land LZ/PZ ] [button: route Routes ] [button "Threats, 1 on the map": crisis_alert 1 Threats ] [button "Imports, 5 items" current: move_to_inbox 5 Imports ]
```
