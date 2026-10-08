# Android phone · Switcher

- Platform: Android phone, 412 x 915 dp
- Screenshot: `../png/AP-Switcher.png`  ·  Source: `../screens/AP-Switcher.dc.html`
- Web screen it adapts: 11 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    [button "Search a grid or lat/long": search ]
      MGRS target
    [text "16S GC 28864 55349"] [button "Account, Colin McFadden": CM ]
    [button "Workspace: Personal. Change workspace": person Personal arrow_drop_up ] [button: file_open Import ] [button: collections_bookmark Library ]
    [button "Map layers": layers ] [button "My location": my_location ]
    [button "Grid under the crosshair, 16S GC 28864 55349. Tap to copy": add 16S GC 28864 55349 ]
    {"LZ/PZ panel"}
          LZ/PZ
          3 open · this session
      [button "Open the LZ/PZ list full height": expand_less ]
    {"Panels"}
    [button current: flight_land LZ/PZ ] [button: route Routes ] [button "Threats, 1 on the map": crisis_alert 1 Threats ] [button "Imports, 3 items": move_to_inbox 3 Imports ]
    {role=dialog "Switch workspace"}
        Switch workspace
      [button "Close workspace switcher": close ]
        {role=group "Pending invitation"}
          group_add
            Alex Park invited you to OP IBIS as Editor
          [button "Decline the invitation to OP IBIS": Decline ] [button "Accept the invitation to OP IBIS": check Accept ]
      Workspace
      [button current: collections_bookmark Library Personal · 12 LZ/PZs, 4 route sets, 3 point sets 1 LZ/PZ with unsaved changes check ]
        Mission Packs
      [button: add New pack ]
    [button: groups OP DK 4 members · you are Owner 2 here now · 3 LZ/PZ · 2 routes ] [button: groups B CO FIELD EX 18 members · you are Editor ] [button: groups OP RAZORBILL Finished Sep 22 · read-only lock ]
      diversity_3 B Co 2-10 AVN and 1 other team [button: Manage teams ]
```
