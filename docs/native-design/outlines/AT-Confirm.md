# Android tablet · Confirm

- Platform: Android tablet, 1280 x 800 dp, landscape
- Screenshot: `../png/AT-Confirm.png`  ·  Source: `../screens/AT-Confirm.dc.html`
- Web screen it adapts: 18 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    {"Navigation rail"}
    [button "Workspace: Personal. Change workspace": person Personal arrow_drop_down ] [button: file_open Import ]
      [button "LZ/PZ, unsaved changes" current: flight_land LZ/PZ ] [button "Routes, unsaved changes": route Routes ] [button "Threats, 1 on the map": crisis_alert 1 Threats ] [button "Imports, 3 items": move_to_inbox 3 Imports ]
    [button: collections_bookmark Library ] [button "Account, Colin McFadden": CM ]
    {"LZ/PZ panel"}
          LZ/PZ
          3 open · this session
      [button "Collapse panel": left_panel_close ]
          Open
        3
            LZ/PZ 1
          [button "Rename LZ/PZ 1": edit ]
          16S GC 28864 55349
          check_circle Analyzed Unsaved changes
          [button: save Save ] [button: view_in_ar 3D ] [button "More actions for LZ/PZ 1": more_vert ]
          folder Saves to your Library
        [button: LZ HAWK (ROCK) 16S GC 31204 58811 check_circle Analyzed check Saved · Oct 3 ] [button "Open LZ HAWK (ROCK) in 3D": view_in_ar ]
        [button: LZ/PZ 3 16S GC 27390 54102 Target set lock Analyze to save ] [button "Open LZ/PZ 3 in 3D": view_in_ar ]
          Recent in Library
        [button: Browse all ]
        hexagon
            LZ HAWK (SHOPE)
            Updated Aug 25 · 17:34
        [button "Open LZ HAWK (SHOPE)": Open ]
        hexagon
            LZ RAZORBILL
            Updated Aug 25 · 17:27
        [button "Open LZ RAZORBILL": Open ]
        hexagon
            PZ OAK (KJZP)
            Updated Aug 25 · 17:02
        [button "Open PZ OAK (KJZP)": Open ]
    {"Map"}
      [button "Search a grid or lat/long": search ]
        MGRS target
      [text "16S GC 28864 55349"] [button "Go to this grid": arrow_forward ]
      [button "Map layers": layers ] [button "My location": my_location ]
      [button "Grid under the crosshair, 16S GC 28864 55349. Tap to copy": add 16S GC 28864 55349 ]
    {"LZ/PZ 1 tools"}
          LZ/PZ 1
          Aircraft, analysis and tools
      [button "Close the LZ/PZ 1 pane": close ]
      [button: Aircraft UH-60L — UH-60L Black Hawk arrow_drop_down ]
        76 m spacing · 100 kt [button "Manage aircraft profiles": Manage ]
        LZ/PZ analysis
        check_circle Analyzed. Planning graphics, export and save are unlocked.
      [button: refresh Re-analyze ]
        Export
        [button: Set capture area ] [button: Export LZ card ]
    A · Name already used
    {role=dialog "Save LZ/PZ, name already used"}
      Save LZ/PZ
      Name is already used.
        Name
      [text "LZ HAWK (SHOPE)"]
      You already have an LZ/PZ with this name, last saved Aug 25 at 17:34.
      {role=radiogroup "Keep both or replace the saved one"}
      [button role=radio checked=true: Keep both Save this one as “LZ HAWK (SHOPE) 2”. ] [button role=radio checked=false: Replace the saved one Overwrite it with this version. The old version is not kept. ]
      [button: Cancel ] [button: Save as new ]
    B · Closing with unsaved changes
    {role=dialog "Save changes to LZ/PZ 1?"}
      Save changes to LZ/PZ 1?
      You have unsaved changes. If you close it now they are lost.
      [button "Don’t save, lose the changes to LZ/PZ 1": Don’t save ] [button: Cancel ] [button: Save ]
    {"How these dialogs behave on Android"}
      On Android
        close Material 3 dialogs with text buttons. The web’s close button is not drawn: Cancel, Back or a tap outside closes the dialog and changes nothing.
        warning A destructive choice is a text button in the error colour, never the prominent one, and the dialog names what it affects.
        swap_horiz In B, Don’t save sits apart at the start of the row, away from Save.
    C · Deleting from the Library
    {role=dialog "Delete LZ HAWK (SHOPE)?"}
      Delete “LZ HAWK (SHOPE)”?
      This removes it from your Library on every device you use. Copies you added to a Mission Pack are not affected.
      [button: Cancel ] [button "Delete LZ HAWK (SHOPE) from your Library": Delete ]
    D · Removing every threat
    {role=dialog "Remove all threats?"}
      Remove all threats?
      2 threats come off the map and are wiped from this device. Threats are never saved to your account, so they cannot be brought back.
      [button: Cancel ] [button "Remove all 2 threats": Remove all ]
```
