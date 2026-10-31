# iPhone · LZ/PZ plan

- Platform: iOS iPhone, 393 x 852 pt
- Screenshot: `../png/IP-LzPlan.png`  ·  Source: `../screens/IP-LzPlan.dc.html`
- Web screen it adapts: 01 (see README, Screens)
- Interactive prototype: yes (state in the DCLogic script at the end of the source)

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
  [button "Workspace: Personal. Change workspace": Personal ]
      {role=group "Import and Library"}
      [button "Import a file": ] [button "Library": ]
    [button "Account, Colin McFadden": CM ]
    {"LZ/PZ 1 plan"}
          LZ/PZ 1
        [button "Rename LZ/PZ 1": ] [button "More actions for LZ/PZ 1": ] [button "Collapse the LZ/PZ panel": ]
        16S GC 28864 55349
        Analyzed Unsaved changes
        [button: Save ] [button "Open LZ/PZ 1 in 3D": 3D ] Saves to your Library
          Aircraft
        [button "Manage aircraft": Manage ]
        [button: UH-60L — UH-60L Black Hawk 76 m spacing · 100 kt ]
          LZ/PZ Analysis
        [button: Re-analyze ]
        [button role=switch checked=false: Slope map ] [button role=switch checked=true: Slope map ]
        [button role=switch checked=true: LZ box ] [button role=switch checked=false: LZ box ]
          Analyzed. Planning graphics, export and save are unlocked.
        LZ/PZ Tools
        [button "Draw LZ boundary": Draw LZ ] [button "Helo: place a helicopter": Helo ] [button "PZ: place a PZ marker": PZ ] [button "Sector: place a sector of fire": Sector ] [button "Unit: place a unit": Unit ] [button "L-GA: place a left go-around": L-GA ] [button "R-GA: place a right go-around": R-GA ]
        Tools place at the crosshair. Done and Cancel finish the mode.
        Export
        [button: Set capture area ]
        [button: Export LZ card ]
        Routes
        [button: Sketch a route ]
        Personal workspace · saved items go to your Library
    {"Panels"}
    [button current: LZ/PZ ] [button: Routes ] [button "Threats, 1 on the map": 1 Threats ] [button "Imports, 3 items": 3 Imports ]
```
