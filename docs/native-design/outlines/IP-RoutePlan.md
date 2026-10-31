# iPhone · Route Plan

- Platform: iOS iPhone, 393 x 852 pt
- Screenshot: `../png/IP-RoutePlan.png`  ·  Source: `../screens/IP-RoutePlan.dc.html`
- Web screen it adapts: 02 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    {"ROUTE 1 plan"}
      ROUTE 1
      4.2 nm · 2:30 · 40 lb
      In SKETCHED ROUTES · Personal Unsaved changes
      [button "Show ROUTE 1 on the map": ]
      Whole Route
          Date
        [date]
          Temperature , degrees Celsius
          [text "15"] °C
          Fuel flow , pounds per hour
          [text "960"] lb/hr
      [button: Fetch winds aloft ]
      Fills the wind to each point from the winds aloft forecast.
      Points
      {"Points of ROUTE 1"}
          START Elapsed 0:00
              Point 1 name
            [text ".TGT"] [button "Set clock time at .TGT, point 1. Not set": --:--:-- ]
              Start alt , feet
              [text "50"] ft
              {role=group "Start altitude reference"}
              [button: AGL ] [button: MSL ]
          Leg to .SP: 0.8 nm · 096°T · 100 kt Elapsed at .SP 0:30
              Point 2 name
            [text ".SP"] [button "Set clock time at .SP, point 2. Not set": --:--:-- ]
              Alt to .SP, feet
              [text "50"] ft
              {role=group "Altitude reference to .SP"}
              [button: AGL ] [button: MSL ]
              Speed to .SP, knots
              [text "100"] kt
              {role=group "Speed reference to .SP"}
              [button: GS ] [button: IAS ]
              Wind direction to .SP, degrees true
              [text "0"] °T
              Wind speed to .SP, knots
              [text "0"] kt
          Leg to .RP: 2.8 nm · 251°T · 100 kt Elapsed at .RP 2:10
              Point 3 name
            [text ".RP"] [button "Set clock time at .RP, point 3. Not set": --:--:-- ]
              Alt to .RP, feet
              [text "50"] ft
              {role=group "Altitude reference to .RP"}
              [button: AGL ] [button: MSL ]
              Speed to .RP, knots
              [text "100"] kt
              {role=group "Speed reference to .RP"}
              [button: GS ] [button: IAS ]
              Wind direction to .RP, degrees true
              [text "0"] °T
              Wind speed to .RP, knots
              [text "0"] kt
          Leg to .TGT: 0.6 nm · 327°T · 100 kt Elapsed at .TGT 2:30
              Point 4 name
            [text ".TGT"] [button "Set clock time at .TGT, point 4. Not set": --:--:-- ]
              Alt to .TGT, feet
              [text "50"] ft
              {role=group "Altitude reference to .TGT"}
              [button: AGL ] [button: MSL ]
              Speed to .TGT, knots
              [text "100"] kt
              {role=group "Speed reference to .TGT"}
              [button: GS ] [button: IAS ]
              Wind direction to .TGT, degrees true
              [text "0"] °T
              Wind speed to .TGT, knots
              [text "0"] kt
      Total
        Distance 4.2 nm
        Time 2:30
        Fuel 40 lb
      [button: Fetch elevations ]
      UH-60L Black Hawk · speeds, altitudes and winds are “to” each point and export to AMPS.
  [button "Back to Routes": ]
    {role=group "ROUTE 1 actions"}
    [button "Share ROUTE 1, including Open in ForeFlight": ] [button "More actions for ROUTE 1": ]
```
