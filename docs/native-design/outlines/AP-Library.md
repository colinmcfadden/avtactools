# Android phone · Library

- Platform: Android phone, 412 x 915 dp
- Screenshot: `../png/AP-Library.png`  ·  Source: `../screens/AP-Library.dc.html`
- Web screen it adapts: 06 (see README, Screens)
- Interactive prototype: yes (state in the DCLogic script at the end of the source)

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    {"Library"}
      [button "Close Library": close ]
        Library
      Everything you have saved. Opening one adds it to this session.
      {role=tablist "Saved item types"}
      [button role=tab: LZ/PZ 12 ] [button role=tab: Routes 4 ] [button role=tab: Local points 3 ]
      search
        Search LZ/PZs by name or grid
        Search LZ/PZs by name or grid [text "{{ query }}"]
      [button "Clear search": close ]
      [button "Sort: Recently updated. Change sort": sort Recently updated arrow_drop_down ]
        flight_land
            {{ item.name }}
            {{ item.grid }}
            {{ item.updated }}
          check Open in session
          [button "{{ item.openedLabel }}" disabled: Open ] [button "{{ item.openLabel }}": Open ] [button "{{ item.moreLabel }}": more_vert ]
          {role=menu "{{ item.menuLabel }}"}
          [button role=menuitem: edit Rename ] [button role=menuitem: content_copy Duplicate ] [button role=menuitem: groups Add to Mission Pack… ]
          [button role=menuitem: delete Delete ]
          {role=menu "{{ item.menuLabel }}"}
          [button role=menuitem: edit Rename ] [button role=menuitem: content_copy Duplicate ] [button role=menuitem: groups Add to Mission Pack… ]
          [button role=menuitem: delete Delete ]
        No saved LZ/PZ matches that name or grid.
        12 saved LZ/PZs
        Working with a team? [button: groups Open a Mission Pack ]
  [button "Close menu": ]
```
