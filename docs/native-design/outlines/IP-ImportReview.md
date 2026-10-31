# iPhone · Import review

- Platform: iOS iPhone, 393 x 852 pt
- Screenshot: `../png/IP-ImportReview.png`  ·  Source: `../screens/IP-ImportReview.dc.html`
- Web screen it adapts: 08 (see README, Screens)
- Interactive prototype: yes (state in the DCLogic script at the end of the source)

Visible text and controls in document order. `[button: …]` is a button and its label, `[text "…"]` an input and its value, `{…}` a role or accessibility label. Indentation is container nesting.

```
    {role=dialog "Import 4 files"}
        OP DK · Mission Pack
        Import 4 files
    [button "Close": ]
      We worked out what each file is. Choose where it should live.
      [button "Close menu": ]
              NORTH_GA_POINTS.LPS [button "{{ ptsAria }}": OP DK {{ptsLabel}} ]
                {role=menu "Destination for NORTH_GA_POINTS.LPS"}
                  Save NORTH_GA_POINTS.LPS to
                [button role=menuitemradio checked={{ ptsPackChecked }}: OP DK Mission Pack · 4 members ]
                [button role=menuitemradio checked={{ ptsLibraryChecked }}: Library ]
                [button role=menuitemradio checked={{ ptsSessionChecked }}: This session ]
              Local points · 1,234 points · 82 KB
            Name
          [text "{{ name }}"]
        {{ptsNote}}
              KJZP_CHART.tif Goes to, fixed: This session
              Map overlay · GeoTIFF · 2.4 MB · georeferenced
        Overlays stay in this session for now.
              THREATS_OCT.ths Goes to, fixed: This device only
              Threats · 7 threats
        Threats are never saved or shared with a pack.
              GOAT_SUCKER.msnx [button "{{ msnAria }}": {{msnLabel}} ]
                {role=menu "Destination for GOAT_SUCKER.msnx"}
                  Save GOAT_SUCKER.msnx to
                [button role=menuitemradio checked=false disabled: OP DK Not for mission files yet ]
                [button role=menuitemradio checked={{ msnLibraryChecked }}: Library ]
                [button role=menuitemradio checked={{ msnSessionChecked }}: This session ]
              Mission file · 2 routes · 91 points · 1.5 MB
        Mission files cannot go into a pack yet. Sketched routes only.
              notes.docx [button "Remove notes.docx from the list": ]
              Not supported. Nothing will be imported from this file.
        {{summary}}
        [button: Cancel ] [button: Import 4 files ]
```
