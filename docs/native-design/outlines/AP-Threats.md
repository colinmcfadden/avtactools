# Android phone · Threats

- Platform: Android phone, 412 x 915 dp
- Screenshot: `../png/AP-Threats.png`  ·  Source: `../screens/AP-Threats.dc.html`
- Web screen it adapts: 03 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    [button "Search a grid or lat/long": search ]
      MGRS target
    [text "16S GC 28864 55349"] [button "Account, Colin McFadden": CM ]
    [button "Workspace: Personal. Change workspace": person Personal arrow_drop_down ] [button: file_open Import ] [button: collections_bookmark Library ]
    [button "Map layers": layers ] [button "My location": my_location ]
    [button "Grid under the crosshair, 16S GC 28696 55425. Tap to copy": add 16S GC 28696 55425 ]
    {"Threats panel"}
          Threats
          2 on the map · this device only
      [button "Open the Threats panel full height": expand_less ]
          lock
            Threats stay on this device only , in an encrypted file that is wiped 48 hours after your last change and when you sign out. They are never saved to your account or shared with a Mission Pack.
          [button: add_location_alt Add threat ] [button: file_open Import .ths ]
          Add threat puts one at the crosshair. You can also long-press the map and choose Add threat here.
        On the map · 2
            Threat 1
            Detect 25 nm · Engage 15 nm
            landscape Terrain mask on
        [button "Hide Threat 1": visibility ] [button "Edit Threat 1": edit ] [button "Remove Threat 1": delete ]
            Threat 2
            Detect 8 nm · Engage 5 nm
            Rings only
        [button "Hide Threat 2": visibility ] [button "Edit Threat 2": edit ] [button "Remove Threat 2": delete ]
        Export
        [button: share .ths for AMPS ] [button: send Send KMZ to ATAK ]
        info
          The .ths file is built on this device. Showing a terrain mask or building a KMZ sends threat positions to the server once. Nothing is kept.
        [button: delete_sweep Remove all threats ]
    {"Panels"}
    [button: flight_land LZ/PZ ] [button: route Routes ] [button "Threats, 2 on the map" current: crisis_alert 2 Threats ] [button "Imports, 3 items": move_to_inbox 3 Imports ]
```
