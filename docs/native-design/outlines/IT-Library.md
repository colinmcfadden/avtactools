# iPad · Library

- Platform: iOS iPad, 1210 x 834 pt, landscape
- Screenshot: `../png/IT-Library.png`  ·  Source: `../screens/IT-Library.dc.html`
- Web screen it adapts: 06 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    EZ/PZ, Library
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
            [button: Draw LZ ] [button: Helo ] [button: PZ ] [button: Sector ]
    {role=dialog "Library"}
          Library
        [button "Close": ]
        Everything you have saved. Opening one adds it to this session.
        {role=tablist "Saved items"}
        [button role=tab: LZ/PZ 12 ] [button role=tab: Routes 4 ] [button role=tab: Local points 3 ]
      [button "Sort, Recently updated": Recently updated ]
      {role=search "Search the Library"}
        Search LZ/PZs by name or grid
      [search "Search LZ/PZs by name or grid"]
      Name Grid Updated
      {role=tabpanel "LZ/PZ"}
        {"Saved LZ/PZs, most recently updated first"}
                LZ HAWK (ROCK)
              Open in session
              Grid 16S GC 31204 58811
              Updated Oct 3 · 09:12
            [button "Open LZ HAWK (ROCK)": Open ] [button "More actions for LZ HAWK (ROCK)": ]
              LZ HAWK (SHOPE)
              Grid 16S GC 24118 57290
              Updated Aug 25 · 17:34
            [button "Open LZ HAWK (SHOPE)": Open ] [button "More actions for LZ HAWK (SHOPE)": ]
              LZ RAZORBILL
              Grid 16S GC 22906 55120
              Updated Aug 25 · 17:27
            [button "Open LZ RAZORBILL": Open ] [button "More actions for LZ RAZORBILL": ]
              LZ FALCON
              Grid 16S GC 27743 51866
              Updated Aug 25 · 17:16
            [button "Open LZ FALCON": Open ] [button "More actions for LZ FALCON": ]
              PZ OAK (KJZP)
              Grid 16S GC 33017 49304
              Updated Aug 25 · 17:02
            [button "Open PZ OAK (KJZP)": Open ] [button "More actions for PZ OAK (KJZP)": ]
              KMGE
              Grid 16S GC 20851 60177
              Updated Aug 18 · 17:40
            [button "Open KMGE": Open ] [button "More actions for KMGE": ]
              KGVL
              Grid 16S GC 35502 62248
              Updated Aug 18 · 17:29
            [button "Open KGVL": Open ] [button "More actions for KGVL": ]
              LZ HAWK (GFARM)
              Grid 16S GC 29180 56013
              Updated Aug 18 · 17:09
            [button "Open LZ HAWK (GFARM)": Open ] [button "More actions for LZ HAWK (GFARM)": ]
      12 saved LZ/PZs · [button: Working with a team? Open a Mission Pack ] ⌘L Library
```
