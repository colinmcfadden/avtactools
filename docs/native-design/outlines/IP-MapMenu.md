# iPhone · Map menu

- Platform: iOS iPhone, 393 x 852 pt
- Screenshot: `../png/IP-MapMenu.png`  ·  Source: `../screens/IP-MapMenu.dc.html`
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
    {"LZ/PZ panel"}
        MGRS target
      [text "16S GC 28864 55349"]
    {"Panels"}
    [button current: LZ/PZ ] [button: Routes ] [button "Threats, 1 on the map": 1 Threats ] [button "Imports, 3 items": 3 Imports ]
    [button "Grid under the crosshair, 16S GC 28864 55490. Tap to copy": 16S GC 28864 55490 ]
    {role=menu "Map point 16S GC 28788 55432"}
      16S GC 28788 55432
    [button role=menuitem: New LZ/PZ here Sets this grid as its target ]
    [button role=menuitem: Add threat here Stays on this device only ]
    [button role=menuitem: Add route point here ]
    [button role=menuitem: Copy grid ]
```
