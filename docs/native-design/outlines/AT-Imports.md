# Android tablet · Imports

- Platform: Android tablet, 1280 x 800 dp, landscape
- Screenshot: `../png/AT-Imports.png`  ·  Source: `../screens/AT-Imports.dc.html`
- Web screen it adapts: 09 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    {"Navigation rail"}
    [button "Workspace: Personal. Change workspace": person Personal arrow_drop_down ] [button: file_open Import ]
      [button "LZ/PZ, unsaved changes": flight_land LZ/PZ ] [button "Routes, unsaved changes": route Routes ] [button "Threats, 1 on the map": crisis_alert 1 Threats ] [button "Imports, 5 items" current: move_to_inbox 5 Imports ]
    [button: collections_bookmark Library ] [button "Account, Colin McFadden": CM ]
    {"Imports panel"}
          Imports
          5 items on the map
      [button "Collapse panel": left_panel_close ]
          Local points
        2
            NORTH GA POINTS
            1,234 points Library
          [button "Hide NORTH GA POINTS": visibility ] [button "More actions for NORTH GA POINTS": more_vert ]
            KGVL TAXI POINTS
            46 points This session
          [button "Show KGVL TAXI POINTS": visibility_off ] [button "More actions for KGVL TAXI POINTS": more_vert ]
          Map overlays
        1
        image
            KJZP_CHART.tif
            GeoTIFF · 2.4 MB · georeferenced
            This session
          [button "Hide KJZP_CHART.tif": visibility ] [button "More actions for KJZP_CHART.tif": more_vert ]
          Opacity
        [range "60"] 60%
          Mission files
        1
        route
            GOAT SUCKER.msnx
            2 routes · 91 points
            Library [button: Open in Routes ]
          Threat files
        1
        crisis_alert
            THREATS_OCT.ths
            7 threats · listed under Threats
            lock This device only [button: Open in Threats ]
        upload_file
            Drop files here, or anywhere on the map
            .msnx · .LPS · .ths · KMZ · GeoTIFF · image + world file
    {"Map"}
      [button "Search a grid or lat/long": search ]
        MGRS target
      [text "16S GC 28864 55349"] [button "Go to this grid": arrow_forward ]
      [button "Map layers": layers ] [button "My location": my_location ]
      [button "Grid under the crosshair, 16S GC 28936 55440. Tap to copy": add 16S GC 28936 55440 ]
    {"LZ/PZ 1 tools"}
          LZ/PZ 1
          Aircraft, analysis and tools
      [button "Close the LZ/PZ 1 pane": close ]
      [button: Aircraft UH-60L — UH-60L Black Hawk arrow_drop_down ]
        76 m spacing · 100 kt [button "Manage aircraft profiles": Manage ]
        LZ/PZ analysis
        check_circle Analyzed. Planning graphics, export and save are unlocked.
      [button: refresh Re-analyze ]
            Slope map
          [button role=switch checked=false: ]
            LZ box
          [button role=switch checked=true: check ]
        LZ/PZ tools
        Places at the crosshair, or where you tap.
        [button: Draw LZ ] [button: Helo ] [button: PZ ] [button: Sector ] [button: Unit ] [button: L-GA ] [button: R-GA ]
        Export
        [button: Set capture area ] [button: Export LZ card ]
        Routes
      [button: route Sketch a route ]
      {role=menu "Import"}
      [button role=menuitem: route Mission file .msnx · AMPS mission ] [button role=menuitem: scatter_plot Local points .LPS · AMPS local points ] [button role=menuitem: crisis_alert Threats .ths · stays on this device lock ] [button role=menuitem: layers Map or overlay KMZ · GeoTIFF · image + world file ]
      touch_app Or drag files onto the map, or open a file in another app and choose EZ/PZ.
```
