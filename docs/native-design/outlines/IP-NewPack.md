# iPhone · New Pack

- Platform: iOS iPhone, 393 x 852 pt
- Screenshot: `../png/IP-NewPack.png`  ·  Source: `../screens/IP-NewPack.dc.html`
- Web screen it adapts: 12 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    {role=dialog "New Mission Pack"}
      [button "Cancel": ]
        New Mission Pack
      [button "Create pack": ]
        A shared space for one operation. Everyone in it edits the same LZ/PZs, routes and point sets live.
            Name
          [text "OP DK"]
            Description Optional
          [textarea] (text: North Georgia night LZ/PZ and route planning. )]
        Who Can Open It
        {role=group "Who can open it"}
        [button: Just me ] [button: A team ] [button: Specific people ]
        [button "Team: B Co 2-10 AVN, 18 members. Change team": Team B Co 2-10 AVN 18 members ]
        [button "They can: Edit. Change what the team can do": They can Edit ]
        Also Invite by Name or Email
            Also invite by name or email
          [text "Name or email address"]
          marcus.webb@army.mil · Viewer [button "Remove marcus.webb@army.mil": ]
              Start with items from my Library
              Choose them after creating
          [button role=switch checked=false: ]
        While a pack is open, everything you create goes into it and its members can see it. Threats are never added.
```
