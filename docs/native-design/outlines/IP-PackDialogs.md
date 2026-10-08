# iPhone · Pack dialogs

- Platform: iOS iPhone, 393 x 852 pt
- Screenshot: `../png/IP-PackDialogs.png`  ·  Source: `../screens/IP-PackDialogs.dc.html`
- Web screen it adapts: 17 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
      A · Copy a Library item into a pack
      B · Update the copy from its original
      C · Finishing (owner only)
      D · Keep a pack item after it is finished
        [button "Workspace: Personal. Change workspace": Personal ]
            {role=group "Import and Library"}
            [button "Import a file": ] [button "Library": ]
          [button "Account, Colin McFadden": CM ]
          {role=group "Map controls"}
          [button "Map layers": ] [button "My location": ]
        {role=dialog "Add to a Mission Pack"}
              Add to a Mission Pack
              Adding “LZ HAWK (SHOPE)”.
          [button "Cancel": ]
          {role=radiogroup "Mission Pack to add the copy to"}
          [button role=radio checked=true: OP DK 4 members · you can edit ]
          [button role=radio checked=false: B CO FIELD EX 18 members · you can edit ]
          [button role=radio checked=false disabled: OP RAZORBILL Finished Sep 22. Reopen it to add items. ]
          A copy goes into the pack. Your Library item stays as it is, and you can update the copy from it later.
        [button: Add copy ]
        [button "Workspace: Mission Pack OP DK, Jess R. here now. Change workspace": OP DK JR ]
            {role=group "Import and Library"}
            [button "Import a file": ] [button "Library": ]
          [button "Account, Colin McFadden": CM ]
          {role=group "Map controls"}
          [button "Map layers": ] [button "My location": ]
          {"Pack panel"}
                  OP DK
                  Mission Pack · 4 members
              Items
                    LZ HAWK (ROCK)
                    16S GC 31204 58811
                    Synced
                  LZ IBIS (OWEST)
                Synced
          {"Panels"}
          [button current: Pack ] [button: LZ/PZ ] [button: Routes ] [button "Threats, 1 on the map": 1 Threats ] [button "Imports, 3 items": 3 Imports ]
          {role=dialog "Update “LZ HAWK (ROCK)” from Library?"}
            Update “LZ HAWK (ROCK)” from Library?
            Your Library version changed Oct 3 at 09:12. Updating replaces the pack copy with it.
            3 changes made in the pack will be replaced , including Jess R.’s landing heading of 270°. The history keeps what was there.
            [button: Update from original ] [button: Cancel ]
        [button "Workspace: Mission Pack OP DK, Jess R. here now. Change workspace": OP DK JR ]
            {role=group "Import and Library"}
            [button "Import a file": ] [button "Library": ]
          [button "Account, Colin McFadden": CM ]
          {role=group "Map controls"}
          [button "Map layers": ] [button "My location": ]
          {"Pack panel"}
                  OP DK
                  Mission Pack · 4 members
              Items
                    LZ HAWK (ROCK)
                    16S GC 31204 58811
                    Synced
                  LZ IBIS (OWEST)
                Synced
          {"Panels"}
          [button current: Pack ] [button: LZ/PZ ] [button: Routes ] [button "Threats, 1 on the map": 1 Threats ] [button "Imports, 3 items": 3 Imports ]
          {role=dialog "Finish OP DK?"}
            Finish OP DK?
            Everyone becomes read-only, you included. Members can still view, export and save copies to their own Library. You can reopen the pack whenever you need to.
            [button: Cancel ] [button: Finish pack ]
        [button "Workspace: Mission Pack OP DK, finished. Change workspace": OP DK ]
            {role=group "Import and Library"}
            [button "Import a file": ] [button "Library": ]
          [button "Account, Colin McFadden": CM ]
          {role=group "Map controls"}
          [button "Map layers": ] [button "My location": ]
        {role=dialog "Save a copy to your Library"}
              Save a copy to your Library
              From OP DK.
          [button "Cancel": ]
          Name
          [text "LZ IBIS (OWEST) copy"] [button "Clear name": ]
          Only you can see this copy. Later changes in the pack do not update it.
          Saves to your Library
        [button: Save copy ]
```
