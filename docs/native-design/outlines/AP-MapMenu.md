# Android phone · Map menu

- Platform: Android phone, 412 x 915 dp
- Screenshot: `../png/AP-MapMenu.png`  ·  Source: `../screens/AP-MapMenu.dc.html`
- Web screen it adapts: 01 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    [button "Search a grid or lat/long": search ]
      MGRS target
    [text "16S GC 28864 55349"] [button "Account, Colin McFadden": CM ]
    [button "Workspace: Personal. Change workspace": person Personal arrow_drop_down ] [button: file_open Import ] [button: collections_bookmark Library ]
    [button "Map layers": layers ] [button "My location": my_location ]
    [button "Grid under the crosshair, 16S GC 28864 55349. Tap to copy": add 16S GC 28864 55349 ]
    {"LZ/PZ panel, lowered"}
          LZ/PZ
          3 open · this session
      [button "Expand the LZ/PZ panel": expand_less ]
    {"Panels"}
    [button current: flight_land LZ/PZ ] [button: route Routes ] [button "Threats, 1 on the map": crisis_alert 1 Threats ] [button "Imports, 3 items": move_to_inbox 3 Imports ]
    {role=menu "Long-press menu for 16S GC 28788 55432"}
      location_on 16S GC 28788 55432
    [button role=menuitem: add_location_alt New LZ/PZ here Opens another LZ/PZ with this target ] [button role=menuitem: crisis_alert Add threat here ] [button role=menuitem: route Add route point here ] [button role=menuitem: content_copy Copy grid ]
```
