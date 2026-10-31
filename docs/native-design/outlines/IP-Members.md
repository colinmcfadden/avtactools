# iPhone · Members

- Platform: iOS iPhone, 393 x 852 pt
- Screenshot: `../png/IP-Members.png`  ·  Source: `../screens/IP-Members.dc.html`
- Web screen it adapts: 15 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    {role=dialog "Members of OP DK"}
          Members of OP DK
          Mission Pack · you are the owner
      [button "Done": ]
        Owners manage who can see and edit this pack. Viewers can look but not change anything.
        Invite
            Invite by name or full email address
          [text "Jamie"] [button "Clear the name": ]
          People on your teams
        [button: JO Jamie Ortiz B Co 2-10 AVN ]
        [button: JC Jamie Cole B Co 2-10 AVN ]
        Name search only finds people on your teams. To invite anyone else, type their full email address.
        [button "Invite as Editor. Change role": As Editor ] [button: Invite ]
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
          [button "Sam Bell's role: Editor. Change role": Editor ] [button "Remove Sam Bell from OP DK": ]
          JR
              Jess Reyes
              jess.reyes@army.mil
              Here now
          [button "Jess Reyes's role: Editor. Change role": Editor ] [button "Remove Jess Reyes from OP DK": ]
          MW
              Marcus Webb
              marcus.webb@army.mil
              Seen 2 h ago
          [button "Marcus Webb's role: Viewer. Change role": Viewer ] [button "Remove Marcus Webb from OP DK": ]
        Pending · 1
              sgt.lopez@army.mil
              Invited Oct 4 as Editor · expires in 6 days
          [button "Resend the invitation to sgt.lopez@army.mil": Resend ] [button "Revoke the invitation to sgt.lopez@army.mil": Revoke ]
        Team
              B Co 2-10 AVN
              18 members · not shared with this pack
          [button "Share OP DK with B Co 2-10 AVN, 18 members": Share with team ]
        Everyone with access sees every item in this pack. Threats are never shared.
```
