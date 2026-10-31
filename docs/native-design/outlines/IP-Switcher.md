# iPhone · Switcher

- Platform: iOS iPhone, 393 x 852 pt
- Screenshot: `../png/IP-Switcher.png`  ·  Source: `../screens/IP-Switcher.dc.html`
- Web screen it adapts: 11 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
  [button "Workspace: Personal. Change workspace": Personal ]
      {role=group "Import and Library"}
      [button "Import a file": ] [button "Library": ]
    [button "Account, Colin McFadden": CM ]
    {role=dialog "Workspace"}
        Workspace
      [button "Close": ]
            Alex Park invited you to OP IBIS as Editor
          [button "Decline the invitation to OP IBIS": Decline ] [button "Accept the invitation to OP IBIS": Accept ]
        [button current: Library , current workspace Personal · 12 LZ/PZs, 4 route sets, 3 point sets 1 LZ/PZ with unsaved changes ]
        Saved items go to your Library.
          Mission Packs
        [button: New pack ]
        [button: OP DK 4 members · you are Owner 2 here now 3 LZ/PZ · 2 routes ]
        [button: B CO FIELD EX 18 members · you are Editor ]
        [button: OP RAZORBILL Finished Sep 22 · read-only ]
        In a pack, edits reach every member as you make them, so there is no Save button.
        B Co 2-10 AVN and 1 other team [button: Manage teams ]
```
