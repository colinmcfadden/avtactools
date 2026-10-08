# Android phone · Import review

- Platform: Android phone, 412 x 915 dp
- Screenshot: `../png/AP-ImportReview.png`  ·  Source: `../screens/AP-ImportReview.dc.html`
- Web screen it adapts: 08 (see README, Screens)
- Interactive prototype: yes (state in the DCLogic script at the end of the source)

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    {role=dialog "Import 4 files"}
      [button "Close without importing": close ]
          Import 4 files
          groups OP DK · cloud_done Live · all changes synced
        We worked out what each file is. Choose where it should live.
          pin_drop
              NORTH_GA_POINTS.LPS Local points
              1,234 points · 82 KB
            Name
          [text "{{ pointsName }}"]
          {role=group "Where NORTH_GA_POINTS.LPS goes"}
          [button: check OP DK ] [button: Library ] [button: This session ]
          groups Everyone in OP DK (4 members) will see these points.
          {role=group "Where NORTH_GA_POINTS.LPS goes"}
          [button: groups OP DK ] [button: check Library ] [button: This session ]
          collections_bookmark Saves to your Library and syncs with your account. Not shared with OP DK.
          {role=group "Where NORTH_GA_POINTS.LPS goes"}
          [button: groups OP DK ] [button: Library ] [button: check This session ]
          info Shown in this session only. Nothing is saved.
          layers
              KJZP_CHART.tif Map overlay
              GeoTIFF · 2.4 MB · georeferenced
          lock Goes to This session Overlays stay in this session for now.
          crisis_alert
              THREATS_OCT.ths Threats
              7 threats
          lock Goes to This device only Threats are never saved or shared with a pack.
          encrypted Kept in an encrypted file on this device, wiped 48 hours after your last change and when you sign out.
          route
              GOAT_SUCKER.msnx Mission file
              2 routes · 91 points · 1.5 MB
          {role=group "Where GOAT_SUCKER.msnx goes"}
          [button disabled: block OP DK ] [button: Library ] [button: check This session ]
          {role=group "Where GOAT_SUCKER.msnx goes"}
          [button disabled: block OP DK ] [button: check Library ] [button: This session ]
          info Mission files cannot go into a pack yet. Sketched routes only.
        description
            notes.docx
            Not supported. Nothing will be imported from this file.
        [button "Remove notes.docx from the list": close ]
        move_to_inbox {{ summary }}
        [button: Cancel ] [button: download Import 4 files ]
```
