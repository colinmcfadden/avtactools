# Android phone · Import

- Platform: Android phone, 412 x 915 dp
- Screenshot: `../png/AP-Import.png`  ·  Source: `../screens/AP-Import.dc.html`
- Web screen it adapts: 07 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    [button "Search a grid or lat/long": search ]
      MGRS target
    [text "16S GC 28864 55349"] [button "Account, Colin McFadden": CM ]
    [button "Workspace: Personal. Change workspace": person Personal arrow_drop_down ] [button: file_open Import ] [button: collections_bookmark Library ]
    [button "Map layers": layers ] [button "My location": my_location ]
    [button "Grid under the crosshair, 16S GC 28864 55349. Tap to copy": add 16S GC 28864 55349 ]
    {"LZ/PZ panel"}
          LZ/PZ
          3 open · this session
      [button "Open the LZ/PZ list full height": expand_less ]
    {"Panels"}
    [button current: flight_land LZ/PZ ] [button: route Routes ] [button "Threats, 1 on the map": crisis_alert 1 Threats ] [button "Imports, 3 items": move_to_inbox 3 Imports ]
    {role=dialog "Import"}
      [button "Close Import": ]
        Import
        You check each file, and where it goes, before anything is added.
      [button: route Mission file .msnx · AMPS routes and points ] [button: scatter_plot Local points .LPS · AMPS point sets ] [button: crisis_alert Threats .ths · stays on this device lock ] [button: map Map or overlay KMZ, GeoTIFF, image with world file ]
      open_in_new
        Or open a file in Files, Gmail or Drive and choose EZ/PZ. We work out the type.
```
