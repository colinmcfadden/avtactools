# iPhone · Save Routes

- Platform: iOS iPhone, 393 x 852 pt
- Screenshot: `../png/IP-SaveRoutes.png`  ·  Source: `../screens/IP-SaveRoutes.dc.html`
- Web screen it adapts: 05 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
  [button "Workspace: Personal. Change workspace": Personal ]
      {role=group "Import and Library"}
      [button "Import a file": ] [button "Library": ]
    [button "Account, Colin McFadden": CM ]
    {role=group "Map controls"}
    [button "Map layers": ] [button "My location": ]
    {role=dialog "Save routes"}
      [button: Cancel ]
        Save Routes
      [button: Save ]
      Saves the sketched routes with their plans.
        Name
        ROUTE 1 [text "ROUTE 1"] [button "Clear the name": ]
        You can rename it any time.
        What Is Saved
          1 route, 4 points, 4.2 nm
          Speeds, altitudes, winds, fuel flow and the start time
          Threats are never saved. Export them as a .ths if you need them next to the mission.
        Saves to your Library
```
