# Android tablet · Members

- Platform: Android tablet, 1280 x 800 dp, landscape
- Screenshot: `../png/AT-Members.png`  ·  Source: `../screens/AT-Members.dc.html`
- Web screen it adapts: 15 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    {"Navigation rail"}
    [button "Workspace: OP DK, a Mission Pack. Change workspace": groups OP DK arrow_drop_down ] [button: file_open Import ]
      [button "Pack, OP DK" current: folder_shared Pack ] [button "LZ/PZ": flight_land LZ/PZ ] [button "Routes, changed in the pack since you looked": route Routes ] [button "Threats, 2 on the map": crisis_alert 2 Threats ] [button "Imports, 5 items": move_to_inbox 5 Imports ]
    [button: collections_bookmark Library ] [button "Account, Colin McFadden": CM ]
    {"Pack panel"}
      groups
            OP DK
          Active
          Mission Pack · you are the owner
      [button "Collapse panel": left_panel_close ]
        North Georgia night LZ/PZ and route planning for B Co 2-10 AVN.
        [button "4 members, 2 here now. Manage members": CM SB JR MW 4 members 2 here now ] [button: person_add Invite ]
        [button: Items ] [button: History ]
          LZ/PZ
        3
        [button: LZ IBIS (OWEST) SB Sam B. · 4 min ago ] [button "More actions for LZ IBIS (OWEST)": more_vert ]
        [button: hexagon LZ HAWK (ROCK) JR Jess R. · 13:21 ] [button "More actions for LZ HAWK (ROCK)": more_vert ]
        collections_bookmark From Library Original changed · [button "Update LZ HAWK (ROCK) from the original": Update ]
        [button: hexagon LZ BLUEBIRD (TWMILE) CM Colin M. · Oct 4 ] [button "More actions for LZ BLUEBIRD (TWMILE)": more_vert ]
          Routes
        2
        [button "OP DK INGRESS, changed in the pack since you looked. 2 routes, Sam B., 13:38": route OP DK INGRESS SB 2 routes · Sam B. · 13:38 ] [button "More actions for OP DK INGRESS": more_vert ]
        [button: route OP DK EGRESS CM 1 route · Colin M. · Oct 4 ] [button "More actions for OP DK EGRESS": more_vert ]
          Point sets
        1
        [button: scatter_plot NORTH GA POINTS CM 1,234 points · Colin M. · 09:40 ] [button "More actions for NORTH GA POINTS": more_vert ]
      [button: Add from Library ] [button: Finish pack ]
    {"Map"}
      [button "Search a grid or lat/long": search ]
        MGRS target
      [text "16S GC 28864 55349"] [button "Go to this grid": arrow_forward ]
      Live · all changes synced
      [button "Map layers": layers ] [button "My location": my_location ]
      [button "Grid under the crosshair, 16S GC 28864 55349. Tap to copy": add 16S GC 28864 55349 ]
    {"LZ IBIS (OWEST) tools"}
          LZ IBIS (OWEST)
          Aircraft, analysis and tools
      [button "Close the LZ IBIS (OWEST) pane": close ]
      [button: Aircraft UH-60L — UH-60L Black Hawk arrow_drop_down ]
        76 m spacing · 100 kt [button "Manage aircraft profiles": Manage ]
        LZ/PZ analysis
        check_circle Analyzed. Planning graphics, export and save are unlocked.
      [button: refresh Re-analyze ]
            Slope map
          [button role=switch checked=false: ]
            LZ box
          [button role=switch checked=true: check ]
        LZ/PZ tools
        Places at the crosshair, or where you tap.
        [button: Draw LZ ] [button: Helo ] [button: PZ ] [button: Sector ] [button: Unit ] [button: L-GA ] [button: R-GA ]
        Export
        [button: Set capture area ] [button: Export LZ card ]
        Routes
      [button: route Sketch a route ]
    {role=dialog "Members of OP DK"}
      groups
          Members of OP DK
          Owners manage who can see and edit this pack. Viewers can look but not change anything.
      [button "Close": close ]
          Invite
            Name or email
          [text "Jamie"] [button "Clear": cancel ]
            People on your teams
          [button: JO Jamie Ortiz B Co 2-10 AVN ] [button: JC Jamie Cole B Co 2-10 AVN ]
          Name search only finds people on your teams. To invite anyone else, type their full email address.
          As
            [button "Invite as Editor": check Editor ] [button "Invite as Viewer": Viewer ]
          [button: person_add Invite ]
            group
                B Co 2-10 AVN
                18 members · not shared with this pack
            [button "Share with team B Co 2-10 AVN": group_add Share with team ]
          Members · 4
          CM
              Colin McFadden (you)
              colin.mcfadden@army.mil
              Here now
          Owner
          SB
              Sam Bell
              sam.bell@army.mil
              Here now
          [button "Editor, role for Sam Bell. Change role": Editor arrow_drop_down ] [button "Remove Sam Bell from OP DK": person_remove ]
          JR
              Jess Reyes
              jess.reyes@army.mil
              Here now
          [button "Editor, role for Jess Reyes. Change role": Editor arrow_drop_down ] [button "Remove Jess Reyes from OP DK": person_remove ]
          MW
              Marcus Webb
              marcus.webb@army.mil
              Seen 2 h ago
          [button "Viewer, role for Marcus Webb. Change role": Viewer arrow_drop_down ] [button "Remove Marcus Webb from OP DK": person_remove ]
          Pending · 1
          mail
              sgt.lopez@army.mil
              Invited Oct 4 as Editor · expires in 6 days
              [button "Resend the invitation to sgt.lopez@army.mil": Resend ] [button "Revoke the invitation to sgt.lopez@army.mil": Revoke ]
      visibility Everyone with access sees every item in this pack. Threats are never shared. [button: Done ]
```
