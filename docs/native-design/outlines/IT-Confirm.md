# iPad · Confirm

- Platform: iOS iPad, 1210 x 834 pt, landscape
- Screenshot: `../png/IT-Confirm.png`  ·  Source: `../screens/IT-Confirm.dc.html`
- Web screen it adapts: 18 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    EZ/PZ confirmations on iPad
        Personal
        LZ/PZ Routes Threats 1 Imports 3
          Open 3 this session
            LZ/PZ 1
            16S GC 28864 55349
            Analyzed Unsaved changes
            Save 3D
            LZ HAWK (ROCK)
            16S GC 31204 58811
            Analyzed Saved · Oct 3
            LZ/PZ 3
            16S GC 27390 54102
            Target set Analyze to save
        LZ/PZ 1
          Aircraft
            UH-60L — UH-60L Black Hawk
            76 m spacing · 100 kt
          LZ/PZ analysis
            Analyzed. Planning graphics, export and save are unlocked.
            Re-analyze
            Slope map
            LZ box
      A · Name already used
        {role=dialog}
          Name is already used
          You already have an LZ/PZ with this name, last saved Aug 25 at 17:34.
          Name
        [text "LZ HAWK (SHOPE)"]
          Keep Both saves this one as “LZ HAWK (SHOPE) 2”. Replace overwrites the saved one, and the old version is not kept.
          [button: Keep Both ] [button: Replace the Saved One ] [button: Cancel ]
      B · Closing with unsaved changes
        {role=dialog}
          Save changes to LZ/PZ 1?
          You have unsaved changes. If you close it now they are lost.
          Saves to your Library ⌘S
          [button: Save ] [button: Don’t Save ] [button: Cancel ]
      C · Deleting from the Library
        {role=dialog}
          Delete “LZ HAWK (SHOPE)”?
          This removes it from your Library on every device you use. Copies you added to a Mission Pack are not affected.
          [button: Cancel ] [button: Delete ]
      D · Removing every threat
        {role=dialog}
          Remove all threats?
          2 threats come off the map and are wiped from this device. They were never saved to your account, so they cannot be brought back.
          [button: Cancel ] [button: Remove All ]
    Shown together for review. In use, one alert at a time sits centred over the dimmed screen. With a keyboard, Return picks the blue button and Esc cancels.
```
