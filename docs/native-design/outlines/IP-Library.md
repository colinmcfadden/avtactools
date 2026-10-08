# iPhone · Library

- Platform: iOS iPhone, 393 x 852 pt
- Screenshot: `../png/IP-Library.png`  ·  Source: `../screens/IP-Library.dc.html`
- Web screen it adapts: 06 (see README, Screens)
- Interactive prototype: yes (state in the DCLogic script at the end of the source)

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    {role=dialog "Library"}
      [button: Done ]
        Library
        Everything you have saved. Opening one adds it to this session.
        {role=tablist "Saved item types"}
        [button role=tab: LZ/PZ 12 ] [button role=tab: Routes 4 ] [button role=tab: Local points 3 ]
          Search LZ/PZs by name or grid
        [text "{{ q }}"] [button "Clear search": ]
          Saved LZ/PZs
        [button "Sort: Recently updated": Recently updated ]
                {{ item.name }}
                {{ item.grid }}
                {{ item.updated }}
              Open in session
              [button "{{ item.openLabel }}" disabled: Open ] [button "{{ item.openLabel }}": Open ] [button "{{ item.moreLabel }}": ]
            {role=menu "{{ item.menuLabel }}"}
                {{ item.name }}
                {{ item.grid }}
            [button role=menuitem: Rename ]
            [button role=menuitem: Duplicate ]
            [button role=menuitem: Add to Mission Pack… ]
            [button role=menuitem: Delete from Library… ]
        {{ noMatchText }}
        12 saved LZ/PZs
      [button: Working with a team? Open a Mission Pack ]
```
