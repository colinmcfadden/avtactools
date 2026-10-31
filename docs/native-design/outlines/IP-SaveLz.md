# iPhone · Save LZ/PZ

- Platform: iOS iPhone, 393 x 852 pt
- Screenshot: `../png/IP-SaveLz.png`  ·  Source: `../screens/IP-SaveLz.dc.html`
- Web screen it adapts: 04 (see README, Screens)
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
              LZ/PZ 1
            16S GC 28864 55349
            Analyzed Unsaved changes
            Save Saves to your Library
      {"Panels"}
      [button current: LZ/PZ ] [button: Routes ] [button "Threats, 1 on the map": 1 Threats ] [button "Imports, 3 items": 3 Imports ]
    {role=dialog}
        Save LZ/PZ
      [button: Cancel ] [button: Save ]
        Name this LZ/PZ so you can find it in your Library.
        Name
        [text "LZ/PZ 1"] [button "Clear name": ]
        You can rename it any time.
        What Is Saved
        {role=list}
          Included: Boundary, target 16S GC 28864 55349 and slope analysis
          Included: 3 helicopters, 2 sectors, doghouses and flight data
          Not included: Threats and routes are not included. Routes are saved on their own.
        Saves to your Library
```
