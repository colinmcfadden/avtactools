# iPad · Pack live

- Platform: iOS iPad, 1210 x 834 pt, landscape
- Screenshot: `../png/IT-PackLive.png`  ·  Source: `../screens/IT-PackLive.dc.html`
- Web screen it adapts: 10 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    EZ/PZ, working in Mission Pack OP DK
      {role=search "MGRS target"}
        MGRS target
      [text "16S GC 26318 55907"] [button: Go ]
        [button "Import": ] [button "Library": ]
      [button "Account, Colin McFadden": CM ]
      [button "Map layers": ] [button "My location": ]
      [button "Grid under the crosshair, 16S GC 26318 55907. Tap to copy": 16S GC 26318 55907 ]
    {"Sidebar"}
      [button "Hide sidebar": ] [button "Workspace: Mission Pack OP DK. Change workspace": OP DK ]
        {role=group "Live, all changes synced. Here now: Colin McFadden and Sam B."}
        Live CM SB
      {"Panels"}
      [button current: Pack ] [button: LZ/PZ ] [button: Routes ] [button "Threats, 2 on the map": Threats 2 ] [button "Imports, 5 items": Imports 5 ]
              OP DK
              Mission Pack · you are the owner
          Active
          North Georgia night LZ/PZ and route planning for B Co 2-10 AVN.
          [button "4 members, 2 here now. Show members": CM SB JR MW 4 members 2 here now ] [button: Invite ]
        {role=group "Pack view"}
        [button: Items ] [button: History ]
            LZ/PZ 3
          changed since you looked
              [button current: LZ IBIS (OWEST) SB Sam B. · 4 min ago ] [button "More actions for LZ IBIS (OWEST)": ]
                [button: LZ HAWK (ROCK) JR Jess R. · 13:21 , changed since you looked ] [button "More actions for LZ HAWK (ROCK)": ]
                From Library Original changed [button "Update LZ HAWK (ROCK) from its Library original": Update ]
              [button: LZ BLUEBIRD (TWMILE) CM Colin M. · Oct 4 ] [button "More actions for LZ BLUEBIRD (TWMILE)": ]
            Routes 2
              [button: OP DK INGRESS SB 2 routes · Sam B. · 13:38 , changed since you looked ] [button "More actions for OP DK INGRESS": ]
              [button: OP DK EGRESS CM 1 route · Colin M. · Oct 4 ] [button "More actions for OP DK EGRESS": ]
            Point sets 1
              [button: NORTH GA POINTS CM 1,234 points · Colin M. · 09:40 ] [button "More actions for NORTH GA POINTS": ]
      [button: Add from Library ] [button: Finish pack ]
    {"Inspector, LZ IBIS (OWEST)"}
        LZ IBIS (OWEST)
      [button "Rename LZ IBIS (OWEST)": ] [button "Close inspector": ]
        {"LZ IBIS (OWEST) in OP DK"}
            16S GC 26318 55907
            Analyzed Synced
            SB
                Sam B. is also here
                Moving CH2 on the map
            [button "3D view of LZ IBIS (OWEST)": 3D ] [button "More actions for LZ IBIS (OWEST)": ]
            Saved to OP DK as you work. 4 members can see it.
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
