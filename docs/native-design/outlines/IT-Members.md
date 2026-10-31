# iPad · Members

- Platform: iOS iPad, 1210 x 834 pt, landscape
- Screenshot: `../png/IT-Members.png`  ·  Source: `../screens/IT-Members.dc.html`
- Web screen it adapts: 15 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    EZ/PZ, members of the Mission Pack OP DK
      {role=search "MGRS target"}
        MGRS target
      [text "16S GC 28864 55349"] [button: Go ]
        [button "Import": ] [button "Library": ]
      [button "Account, Colin McFadden": CM ]
      [button "Map layers": ] [button "My location": ]
      [button "Grid under the crosshair, 16S GC 28864 55349. Tap to copy": 16S GC 28864 55349 ]
    {"Sidebar"}
      [button "Hide sidebar": ] [button "Workspace: OP DK, Mission Pack. Change workspace": OP DK PACK ]
        {role=group "Here now: Colin McFadden, Sam Bell and Jess Reyes. 1 more member"}
        CM SB JR +1
      {"Panels"}
      [button current: Pack ] [button: LZ/PZ ] [button: Routes ] [button "Threats, 2 on the map": Threats 2 ] [button "Imports, 5 items": Imports 5 ]
              OP DK
            Active
            Mission Pack · you are the owner
            North Georgia night LZ/PZ and route planning for B Co 2-10 AVN.
            CM SB JR MW [button: 4 members · 2 here now ]
            [button: Invite ] Live · all changes synced
        [button: Items ] [button: History ]
            LZ/PZ 3
              [button: LZ IBIS (OWEST) , changed in the pack since you looked SB Sam B. · 4 min ago ]
            [button "More actions for LZ IBIS (OWEST)": ]
              [button: LZ HAWK (ROCK) JR Jess R. · 13:21 ]
                From Library Original changed [button "Update LZ HAWK (ROCK) from the original": Update ]
            [button "More actions for LZ HAWK (ROCK)": ]
              [button: LZ BLUEBIRD (TWMILE) CM Colin M. · Oct 4 ]
            [button "More actions for LZ BLUEBIRD (TWMILE)": ]
            Routes 2
              [button: OP DK INGRESS SB 2 routes · Sam B. · 13:38 ]
            [button "More actions for OP DK INGRESS": ]
              [button: OP DK EGRESS CM 1 route · Colin M. · Oct 4 ]
            [button "More actions for OP DK EGRESS": ]
            Point Sets 1
              [button: NORTH GA POINTS CM 1,234 points · Colin M. · 09:40 ]
            [button "More actions for NORTH GA POINTS": ]
        [button: Add from Library ] [button: Finish pack ]
    {role=dialog}
        Members of OP DK
      [button "Done": ]
        Owners manage who can see and edit this pack. Viewers can look but not change anything.
          Invite
              Invite by name or email address
            [text "Jamie"] [button "Clear": ]
          [button "Invite as Editor. Change role": As Editor ] [button: Invite ]
          People on Your Teams
            [button: JO Jamie Ortiz B Co 2-10 AVN ]
            [button: JC Jamie Cole B Co 2-10 AVN ]
          Name search only finds people on your teams. To invite anyone else, type their full email address.
            B Co 2-10 AVN
            18 members · not shared with this pack
        [button: Share with team ]
            Members 4
            CM
                Colin McFadden (you)
                colin.mcfadden@army.mil · Here now
            Owner
            SB
                Sam Bell
                sam.bell@army.mil · Here now
            [button "Role for Sam Bell: Editor. Change role": Editor ] [button "Remove Sam Bell": ]
            JR
                Jess Reyes
                jess.reyes@army.mil · Here now
            [button "Role for Jess Reyes: Editor. Change role": Editor ] [button "Remove Jess Reyes": ]
            MW
                Marcus Webb
                marcus.webb@army.mil · Seen 2 h ago
            [button "Role for Marcus Webb: Viewer. Change role": Viewer ] [button "Remove Marcus Webb": ]
            Pending 1
                sgt.lopez@army.mil
                Invited Oct 4 as Editor · expires in 6 days
            [button "Resend invite to sgt.lopez@army.mil": Resend ] [button "Revoke invite to sgt.lopez@army.mil": Revoke ]
        Everyone with access sees every item in this pack. Threats are never shared.
```
