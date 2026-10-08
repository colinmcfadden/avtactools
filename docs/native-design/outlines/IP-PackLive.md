# iPhone · Pack live

- Platform: iOS iPhone, 393 x 852 pt
- Screenshot: `../png/IP-PackLive.png`  ·  Source: `../screens/IP-PackLive.dc.html`
- Web screen it adapts: 10 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
  [button "Workspace: OP DK, Mission Pack. Here now: you, Sam B., Jess R. and 1 other. Live, all changes synced. Change workspace": OP DK CM SB JR +1 ]
    Live · all changes synced
      {role=group "Import and Library"}
      [button "Import a file": ] [button "Library": ]
    [button "Account, Colin McFadden": CM ]
    {role=group "Map controls"}
    [button "Map layers": ] [button "My location": ]
    [button "Grid under the crosshair, 16S GC 26318 55907. Tap to copy": 16S GC 26318 55907 ]
    {"LZ/PZ panel"}
        MGRS target
      [text "16S GC 26318 55907"]
            LZ/PZ
            OP DK · 3 items · live
        [button "Expand the LZ/PZ panel": ]
            LZ IBIS (OWEST)
          [button "Rename LZ IBIS (OWEST)": ] [button "More actions for LZ IBIS (OWEST)": ]
          16S GC 26318 55907
          Analyzed Synced SB Sam B. is also here
          [button "Open LZ IBIS (OWEST) in 3D": 3D ] Saved to OP DK as you work. 4 members can see it.
        Also in OP DK
          [button: LZ HAWK (ROCK) 16S GC 31204 58811 Analyzed From Library Original changed ] [button "Open LZ HAWK (ROCK) in 3D": 3D ]
          [button: LZ BLUEBIRD (TWMILE) 16S GC 28110 52476 Analyzed JR Jess R. editing ] [button "Open LZ BLUEBIRD (TWMILE) in 3D": 3D ]
        New LZ/PZs you start while OP DK is open are added to it.
    {"Panels"}
    [button "Pack, OP DK": Pack ] [button current: LZ/PZ ] [button: Routes ] [button "Threats, 2 on the map": 2 Threats ] [button "Imports, 5 items": 5 Imports ]
```
