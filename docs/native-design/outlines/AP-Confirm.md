# Android phone · Confirm

- Platform: Android phone, 412 x 915 dp
- Screenshot: `../png/AP-Confirm.png`  ·  Source: `../screens/AP-Confirm.dc.html`
- Web screen it adapts: 18 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    A · Name already used
    B · Closing with unsaved changes
    C · Deleting from the Library
    D · Removing every threat
    {"A, name already used"}
        [button "Search a grid or lat/long": search ] 16S GC 28864 55349 [button "Account, Colin McFadden": CM ]
        [button "Workspace: Personal. Change workspace": person Personal arrow_drop_down ] [button: file_open Import ] [button: collections_bookmark Library ]
        [button "Map layers": layers ] [button "My location": my_location ]
        [button "Grid under the crosshair, 16S GC 28864 55349. Tap to copy": add 16S GC 28864 55349 ]
              LZ/PZ
              3 open · this session
          [button "Open the LZ/PZ list full height": expand_less ]
                LZ/PZ 1
              [button "Rename LZ/PZ 1": edit ] [button "More actions for LZ/PZ 1": more_vert ]
              16S GC 28864 55349
              check_circle Analyzed Unsaved changes
              [button: save Save ] [button: view_in_ar 3D ] Saves to your Library
          Also open
          flight_land
              LZ HAWK (ROCK)
              16S GC 31204 58811 · Saved · Oct 3
          [button "Open LZ HAWK (ROCK) in 3D": view_in_ar ]
        [button current: flight_land LZ/PZ ] [button: route Routes ] [button "Threats, 1 on the map": crisis_alert 1 Threats ] [button "Imports, 3 items": move_to_inbox 3 Imports ]
        {role=dialog "Save LZ/PZ. Name is already used"}
          Save LZ/PZ
            Name
          [text "LZ HAWK (SHOPE)"] error
          Name is already used.
          You already have an LZ/PZ with this name, last saved Aug 25 at 17:34.
          How to save it
            [radio "keep" checked] Keep both Save this one as “LZ HAWK (SHOPE) 2”.
            [radio "replace"] Replace the saved one Overwrite it with this version. The old version is not kept.
          [button: Cancel ] [button: Save as new ]
    {"B, closing with unsaved changes"}
        [button "Search a grid or lat/long": search ] 16S GC 28864 55349 [button "Account, Colin McFadden": CM ]
        [button "Workspace: Personal. Change workspace": person Personal arrow_drop_down ] [button: file_open Import ] [button: collections_bookmark Library ]
        [button "Map layers": layers ] [button "My location": my_location ]
        [button "Grid under the crosshair, 16S GC 28864 55349. Tap to copy": add 16S GC 28864 55349 ]
              LZ/PZ
              3 open · this session
          [button "Open the LZ/PZ list full height": expand_less ]
                LZ/PZ 1
              [button "Rename LZ/PZ 1": edit ] [button "Close LZ/PZ 1": close ]
              16S GC 28864 55349
              check_circle Analyzed Unsaved changes
              [button: save Save ] [button: view_in_ar 3D ] Saves to your Library
          Also open
          flight_land
              LZ HAWK (ROCK)
              16S GC 31204 58811 · Saved · Oct 3
          [button "Open LZ HAWK (ROCK) in 3D": view_in_ar ]
        [button current: flight_land LZ/PZ ] [button: route Routes ] [button "Threats, 1 on the map": crisis_alert 1 Threats ] [button "Imports, 3 items": move_to_inbox 3 Imports ]
        {role=dialog "Save changes to LZ/PZ 1?"}
          Save changes to LZ/PZ 1?
          You have unsaved changes. If you close it now they are lost.
          [button: Don’t save ] [button: Cancel ] [button: Save ]
    {"C, deleting from the Library"}
        [button "Search a grid or lat/long": search ] 16S GC 28864 55349 [button "Account, Colin McFadden": CM ]
        [button "Workspace: Personal. Change workspace": person Personal arrow_drop_down ] [button: file_open Import ] [button: collections_bookmark Library ]
        [button "Map layers": layers ] [button "My location": my_location ]
        [button "Grid under the crosshair, 16S GC 28864 55349. Tap to copy": add 16S GC 28864 55349 ]
              LZ/PZ
              3 open · this session
          [button "Open the LZ/PZ list full height": expand_less ]
                LZ/PZ 1
              [button "Rename LZ/PZ 1": edit ] [button "More actions for LZ/PZ 1": more_vert ]
              16S GC 28864 55349
              check_circle Analyzed Unsaved changes
              [button: save Save ] [button: view_in_ar 3D ] Saves to your Library
          Also open
          flight_land
              LZ HAWK (ROCK)
              16S GC 31204 58811 · Saved · Oct 3
          [button "Open LZ HAWK (ROCK) in 3D": view_in_ar ]
        [button current: flight_land LZ/PZ ] [button: route Routes ] [button "Threats, 1 on the map": crisis_alert 1 Threats ] [button "Imports, 3 items": move_to_inbox 3 Imports ]
        {role=dialog "Delete LZ HAWK (SHOPE)?"}
          Delete “LZ HAWK (SHOPE)”?
          This removes it from your Library on every device you use. Copies you added to a Mission Pack are not affected.
          [button: Cancel ] [button: Delete ]
    {"D, removing every threat"}
        [button "Search a grid or lat/long": search ] 16S GC 28864 55349 [button "Account, Colin McFadden": CM ]
        [button "Workspace: Personal. Change workspace": person Personal arrow_drop_down ] [button: file_open Import ] [button: collections_bookmark Library ]
        [button "Map layers": layers ] [button "My location": my_location ]
              Threats
              2 on the map · this device only
          [button "Open the threat list full height": expand_less ]
            lock Threats stay on this device only, in an encrypted file that is wiped 48 hours after your last change and when you sign out. They are never saved to your account or shared with a Mission Pack.
          [button: file_open Import .ths ] [button: Remove all ]
        [button: flight_land LZ/PZ ] [button: route Routes ] [button "Threats, 2 on the map" current: crisis_alert 2 Threats ] [button "Imports, 3 items": move_to_inbox 3 Imports ]
        {role=dialog "Remove all threats?"}
          Remove all threats?
          2 threats come off the map. Threats are never saved, so they cannot be brought back.
          [button: Cancel ] [button: Remove all ]
```
