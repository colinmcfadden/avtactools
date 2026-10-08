# iPhone · Finished

- Platform: iOS iPhone, 393 x 852 pt
- Screenshot: `../png/IP-Finished.png`  ·  Source: `../screens/IP-Finished.dc.html`
- Web screen it adapts: 16 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
  [button "Workspace: Mission Pack OP DK, finished and read-only. Change workspace": OP DK Finished ]
      {role=group "Import and Library"}
      [button "Import a file": ] [button "Library": ]
    [button "Account, Colin McFadden": CM ]
    {role=group "Map controls"}
    [button "Map layers": ] [button "My location": ]
    [button "Grid under the crosshair, 16S GC 26318 55907. Tap to copy": 16S GC 26318 55907 ]
    {"LZ/PZ panel"}
            LZ/PZ
            OP DK · finished · read-only
        [button "Expand the LZ/PZ panel": ]
        {"Finished pack"}
            {role=status}
            OP DK was finished by Colin McFadden on Oct 5 at 14:20. It is read-only for everyone.
        [button: Save a copy to Library ]
          [button: Duplicate as new pack ] [button "Reopen pack. Only the owner can reopen it": Reopen pack ]
            LZ IBIS (OWEST)
          [button "More actions for LZ IBIS (OWEST)": ]
          16S GC 26318 55907
          Analyzed Read-only
          [button "Open LZ IBIS (OWEST) in 3D": 3D ] [button "Save a copy of LZ IBIS (OWEST) to your Library": Save a copy ]
          Finished by Colin M. on Oct 5 at 14:20
        Also in OP DK
          [button: LZ HAWK (ROCK) 16S GC 31204 58811 Analyzed From Library ] [button "Open LZ HAWK (ROCK) in 3D": 3D ]
          [button: LZ BLUEBIRD (TWMILE) 16S GC 28110 52476 Analyzed ] [button "Open LZ BLUEBIRD (TWMILE) in 3D": 3D ]
        Planning tools and the map's long-press menu are off in a finished pack. You can still view, open in 3D and export from More. Save a copy to change anything.
    {"Panels"}
    [button "Pack, OP DK, finished": Pack ] [button current: LZ/PZ ] [button: Routes ] [button "Threats, 2 on the map": 2 Threats ] [button "Imports, 5 items": 5 Imports ]
```
