# iPhone · LZ/PZ

- Platform: iOS iPhone, 393 x 852 pt
- Screenshot: `../png/IP-Home.png`  ·  Source: `../screens/IP-Home.dc.html`
- Web screen it adapts: 01 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
  [button "Workspace: Personal. Change workspace": Personal ]
      {role=group "Import and Library"}
      [button "Import a file": ] [button "Library": ]
    [button "Account, Colin McFadden": CM ]
    {role=group "Map controls"}
    [button "Map layers": ] [button "My location": ]
    [button "Grid under the crosshair, 16S GC 28864 55349. Tap to copy": 16S GC 28864 55349 ]
    {"LZ/PZ panel"}
        MGRS target
      [text "16S GC 28864 55349"]
            LZ/PZ
            3 open · this session
        [button "Expand the LZ/PZ panel": ]
            LZ/PZ 1
          [button "Rename LZ/PZ 1": ] [button "More actions for LZ/PZ 1": ]
          16S GC 28864 55349
          Analyzed Unsaved changes
          [button: Save ] [button "Open LZ/PZ 1 in 3D": 3D ] Saves to your Library
        Also Open
          [button: LZ HAWK (ROCK) 16S GC 31204 58811 Analyzed Saved · Oct 3 ] [button "Open LZ HAWK (ROCK) in 3D": 3D ]
          [button: LZ/PZ 3 16S GC 27390 54102 Target set Analyze to save ] [button "Open LZ/PZ 3 in 3D": 3D ]
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
        Set a new target in the search field, or long-press the map, to open another LZ/PZ.
    {"Panels"}
    [button current: LZ/PZ ] [button: Routes ] [button "Threats, 1 on the map": 1 Threats ] [button "Imports, 3 items": 3 Imports ]
```
