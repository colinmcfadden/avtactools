# iPad · Import review

- Platform: iOS iPad, 1210 x 834 pt, landscape
- Screenshot: `../png/IT-ImportReview.png`  ·  Source: `../screens/IT-ImportReview.dc.html`
- Web screen it adapts: 08 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    EZ/PZ, import review in Mission Pack OP DK
      {role=search "MGRS target"}
        MGRS target
      [text "16S GC 28864 55349"] [button: Go ]
          {"Here now in OP DK"}
            CM
            SB
            JR
            +1
        Live · all changes synced
        [button "Import": ] [button "Library": ]
      [button "Account, Colin McFadden": CM ]
      [button "Map layers": ] [button "My location": ]
      [button "Grid under the crosshair, 16S GC 28864 55349. Tap to copy": 16S GC 28864 55349 ]
    {"Sidebar"}
      [button "Hide sidebar": ] [button "Workspace: Mission Pack OP DK. Change workspace": OP DK ]
      {"Panels"}
      [button: Pack ] [button: LZ/PZ ] [button: Routes ] [button "Threats, 1 on the map": Threats 1 ] [button "Imports, 5 items" current: Imports 5 ]
            5 items on the map
          [button: Import ]
              Drag files here or onto the map
              .msnx · .LPS · .ths · KMZ · GeoTIFF · image + world file
          Local points 2
                  NORTH GA POINTS
                  1,234 points OP DK
              [button "Hide NORTH GA POINTS": ] [button "More actions for NORTH GA POINTS": ]
                  KGVL TAXI POINTS
                  46 points · This session
              [button "Show KGVL TAXI POINTS": ] [button "More actions for KGVL TAXI POINTS": ]
          Map overlays 1
                  KJZP_CHART.tif
                  GeoTIFF · 2.4 MB · georeferenced
                  This session · Opacity 60%
              [button "Hide KJZP_CHART.tif": ] [button "More actions for KJZP_CHART.tif": ]
          Mission files 1
              GOAT SUCKER.msnx
              2 routes · 91 points · This session
            [button: Open in Routes ]
          Threat files 1
              THREATS_OCT.ths
              7 threats · listed under Threats · This device only
            [button: Open in Threats ]
    {role=dialog}
      [button "Cancel": ]
        Import 4 files
        We worked out what each file is. Choose where it should live.
      Working in OP DK · 4 members
      {"Files to import"}
                NORTH_GA_POINTS.LPS
              Local points
              1,234 points · 82 KB
            Where NORTH_GA_POINTS.LPS goes
              [button: OP DK ] [button: Library ] [button: Session ]
            Name
          [text "NORTH GA POINTS"]
          Everyone in OP DK (4 members) will see these points.
                KJZP_CHART.tif
              Map overlay
              GeoTIFF · 2.4 MB · georeferenced
            Goes to: This session
          Overlays stay in this session for now.
                THREATS_OCT.ths
              Threats
              7 threats
            Goes to: This device only
          Threats stay on this device only, in an encrypted file that is wiped 48 hours after your last change and when you sign out. They are never saved to your account or shared with a Mission Pack.
                GOAT_SUCKER.msnx
              Mission file
              2 routes · 91 points · 1.5 MB
            Where GOAT_SUCKER.msnx goes
              [button disabled: OP DK ] [button: Library ] [button: Session ]
          Mission files cannot go into a pack yet. Sketched routes only.
                notes.docx
              Not supported
              Nothing will be imported from this file.
          [button "Remove notes.docx from the list": ]
        1 to OP DK · 3 stay on this iPad
      [button: Import 4 files ]
```
