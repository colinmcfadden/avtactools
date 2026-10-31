# iPad · Switcher

- Platform: iOS iPad, 1210 x 834 pt, landscape
- Screenshot: `../png/IT-Switcher.png`  ·  Source: `../screens/IT-Switcher.dc.html`
- Web screen it adapts: 11 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    EZ/PZ, workspace switcher
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
      [button current: LZ/PZ ] [button: Routes ] [button "Threats, 1 on the map": Threats 1 ] [button "Imports, 3 items": Imports 3 ]
            Open 3
          this session
          {current}
              LZ/PZ 1
            [button "Rename LZ/PZ 1": ]
            16S GC 28864 55349
            Analyzed Unsaved changes
            [button: Save ] [button "3D view of LZ/PZ 1": 3D ] [button "More actions for LZ/PZ 1": ]
            Saves to your Library ⌘S
          [button: LZ HAWK (ROCK) 16S GC 31204 58811 Analyzed Saved · Oct 3 ] [button "3D view of LZ HAWK (ROCK)": 3D ]
          [button: LZ/PZ 3 16S GC 27390 54102 Target set Analyze to save ] [button "3D view of LZ/PZ 3": 3D ]
          Set a new target in the search field, or long-press the map, to open another LZ/PZ.
            Recent in Library
          [button: Browse all ]
                  LZ HAWK (SHOPE)
                  Updated Aug 25 · 17:34
              [button "Open LZ HAWK (SHOPE)": Open ]
                  LZ RAZORBILL
                  Updated Aug 25 · 17:27
              [button "Open LZ RAZORBILL": Open ]
                  PZ OAK (KJZP)
                  Updated Aug 25 · 17:02
              [button "Open PZ OAK (KJZP)": Open ]
    {"Inspector, LZ/PZ 1"}
        LZ/PZ 1
      [button "Close inspector": ]
            Aircraft
          [button: Manage ]
          [button: UH-60L — UH-60L Black Hawk 76 m spacing · 100 kt ]
            LZ/PZ analysis
              Analyzed. Planning graphics, export and save are unlocked.
          [button: Re-analyze ]
            Slope map [button role=switch checked=false: ]
            LZ box [button role=switch checked=true: ]
            LZ/PZ tools
          [button: Draw LZ ] [button: Helo ] [button: PZ ] [button: Sector ] [button: Unit ] [button: L-GA ] [button: R-GA ]
          Places at the crosshair or where you tap.
            Export
          [button: Set capture area ] [button: Export LZ card ]
            Routes
        [button: Sketch a route ]
    {role=dialog "Workspace"}
          Alex Park invited you to OP IBIS as Editor
        [button "Decline the invitation to OP IBIS": Decline ] [button "Accept the invitation to OP IBIS": Accept ]
          Workspace
        [button current: Library Personal · 12 LZ/PZs, 4 route sets, 3 point sets 1 LZ/PZ with unsaved changes ]
        Personal workspace · saved items go to your Library
          Mission Packs
        [button: New pack ]
        [button: OP DK 4 members · you are Owner 2 here now · 3 LZ/PZ · 2 routes ]
        [button: B CO FIELD EX 18 members · you are Editor ]
        [button: OP RAZORBILL Finished Sep 22 · read-only ]
      B Co 2-10 AVN and 1 other team [button: Manage teams ]
```
