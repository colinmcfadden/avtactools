# iPad · Save flow

- Platform: iOS iPad, 1210 x 834 pt, landscape
- Screenshot: `../png/IT-SaveFlow.png`  ·  Source: `../screens/IT-SaveFlow.dc.html`
- Web screen it adapts: 20 (see README, Screens)
- Interactive prototype: yes (state in the DCLogic script at the end of the source)

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    EZ/PZ, try the save flow
      {role=search "MGRS target"}
        MGRS target
      [text "16S GC 28864 55349"] [button: Go ]
        [button "Import": ] [button "Library": ]
      [button "Account, Colin McFadden": CM ]
        {{ hint }} [button: Make a change ] [button: Replay ]
      [button "Map layers": ] [button "My location": ]
      {role=status}
        {{ toast }} [button: Open Library ]
      [button "Grid under the crosshair, 16S GC 28864 55349. Tap to copy": 16S GC 28864 55349 ]
    {"Sidebar"}
      [button "Hide sidebar": ] [button "Workspace: Personal. Change workspace": Personal ]
      {"Panels"}
      [button current: LZ/PZ ] [button: Routes ] [button "Threats, 1 on the map": Threats 1 ] [button "Imports, 3 items": Imports 3 ]
            Open 3
          this session
          {current}
              {{ name }}
            [button "{{ renameLabel }}": ]
            16S GC 28864 55349
            Analyzed Unsaved changes Saved · just now
            [button: Save ] [button disabled: Saved ] [button "{{ threeDLabel }}": 3D ] [button "{{ moreLabel }}": ]
            Saves to your Library ⌘S
          [button: LZ HAWK (ROCK) 16S GC 31204 58811 Analyzed Saved · Oct 3 ] [button "3D view of LZ HAWK (ROCK)": 3D ]
          [button: LZ/PZ 3 16S GC 27390 54102 Target set Analyze to save ] [button "3D view of LZ/PZ 3": 3D ]
          Set a new target in the search field, or long-press the map, to open another LZ/PZ.
            Recent in Library
          [button: Browse all ]
                  LZ HAWK (SHOPE)
                  Updated Aug 25 · 17:34
              [button "Open LZ HAWK (SHOPE)": Open ]
                  LZ RAZORBILL
                  Updated Aug 25 · 17:27
              [button "Open LZ RAZORBILL": Open ]
                  PZ OAK (KJZP)
                  Updated Aug 25 · 17:02
              [button "Open PZ OAK (KJZP)": Open ]
    {"{{ inspectorLabel }}"}
        {{ name }}
      [button "Close inspector": ]
            Aircraft
          [button: Manage ]
          [button: UH-60L — UH-60L Black Hawk 76 m spacing · 100 kt ]
            LZ/PZ analysis
              Analyzed. Planning graphics, export and save are unlocked.
          [button: Re-analyze ]
            Slope map [button role=switch checked=false: ]
            LZ box [button role=switch checked=true: ]
            LZ/PZ tools
          [button: Draw LZ ] [button: Helo ] [button: PZ ] [button: Sector ] [button: Unit ] [button: L-GA ] [button: R-GA ]
          Places at the crosshair or where you tap.
            Export
          [button: Set capture area ] [button: Export LZ card ]
            Routes
        [button: Sketch a route ]
    {role=dialog}
      [button: Cancel ]
        Save LZ/PZ
      [button: Save ] [button disabled: Save ]
        Name this LZ/PZ so you can find it in your Library.
          Name
          [text "{{ draft }}"] [button "Clear name": ]
          Press Return to save. You can rename it any time.
          What Is Saved
              Boundary, target 16S GC 28864 55349 and slope analysis
              3 helicopters, 2 sectors, doghouses and flight data
              Threats and routes are not included. Routes are saved on their own.
        Saves to your Library · Personal workspace
```
