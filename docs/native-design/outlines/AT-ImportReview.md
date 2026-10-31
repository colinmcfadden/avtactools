# Android tablet · Import review

- Platform: Android tablet, 1280 x 800 dp, landscape
- Screenshot: `../png/AT-ImportReview.png`  ·  Source: `../screens/AT-ImportReview.dc.html`
- Web screen it adapts: 08 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    {"Navigation rail"}
    [button "Workspace: OP DK, a Mission Pack. Change workspace": groups OP DK arrow_drop_down ] [button: file_open Import ]
      [button: inventory_2 Pack ] [button: flight_land LZ/PZ ] [button: route Routes ] [button "Threats, 1 on the map": crisis_alert 1 Threats ] [button "Imports, 5 items" current: move_to_inbox 5 Imports ]
    [button: collections_bookmark Library ] [button "Account, Colin McFadden": CM ]
    {"Imports panel"}
          Imports
          5 items on the map
      [button "Collapse panel": left_panel_close ]
      {role=group "People here now in OP DK: CM, SB, JR and 1 more"}
      CM SB JR +1 Live · all changes synced
      upload_file
          Drag files here from another app, or tap Import
          .msnx · .LPS · .ths · KMZ · GeoTIFF · image + world file
          Local points
        2
        scatter_plot
            NORTH GA POINTS
            1,234 points groups OP DK
        [button "Hide NORTH GA POINTS": visibility ] [button "More actions for NORTH GA POINTS": more_vert ]
        scatter_plot
            KGVL TAXI POINTS
            46 points This session
        [button "Show KGVL TAXI POINTS": visibility_off ] [button "More actions for KGVL TAXI POINTS": more_vert ]
          Map overlays
        1
        image
            KJZP_CHART.tif
            GeoTIFF · 2.4 MB This session
        [button "Hide KJZP_CHART.tif": visibility ] [button "More actions for KJZP_CHART.tif": more_vert ]
          Mission files
        1
        route
            GOAT SUCKER.msnx
            2 routes · 91 points · This session
        [button: Open in Routes ]
          Threat files
        1
        crisis_alert
            THREATS_OCT.ths
            7 threats · this device only
        [button: Open in Threats ]
    {"Map"}
      [button "Search a grid or lat/long": search ]
        MGRS target
      [text "16S GC 28864 55349"] [button "Go to this grid": arrow_forward ]
      [button "Map layers": layers ] [button "My location": my_location ]
      [button "Grid under the crosshair, 16S GC 28864 55349. Tap to copy": add 16S GC 28864 55349 ]
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
    {role=dialog "Import 4 files"}
            Import 4 files
          groups Working in OP DK
          We worked out what each file is. Choose where it should live.
      [button "Close": close ]
      File Where it goes
        scatter_plot
            NORTH_GA_POINTS.LPS Local points
            1,234 points · 82 KB
              Name
            [text "NORTH GA POINTS"]
          {role=group "Where NORTH_GA_POINTS.LPS goes"}
          [button: check OP DK ] [button: collections_bookmark Library ] [button: schedule This session ]
          groups Everyone in OP DK (4 members) will see these points.
        image
            KJZP_CHART.tif Map overlay
            GeoTIFF · 2.4 MB · georeferenced
        schedule This session
          Overlays stay in this session for now.
        crisis_alert
            THREATS_OCT.ths Threats
            7 threats · listed under Threats
        lock This device only
          Threats stay on this device only, in an encrypted file that is wiped 48 hours after your last change and when you sign out. They are never saved to your account or shared with a Mission Pack.
        route
            GOAT_SUCKER.msnx Mission file
            2 routes · 91 points · 1.5 MB
          {role=group "Where GOAT_SUCKER.msnx goes"}
          [button disabled: groups OP DK ] [button: collections_bookmark Library ] [button: check This session ]
          info Mission files cannot go into a pack yet. Sketched routes only.
      block
          notes.docx
          Not supported. Nothing will be imported from this file.
      [button "Remove notes.docx from the list": close ]
      1 to OP DK · 2 stay in this session · 1 on this device only [button: Cancel ] [button: download Import 4 files ]
```
