# iPad · Finished

- Platform: iOS iPad, 1210 x 834 pt, landscape
- Screenshot: `../png/IT-Finished.png`  ·  Source: `../screens/IT-Finished.dc.html`
- Web screen it adapts: 16 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    EZ/PZ, finished Mission Pack OP DK, read-only
      {role=search "MGRS target"}
        MGRS target
      [text "16S GC 26318 55907"] [button: Go ]
        [button "Import": ] [button "Library": ]
      [button "Account, Colin McFadden": CM ]
      {role=status}
          OP DK is finished
        [button: Reopen pack ]
        OP DK was finished by Colin McFadden on Oct 5 at 14:20. It is read-only for everyone.
        [button: Save a copy to Library ] [button: Duplicate as new pack ]
      [button "Map layers": ] [button "My location": ]
      [button "Grid under the crosshair, 16S GC 26318 55907. Tap to copy": 16S GC 26318 55907 ]
    {"Sidebar"}
      [button "Hide sidebar": ] [button "Workspace: Mission Pack OP DK, finished. Change workspace": OP DK Finished ]
        {role=group "People in this pack: CM, SB, JR and 1 more"}
        CM SB JR +1
      Read-only for everyone
      {"Panels"}
      [button "Pack": Pack ] [button "LZ/PZ, 3 in this pack" current: LZ/PZ ] [button "Routes, 2 in this pack": Routes ] [button "Threats, 2 on this device": Threats 2 ] [button "Imports, 5 items": Imports 5 ]
            In OP DK 3
          finished · read-only
          {current}
              LZ IBIS (OWEST)
            16S GC 26318 55907
            Analyzed Read-only
            [button "3D view of LZ IBIS (OWEST)": 3D ] [button "Save a copy of LZ IBIS (OWEST) to your Library": Save a copy ] [button "More actions for LZ IBIS (OWEST)": ]
            Finished by Colin M. on Oct 5 at 14:20
          [button: LZ HAWK (ROCK) 16S GC 31204 58811 Analyzed From Library ] [button "3D view of LZ HAWK (ROCK)": 3D ]
          [button: LZ BLUEBIRD (TWMILE) 16S GC 28110 52476 Analyzed ] [button "3D view of LZ BLUEBIRD (TWMILE)": 3D ]
          Nothing in a finished pack can be changed, by anyone. Save a copy to your Library to keep planning from an LZ/PZ.
    {"Inspector, LZ IBIS (OWEST), read-only"}
          LZ IBIS (OWEST)
          Read-only · export still works
      [button "Close inspector": ]
            Aircraft
          [button: Manage ]
              UH-60L — UH-60L Black Hawk
              76 m spacing · 100 kt
            LZ/PZ analysis
              Analyzed. Locked: OP DK is finished, so it cannot be analyzed again.
          [button disabled: Re-analyze ]
            Slope map [button role=switch checked=false: ]
            LZ box [button role=switch checked=true disabled: ]
            LZ/PZ tools
          Locked
          [button disabled: Draw LZ ] [button disabled: Helo ] [button disabled: PZ ] [button disabled: Sector ] [button disabled: Unit ] [button disabled: L-GA ] [button disabled: R-GA ]
          Off in a finished pack.
            Export
          [button: Set capture area ] [button: Export LZ card ]
            Routes
        [button disabled: Sketch a route ]
```
