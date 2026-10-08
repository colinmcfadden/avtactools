# iPhone · Confirm

- Platform: iOS iPhone, 393 x 852 pt
- Screenshot: `../png/IP-Confirm.png`  ·  Source: `../screens/IP-Confirm.dc.html`
- Web screen it adapts: 18 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    Confirmations
  A · Name already used
        Personal
        CM
        16S GC 28864 55349
          16S GC 28864 55349
                LZ/PZ
                3 open · this session
                LZ/PZ 1
              16S GC 28864 55349
              Analyzed Unsaved changes
              Save 3D Saves to your Library
            Also Open
        LZ/PZ Routes 1 Threats 3 Imports
      {role=alertdialog}
        Name Is Already Used
          You already have an LZ/PZ named “LZ HAWK (SHOPE)”, last saved Aug 25 at 17:34.
          Keep Both saves this one as “LZ HAWK (SHOPE) 2”. Replace overwrites the saved one, and the old version is not kept.
        [button: Keep Both ] [button: Replace ] [button: Cancel ]
  B · Closing with unsaved changes
        Personal
        CM
        16S GC 28864 55349
          16S GC 28864 55349
                LZ/PZ
                3 open · this session
                LZ/PZ 1
              16S GC 28864 55349
              Analyzed Unsaved changes
              Save 3D Saves to your Library
            Also Open
        LZ/PZ Routes 1 Threats 3 Imports
      {role=alertdialog}
        Save Changes to LZ/PZ 1?
        You have unsaved changes. If you close it now they are lost.
        [button: Save ] [button: Don’t Save ] [button: Cancel ]
  C · Deleting from the Library
            Library
          Search Library
            LZ/PZ
                  LZ HAWK (SHOPE)
                  Updated Aug 25 · 17:34
                  LZ RAZORBILL
                  Updated Aug 25 · 17:27
                  PZ OAK (KJZP)
                  Updated Aug 25 · 17:02
      {role=alertdialog}
        Delete “LZ HAWK (SHOPE)”?
        This removes it from your Library on every device you use. Copies you added to a Mission Pack are not affected.
        [button: Cancel ] [button: Delete ]
  D · Removing every threat
        Personal
        CM
                Threats
                2 on the map · this device only
              Threats stay on this device only, in an encrypted file that is wiped 48 hours after your last change and when you sign out. They are never saved to your account or shared with a Mission Pack.
            Remove All Threats
        LZ/PZ Routes 2 Threats 3 Imports
      {role=alertdialog}
        Remove All Threats?
        2 threats come off the map and are wiped from this device. Threats are never saved to your account, so they cannot be brought back.
        [button: Cancel ] [button: Remove All ]
```
