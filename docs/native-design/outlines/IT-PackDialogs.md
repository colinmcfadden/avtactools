# iPad · Pack dialogs

- Platform: iOS iPad, 1210 x 834 pt, landscape
- Screenshot: `../png/IT-PackDialogs.png`  ·  Source: `../screens/IT-PackDialogs.dc.html`
- Web screen it adapts: 17 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    EZ/PZ, Mission Pack dialogs
        {role=search "MGRS target"}
          MGRS target
        [text "16S GC 28864 55349"] [button: Go ]
          [button "Import": ] [button "Library": ]
        [button "Account, Colin McFadden": CM ]
        [button "Map layers": ] [button "My location": ]
        [button "Grid under the crosshair, 16S GC 28864 55349. Tap to copy": 16S GC 28864 55349 ]
      {"Sidebar"}
        [button "Hide sidebar": ] [button "Workspace: Personal. Change workspace": Personal ]
        {"Panels"}
        [button current: LZ/PZ ] [button: Routes ] [button "Threats, 1 on the map": Threats 1 ] [button "Imports, 3 items": Imports 3 ]
              Open 3
            this session
            {current}
                LZ/PZ 1
              16S GC 28864 55349
              Analyzed Unsaved changes
            LZ HAWK (ROCK) 16S GC 31204 58811
            LZ/PZ 3 16S GC 27390 54102
      A Copy a Library item into a pack · Form sheet
      {role=dialog}
        [button: Cancel ]
          Add to a Mission Pack
        [button: Add copy ]
        Adding “LZ HAWK (SHOPE)” .
        {role=radiogroup "Mission Pack"}
        [button role=radio checked=true: OP DK 4 members · you can edit ]
        [button role=radio checked=false: B CO FIELD EX 18 members · you can edit ]
        [button role=radio checked=false: OP RAZORBILL Finished Sep 22. Reopen it to add items. ]
        A copy goes into the pack. Your Library item stays as it is, and you can update the copy from it later.
      B Update the copy from its original · Alert
      {role=dialog}
        Update “LZ HAWK (ROCK)” from Library?
          Your Library version changed Oct 3 at 09:12. Updating replaces the pack copy with it.
          3 changes made in the pack will be replaced , including Jess R.’s landing heading of 270°. The history keeps what was there.
        [button: Update from original ] [button: Cancel ]
      C Finishing (owner only) · Alert
      {role=dialog}
        Finish OP DK?
        Everyone becomes read-only, you included. Members can still view, export and save copies to their own Library. You can reopen the pack whenever you need to.
        [button: Cancel ] [button: Finish pack ]
      D Keep a pack item after it is finished · Form sheet
      {role=dialog}
        [button: Cancel ]
          Save a copy to your Library
        [button: Save copy ]
        From OP DK .
          Name
        [text "LZ IBIS (OWEST) copy"] [button "Clear name": ]
        Only you can see this copy. Later changes in the pack do not update it.
        Saves to your Library
```
