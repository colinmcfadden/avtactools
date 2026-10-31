# Android phone · New pack

- Platform: Android phone, 412 x 915 dp
- Screenshot: `../png/AP-NewPack.png`  ·  Source: `../screens/AP-NewPack.dc.html`
- Web screen it adapts: 12 (see README, Screens)
- Interactive prototype: yes (state in the DCLogic script at the end of the source)

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    {role=dialog "New Mission Pack"}
      [button "Cancel, close without creating a pack": close ]
        New Mission Pack
      [button: Create ]
        groups
          A shared space for one operation. Everyone in it edits the same LZ/PZs, routes and point sets live.
          Name
        [text "{{ name }}"]
          Description (optional)
        [textarea] (text: North Georgia night LZ/PZ and route planning. )]
        Who can open it
            [button: check Just me ] [button: Just me ]
            [button: check A team ] [button: A team ]
            [button: check Specific people ] [button: Specific people ]
          [button "Team, B Co 2-10 AVN, 18 members. Change team": B Co 2-10 AVN arrow_drop_down ] Team
            18 members
          [button "They can, Edit. Change what the team can do": Edit arrow_drop_down ] They can
          {{ inviteLabel }}
        person_add [text "{{ invite }}"]
          Type a name or an email address. Names are found among your teammates.
        MW marcus.webb@army.mil · Viewer [button "Remove marcus.webb@army.mil": close ]
        lock Only you can open it. You can invite people later from Members.
            Start with items from my Library
            Choose them after creating. Each goes in as a copy.
          [button role=switch checked=false: ] [button role=switch checked=true: check ]
        info
          While a pack is open, everything you create goes into it and its members can see it. Threats are never added: they stay on this device only.
```
