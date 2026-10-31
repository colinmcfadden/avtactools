# Android tablet · Threats

- Platform: Android tablet, 1280 x 800 dp, landscape
- Screenshot: `../png/AT-Threats.png`  ·  Source: `../screens/AT-Threats.dc.html`
- Web screen it adapts: 03 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    {"Navigation rail"}
    [button "Workspace: Personal. Change workspace": person Personal arrow_drop_down ] [button: file_open Import ]
      [button "LZ/PZ, unsaved changes": flight_land LZ/PZ ] [button "Routes, unsaved changes": route Routes ] [button "Threats, 2 on the map" current: crisis_alert 2 Threats ] [button "Imports, 3 items": move_to_inbox 3 Imports ]
    [button: collections_bookmark Library ] [button "Account, Colin McFadden": CM ]
    {"Threats panel"}
          Threats
          2 on the map · this device only
      [button "Collapse panel": left_panel_close ]
        encrypted Threats stay on this device only, in an encrypted file that is wiped 48 hours after your last change and when you sign out. They are never saved to your account or shared with a Mission Pack.
        [button: add Add threat ] [button: file_open Import .ths ]
        touch_app Long-press the map and choose Add threat here, or drag a .ths file onto the map.
          On the map
        2
            Threat 1
          Detect 25 nm · Engage 15 nm
          landscape Terrain mask on [button "Hide Threat 1": visibility ] [button "Edit Threat 1": edit ] [button "Remove Threat 1": delete ]
            Threat 2
          Detect 8 nm · Engage 5 nm
          adjust Rings only [button "Hide Threat 2": visibility ] [button "Edit Threat 2": edit ] [button "Remove Threat 2": delete ]
          Export
        [button "Export .ths for AMPS": download .ths for AMPS ] [button "Share KMZ to ATAK": share KMZ to ATAK ]
        The .ths file is built on this device. Showing a terrain mask or building a KMZ sends threat positions to the server once. Nothing is kept.
      [button: delete_sweep Remove all threats ]
    {"Map"}
      [button "Search a grid or lat/long": search ]
        MGRS target
      [text "16S GC 28864 55349"] [button "Go to this grid": arrow_forward ]
    [button "Open the LZ/PZ 1 pane": right_panel_open ]
      {"Actions at 16S GC 28571 55508"}
        location_on
            Pressed point
            16S GC 28571 55508
        [button: crisis_alert Add threat here ]
        [button: flight_land New LZ/PZ here ]
        [button: add_location_alt Add route point here ]
        [button: content_copy Copy grid ]
      [button "Map layers": layers ] [button "My location": my_location ]
      [button "Grid under the crosshair, 16S GC 28681 55409. Tap to copy": add 16S GC 28681 55409 ]
```
