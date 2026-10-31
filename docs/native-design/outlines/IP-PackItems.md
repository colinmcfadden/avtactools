# iPhone · Pack Items

- Platform: iOS iPhone, 393 x 852 pt
- Screenshot: `../png/IP-PackItems.png`  ·  Source: `../screens/IP-PackItems.dc.html`
- Web screen it adapts: 13 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
  [button "Workspace: Mission Pack OP DK, 2 here now. Change workspace": OP DK CM SB ]
      {role=group "Import and Library"}
      [button "Import a file": ] [button "Library": ]
    [button "Account, Colin McFadden": CM ]
    {"Pack panel"}
            OP DK
          Active Synced
          Mission Pack · you are the owner
      [button "Collapse the Pack panel": ]
        North Georgia night LZ/PZ and route planning for B Co 2-10 AVN.
        [button: CM SB JR MW 4 members 2 here now ] [button: Invite ]
        {role=group "Pack view"}
        [button: Items ] [button: History ]
          LZ/PZ 3 items
        Changed since you looked
          [button: LZ IBIS (OWEST) , changed since you looked SB Sam B. · 4 min ago ] [button "More actions for LZ IBIS (OWEST)": ]
            [button: LZ HAWK (ROCK) JR Jess R. · 13:21 · From Library ] [button "More actions for LZ HAWK (ROCK)": ]
            Original changed [button "Update LZ HAWK (ROCK) from its Library original": Update ]
          [button: LZ BLUEBIRD (TWMILE) CM Colin M. · Oct 4 ] [button "More actions for LZ BLUEBIRD (TWMILE)": ]
        Routes 2 items
          [button: OP DK INGRESS SB 2 routes · Sam B. · 13:38 ] [button "More actions for OP DK INGRESS": ]
          [button: OP DK EGRESS CM 1 route · Colin M. · Oct 4 ] [button "More actions for OP DK EGRESS": ]
        Point Sets 1 item
          [button: NORTH GA POINTS CM 1,234 points · Colin M. · 09:40 ] [button "More actions for NORTH GA POINTS": ]
        [button: Add from Library ] [button: Finish pack ]
        Add from Library puts a copy in OP DK; the original stays in your Library. Finishing makes OP DK read-only for all 4 members, you included. Only the owner can finish a pack.
    {"Panels"}
    [button current: Pack ] [button: LZ/PZ ] [button: Routes ] [button "Threats, 1 on the map": 1 Threats ] [button "Imports, 3 items": 3 Imports ]
```
