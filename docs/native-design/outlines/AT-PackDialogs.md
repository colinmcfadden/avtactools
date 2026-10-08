# Android tablet · Pack dialogs

- Platform: Android tablet, 1280 x 800 dp, landscape
- Screenshot: `../png/AT-PackDialogs.png`  ·  Source: `../screens/AT-PackDialogs.dc.html`
- Web screen it adapts: 17 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
      {"Navigation rail"}
      [button "Workspace: OP DK, a Mission Pack. Change workspace": groups OP DK arrow_drop_down ] [button: file_open Import ]
        [button current: folder_shared Pack ] [button: flight_land LZ/PZ ] [button: route Routes ] [button "Threats, 1 on the map": crisis_alert 1 Threats ] [button "Imports, 3 items": move_to_inbox 3 Imports ]
      [button: collections_bookmark Library ] [button "Account, Colin McFadden": CM ]
      {"Pack panel"}
            OP DK
            Live · all changes synced
          JR CM
            Items
            LZ HAWK (ROCK)
            16S GC 31204 58811
          check Synced
            LZ IBIS (OWEST)
          check Synced
      {"Map"}
        [button "Search a grid or lat/long": search ]
          MGRS target
        [text "16S GC 28864 55349"] [button "Go to this grid": arrow_forward ]
        [button "Map layers": layers ] [button "My location": my_location ]
        [button "Grid under the crosshair, 16S GC 28864 55349. Tap to copy": add 16S GC 28864 55349 ]
      {"LZ HAWK (ROCK) tools"}
            LZ HAWK (ROCK)
            Aircraft, analysis and tools
        [button "Close the LZ HAWK (ROCK) pane": close ]
          Aircraft UH-60L — UH-60L Black Hawk
    A Copy a Library item into a pack
      {role=dialog "Add to a Mission Pack"}
        Add to a Mission Pack
        Adding “LZ HAWK (SHOPE)”.
        {role=radiogroup "Mission Pack"}
        [button role=radio checked=true: OP DK 4 members · you can edit groups ] [button role=radio checked=false: B CO FIELD EX 18 members · you can edit groups ] [button role=radio checked=false: OP RAZORBILL Finished Sep 22. Reopen it to add items. lock ]
        A copy goes into the pack. Your Library item stays as it is, and you can update the copy from it later.
        [button: Cancel ] [button: Add copy ]
    C Finishing (owner only)
      {role=dialog "Finish OP DK?"}
        Finish OP DK?
          lock Everyone becomes read-only, you included.
          visibility Members can still view, export and save copies to their own Library.
          lock_open You can reopen the pack whenever you need to.
        [button: Cancel ] [button: Finish pack ]
    B Update the copy from its original
      {role=dialog "Update LZ HAWK (ROCK) from Library?"}
        Update “LZ HAWK (ROCK)” from Library?
        Your Library version changed Oct 3 at 09:12. Updating replaces the pack copy with it.
        warning
          3 changes made in the pack will be replaced , including Jess R.’s landing heading of 270°. The history keeps what was there.
        [button: Cancel ] [button: Update from original ]
    D Keep a pack item after it is finished
      {role=dialog "Save a copy to your Library"}
        Save a copy to your Library
        From groups OP DK.
          Name
        [text "LZ IBIS (OWEST) copy"]
        Only you can see this copy. Later changes in the pack do not update it.
        folder Saves to your Library [button: Cancel ] [button: Save copy ]
```
