# Android phone · Route plan

- Platform: Android phone, 412 x 915 dp
- Screenshot: `../png/AP-RoutePlan.png`  ·  Source: `../screens/AP-RoutePlan.dc.html`
- Web screen it adapts: 02 (see README, Screens)
- Interactive prototype: no, static

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    [button "Back to Routes": arrow_back ]
        ROUTE 1
        4.2 nm · 2:30 · 40 lb
    [button "Share ROUTE 1 as GPX or FPL": share ] [button "More actions for ROUTE 1": more_vert ]
        SKETCHED ROUTES Unsaved changes
        person Personal · saves to your Library
    [button "Save SKETCHED ROUTES": save Save ]
      {"Whole route"}
          Whole route
        [button "Fetch winds aloft for ROUTE 1": air Fetch winds ]
            Date
          [text "mm/dd/yyyy"] [button "Choose the date from a calendar": calendar_month ]
            Temp °C
          [text "15"]
            Fuel lb/hr
          [text "960"]
        Points
      4 points
      [button: .TGT schedule --:--:-- Clock time not set. Start alt 50 AGL START 0:00 expand_more ]
              Point name
            [text ".SP"]
          [button "Set clock time at .SP, not set": schedule --:--:-- ] [button "Collapse .SP": expand_less ]
              Alt to
            [text "50"] ft
            Altitude reference for .SP
              [button: check AGL ] [button: MSL ]
              Wind °T
            [text "0"]
              Speed to
            [text "100"] kt
            Speed reference for .SP
              [button: check GS ] [button: IAS ]
              Wind KT
            [text "0"]
          east 0.8 nm · 096°T · 100 kt 0:30
      [button: .RP schedule --:--:-- Clock time not set. 2.8 nm · 251°T · 100 kt 2:10 50 AGL · 100 GS · Wind 0°T 0 kt expand_more ] [button: .TGT schedule --:--:-- Clock time not set. 0.6 nm · 327°T · 100 kt 2:30 50 AGL · 100 GS · Wind 0°T 0 kt expand_more ]
          Total
          4.2 nm · 2:30 · 40 lb
      [button: terrain Fetch elevations ]
      UH-60L Black Hawk · speeds, altitudes and winds are “to” each point and export to AMPS.
```
