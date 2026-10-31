# Android phone · Pack dialogs

- Platform: Android phone, 412 x 915 dp
- Screenshot: `../png/AP-PackDialogs.png`  ·  Source: `../screens/AP-PackDialogs.dc.html`
- Web screen it adapts: 17 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    Pack dialogs, Android phone
    A · Copy a Library item into a pack
    B · Update the copy from its original
    C · Finishing (owner only)
    D · Keep a pack item after it is finished
        search 16S GC 28864 55349 CM
        person Personal arrow_drop_down file_open Import collections_bookmark Library
        layers my_location
        add 16S GC 28864 55349
              LZ/PZ
              3 open · this session
          expand_less
                LZ HAWK (SHOPE)
              more_vert
              check_circle Saved
        flight_land LZ/PZ route Routes crisis_alert 1 Threats move_to_inbox 3 Imports
        {role=dialog}
          Add to a Mission Pack
          Adding “LZ HAWK (SHOPE)”.
          {role=radiogroup "Mission Pack"}
          [button role=radio checked=true: groups OP DK 4 members · you can edit ] [button role=radio checked=false: groups B CO FIELD EX 18 members · you can edit ] [button role=radio checked=false: groups OP RAZORBILL lock Finished Sep 22. Reopen it to add items. ]
          A copy goes into the pack. Your Library item stays as it is, and you can update the copy from it later.
          [button: Cancel ] [button: Add copy ]
        search 16S GC 28864 55349 CM
        groups OP DK CM SB JR +1 cloud_done file_open Import collections_bookmark Library
        layers my_location
        add 16S GC 28864 55349
              groups OP DK
              cloud_done Live · all changes synced
          expand_less
          Items
          flight_land
              LZ HAWK (ROCK)
              16S GC 31204 58811 · Synced
          more_vert
        groups Pack flight_land LZ/PZ route Routes crisis_alert 1 Threats move_to_inbox 3 Imports
        {role=dialog}
          Update “LZ HAWK (ROCK)” from Library?
          Your Library version changed Oct 3 at 09:12. Updating replaces the pack copy with it.
          history
            3 changes made in the pack will be replaced , including Jess R.’s landing heading of 270°. The history keeps what was there.
          [button: Cancel ] [button: Update from original ]
        search 16S GC 28864 55349 CM
        groups OP DK CM SB JR +1 cloud_done file_open Import collections_bookmark Library
        layers my_location
        add 16S GC 28864 55349
              groups OP DK
              cloud_done Live · all changes synced
          more_vert
          Items
          flight_land
              LZ HAWK (ROCK)
              16S GC 31204 58811 · Synced
          more_vert
        groups Pack flight_land LZ/PZ route Routes crisis_alert 1 Threats move_to_inbox 3 Imports
        {role=dialog}
          Finish OP DK?
          Everyone becomes read-only, you included. Members can still view, export and save copies to their own Library. You can reopen the pack whenever you need to.
          [button: Cancel ] [button: Finish pack ]
        search 16S GC 28864 55349 CM
        groups OP DK CM SB JR +1 lock file_open Import collections_bookmark Library
        layers my_location
        add 16S GC 28864 55349
              groups OP DK
              lock Finished · read-only
          expand_less
          Items
          flight_land
              LZ IBIS (OWEST)
              Read-only
          more_vert
        groups Pack flight_land LZ/PZ route Routes crisis_alert 1 Threats move_to_inbox 3 Imports
        {role=dialog}
          Save a copy to your Library
          groups From OP DK.
              Name
            [text "LZ IBIS (OWEST) copy"] [button "Clear name": cancel ]
            Only you can see this copy. Later changes in the pack do not update it.
          collections_bookmark Saves to your Library
          [button: Cancel ] [button: Save copy ]
```
