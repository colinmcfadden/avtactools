# Android phone · Pack history

- Platform: Android phone, 412 x 915 dp
- Screenshot: `../png/AP-PackHistory.png`  ·  Source: `../screens/AP-PackHistory.dc.html`
- Web screen it adapts: 14 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    [button "Search a grid or lat/long": search ]
      MGRS target
    [text "16S GC 28864 55349"] [button "Account, Colin McFadden": CM ]
    [button "Workspace: Mission Pack OP DK. Colin M., Sam B., Jess R. and 1 more here now. Live, all changes synced. Change workspace": groups OP DK CM SB JR +1 cloud_done arrow_drop_down ] [button: file_open Import ] [button: collections_bookmark Library ]
    {"Pack panel"}
      groups
            OP DK
          Active
          Mission Pack · you are the owner
          cloud_done Live · all changes synced
      [button "Collapse panel": expand_more ]
        {role=group "Pack panel view"}
        [button: Items ] [button: check History ]
      [button "Filter by person: Everyone": group Everyone arrow_drop_down ] [button "Filter by item: All items": filter_list All items arrow_drop_down ]
      {"Pack history"}
        New since you looked · 3
          SB
              Sam B. moved Chalk 2 on LZ IBIS (OWEST)
              13:42 · LZ IBIS (OWEST)
          SB
              Sam B. added route RED 1 (copied from Library)
              13:38 · OP DK INGRESS
          JR
              Jess R. changed the landing heading to 270° on LZ HAWK (ROCK)
              13:21 · LZ HAWK (ROCK)
        Earlier today
          CM
              Colin M. updated LZ HAWK (ROCK) from Library
              11:05 · LZ HAWK (ROCK)
          warning
              Edit skipped: sector S-1 on LZ IBIS (OWEST) was already deleted. Jess R.’s change was not applied.
              10:48 · LZ IBIS (OWEST)
          MW
              Marcus W. joined as Viewer
              10:30 · Members
          CM
              Colin M. invited marcus.webb@army.mil as Viewer
              10:12 · Members
          CM
              Colin M. imported NORTH GA POINTS (1,234 points)
              09:40 · NORTH GA POINTS
    {"Panels"}
    [button current: groups Pack ] [button: flight_land LZ/PZ ] [button: route Routes ] [button "Threats, 1 on the map": crisis_alert 1 Threats ] [button "Imports, 3 items": move_to_inbox 3 Imports ]
```
