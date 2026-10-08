# Android phone · Members

- Platform: Android phone, 412 x 915 dp
- Screenshot: `../png/AP-Members.png`  ·  Source: `../screens/AP-Members.dc.html`
- Web screen it adapts: 15 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    [button "Back to OP DK": arrow_back ]
        Members of OP DK
        groups Mission Pack · you are the owner
    [button: Done ]
      Owners manage who can see and edit this pack. Viewers can look but not change anything.
      Invite
        Name or email
      person_search [text "Jamie"] [button "Clear the name": cancel ]
        People on your teams
      [button: JO Jamie Ortiz B Co 2-10 AVN ] [button: JC Jamie Cole B Co 2-10 AVN ]
      Name search only finds people on your teams. To invite anyone else, type their full email address.
      As [button "Invite as Editor. Change role": Editor arrow_drop_down ] [button: person_add Invite ]
      groups
          B Co 2-10 AVN
          18 members · not shared with this pack
      [button "Share with team B Co 2-10 AVN, 18 members": Share with team ]
      Members · 4
        CM
            Colin McFadden (you) Here now
            colin.mcfadden@army.mil
        Owner
        SB
            Sam Bell Here now
            sam.bell@army.mil
          [button "Editor, role for Sam Bell. Change role": Editor arrow_drop_down ] [button "Remove Sam Bell from OP DK": person_remove ]
        JR
            Jess Reyes Here now
            jess.reyes@army.mil
          [button "Editor, role for Jess Reyes. Change role": Editor arrow_drop_down ] [button "Remove Jess Reyes from OP DK": person_remove ]
        MW
            Marcus Webb Seen 2 h ago
            marcus.webb@army.mil
          [button "Viewer, role for Marcus Webb. Change role": Viewer arrow_drop_down ] [button "Remove Marcus Webb from OP DK": person_remove ]
      Pending · 1
      schedule_send
          sgt.lopez@army.mil
          Invited Oct 4 as Editor · expires in 6 days
          [button "Resend the invite to sgt.lopez@army.mil": Resend ] [button "Revoke the invite to sgt.lopez@army.mil": Revoke ]
      visibility
        Everyone with access sees every item in this pack. Threats are never shared.
```
