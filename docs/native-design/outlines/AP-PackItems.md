# Android phone · Pack items

- Platform: Android phone, 412 x 915 dp
- Screenshot: `../png/AP-PackItems.png`  ·  Source: `../screens/AP-PackItems.dc.html`
- Web screen it adapts: 13 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    {"Pack panel, OP DK"}
      groups
            OP DK
          Active cloud_done Live · all changes synced
          Mission Pack · you are the owner
      [button "Collapse panel": expand_more ]
      North Georgia night LZ/PZ and route planning for B Co 2-10 AVN.
      [button "4 members, 2 here now. Open members": CM SB JR MW 4 members · 2 here now ] [button: person_add Invite ]
        [button: check Items ] [button: History ]
        LZ/PZ 3
        [button "LZ IBIS (OWEST), changed in the pack since you looked, by Sam B. 4 min ago. Show on the map": flight_land LZ IBIS (OWEST) SB Sam B. · 4 min ago ] [button "More actions for LZ IBIS (OWEST)": more_vert ]
        [button "LZ HAWK (ROCK), by Jess R. at 13:21. From Library, original changed. Show on the map": flight_land LZ HAWK (ROCK) JR Jess R. · 13:21 From Library · Original changed ] [button "Update LZ HAWK (ROCK) from the changed original": Update ] [button "More actions for LZ HAWK (ROCK)": more_vert ]
        [button "LZ BLUEBIRD (TWMILE), by Colin M. on Oct 4. Show on the map": flight_land LZ BLUEBIRD (TWMILE) CM Colin M. · Oct 4 ] [button "More actions for LZ BLUEBIRD (TWMILE)": more_vert ]
        Routes 2
        [button "OP DK INGRESS, 2 routes, by Sam B. at 13:38. Show on the map": route OP DK INGRESS SB 2 routes · Sam B. · 13:38 ] [button "More actions for OP DK INGRESS": more_vert ]
        [button "OP DK EGRESS, 1 route, by Colin M. on Oct 4. Show on the map": route OP DK EGRESS CM 1 route · Colin M. · Oct 4 ] [button "More actions for OP DK EGRESS": more_vert ]
        Point sets 1
        [button "NORTH GA POINTS, 1,234 points, by Colin M. at 09:40. Show on the map": scatter_plot NORTH GA POINTS CM 1,234 points · Colin M. · 09:40 ] [button "More actions for NORTH GA POINTS": more_vert ]
      [button: library_add Add from Library ] [button "Finish pack, makes it read-only for everyone": flag Finish pack ]
    {"Panels"}
    [button current: groups Pack ] [button: flight_land LZ/PZ ] [button: route Routes ] [button "Threats, 2 on the map": crisis_alert 2 Threats ] [button "Imports, 5 items": move_to_inbox 5 Imports ]
```
