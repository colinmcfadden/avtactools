# iPad · Pack history

- Platform: iOS iPad, 1210 x 834 pt, landscape
- Screenshot: `../png/IT-PackHistory.png`  ·  Source: `../screens/IT-PackHistory.dc.html`
- Web screen it adapts: 14 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    EZ/PZ, Mission Pack OP DK, history
      {role=search "MGRS target"}
        MGRS target
      [text "16S GC 28864 55349"] [button: Go ]
        [button "Import": ] [button "Library": ]
      [button "Account, Colin McFadden": CM ]
      [button "Map layers": ] [button "My location": ]
      [button "Grid under the crosshair, 16S GC 28864 55349. Tap to copy": 16S GC 28864 55349 ]
    {"Sidebar"}
      [button "Hide sidebar": ] [button "Workspace: OP DK, a Mission Pack. Change workspace": OP DK PACK ]
      {"Panels"}
      [button current: Pack ] [button: LZ/PZ ] [button: Routes ] [button "Threats, 2 on the map": Threats 2 ] [button "Imports, 5 items": Imports 5 ]
            OP DK
          Active
          Mission Pack · you are the owner
            {role=group "People in this pack: Colin McFadden (you), Sam B., Jess R. and 1 more"}
            CM SB JR +1
          Live · all changes synced
        {role=group "Pack view"}
        [button: Items ] [button: History ]
        [button "Changes by: Everyone. Choose a person": Everyone ] [button "Changes to: All items. Choose an item": All items ]
            New since you looked 3
            [button: SB Sam B. moved Chalk 2 on LZ IBIS (OWEST) 13:42 · LZ IBIS (OWEST) ]
            [button: SB Sam B. added route RED 1 (copied from Library) 13:38 · OP DK INGRESS ]
            [button: JR Jess R. changed the landing heading to 270° on LZ HAWK (ROCK) 13:21 · LZ HAWK (ROCK) ]
            Earlier today
            [button: CM Colin M. updated LZ HAWK (ROCK) from Library 11:05 · LZ HAWK (ROCK) ]
            [button: Edit skipped: sector S-1 on LZ IBIS (OWEST) was already deleted. Jess R.'s change was not applied. 10:48 · LZ IBIS (OWEST) ]
            [button: MW Marcus W. joined as Viewer 10:30 · Members ]
            [button: CM Colin M. invited marcus.webb@army.mil as Viewer 10:12 · Members ]
            [button: CM Colin M. imported NORTH GA POINTS (1,234 points) 09:40 · NORTH GA POINTS ]
          Tap a change to show its item on the map.
    {"Inspector, LZ IBIS (OWEST)"}
          LZ IBIS (OWEST)
          Synced Edits go to OP DK
      [button "Close inspector": ]
            Aircraft
          [button: Manage ]
          [button: UH-60L — UH-60L Black Hawk 76 m spacing · 100 kt ]
            LZ/PZ analysis
              Analyzed. Planning graphics, export and save are unlocked.
          [button: Re-analyze ]
            Slope map [button role=switch checked=false: ]
            LZ box [button role=switch checked=true: ]
            LZ/PZ tools
          [button: Draw LZ ] [button: Helo ] [button: PZ ] [button: Sector ] [button: Unit ] [button: L-GA ] [button: R-GA ]
          Places at the crosshair or where you tap.
            Export
          [button: Set capture area ] [button: Export LZ card ]
            Routes
        [button: Sketch a route ]
```
