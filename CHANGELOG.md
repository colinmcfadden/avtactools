## [1.8.0](https://github.com/colinmcfadden/avtactools/compare/v1.7.8...v1.8.0) (2026-10-31)

### Features

* **android:** a debug build shows which server it talks to and what the last sign-in failure was ([f694de8](https://github.com/colinmcfadden/avtactools/commit/f694de8b33d7c8ee26ff761c14fbd940537c5be5))
* **android:** a finished mission pack's banner, and finishing, reopening and duplicating one ([c1c730d](https://github.com/colinmcfadden/avtactools/commit/c1c730d2e6d7ca8f4d6bef88117d354c820ddd95))
* **android:** a read-only mission pack, looked at and never changed ([190700b](https://github.com/colinmcfadden/avtactools/commit/190700b3653c24c7c3a61d0e2d180e2b88f5e4d1))
* **android:** a slope heat map button and deleting a boundary corner ([eaf6ff2](https://github.com/colinmcfadden/avtactools/commit/eaf6ff2b5240fbf10b540fd16ed29b6c6c6067b1))
* **android:** accept a mission-pack invitation link once signed in ([d330705](https://github.com/colinmcfadden/avtactools/commit/d33070550203a8a884a8c4aedb5aca06664db85f))
* **android:** add local threat planning UI ([bd43777](https://github.com/colinmcfadden/avtactools/commit/bd4377755236420ff7d9bfeabe4e2e1fcad4114c))
* **android:** add the pure-Kotlin core modules, tested against the fixtures ([aab4a29](https://github.com/colinmcfadden/avtactools/commit/aab4a29a8b26c54d7ca656da92992724649df982))
* **android:** aircraft profiles: choose the mission aircraft, and make, copy, change and delete your own ([8c801fc](https://github.com/colinmcfadden/avtactools/commit/8c801fc41e985ff0a4e17410575f4f18fb752084))
* **android:** analysis of a landing zone, with the diagram operations held to the web's reducer ([658b36f](https://github.com/colinmcfadden/avtactools/commit/658b36f7e39efe14f93fe7e7c8222558c29936e0))
* **android:** analyze a landing zone from the diagrams tab, with its summary on the map ([07d20d5](https://github.com/colinmcfadden/avtactools/commit/07d20d55c7c03aedc917b68c5edff0a3017d94c7))
* **android:** apply mission-pack operations as the server does ([60e3d74](https://github.com/colinmcfadden/avtactools/commit/60e3d747fb07f6de034c47b0c5236c1d1024c573))
* **android:** ask for a sign-in after 30 days offline, the server's window ([17f2096](https://github.com/colinmcfadden/avtactools/commit/17f20969aa016b77b05017e9ce83c63f2c8de571))
* **android:** bring a mission's routes in as a new set of routes, from Files, a mail, or the Routes tab ([7d4b469](https://github.com/colinmcfadden/avtactools/commit/7d4b469bf6d06d5136d8517877001e2094cfe206))
* **android:** build with the Android Gradle Plugin, a design system and an app that starts ([047e70d](https://github.com/colinmcfadden/avtactools/commit/047e70d1d29fc238b09c0337e5b3f748a2f21559))
* **android:** call every pack, team, invitation and people-search route ([5a16b39](https://github.com/colinmcfadden/avtactools/commit/5a16b3995a037ffff72a7bf742bcb6d80a320d7b))
* **android:** close the phone's panel, tools under the lz/pz card, add object from the map menu ([8483cd6](https://github.com/colinmcfadden/avtactools/commit/8483cd6d01759aae964d2f529d5bf4966911b7f1))
* **android:** copies of the person's own items into a mission pack ([bff5f2a](https://github.com/colinmcfadden/avtactools/commit/bff5f2a1161623169748139afc4aea8d48de7988))
* **android:** credit naip for a streamed survey's point colour ([83135c1](https://github.com/colinmcfadden/avtactools/commit/83135c1a8a1fea95d1780a5240a03ae76f1fc6b2))
* **android:** cut the brief's routes into strip charts, as the web does ([2c11601](https://github.com/colinmcfadden/avtactools/commit/2c116010bac548f0b24d127c0e71d122af0faeb2))
* **android:** diagrams tab with list, create from a target, open, rename, delete and conflicts ([fea9053](https://github.com/colinmcfadden/avtactools/commit/fea90531742fc2892519b54560580265b0914dce))
* **android:** draw a landing-zone boundary by hand ([bf7d3b1](https://github.com/colinmcfadden/avtactools/commit/bf7d3b1d11aacd4b6a22ff73701502bb4413138d))
* **android:** draw contour lines on the slope map ([80acad0](https://github.com/colinmcfadden/avtactools/commit/80acad095455b14815b9c4f84d613507b6efb6ea))
* **android:** draw route points by their type, as the web does ([f6da052](https://github.com/colinmcfadden/avtactools/commit/f6da0522ba7610e2dfd7a84051f56f6fb11da1ed))
* **android:** draw the 3d view's point cloud ([fbe1d48](https://github.com/colinmcfadden/avtactools/commit/fbe1d48fcfc4be5f597d966078863d7caf776111))
* **android:** draw the ground and its satellite imagery under the 3d view ([d86abc4](https://github.com/colinmcfadden/avtactools/commit/d86abc45c03ac7b350cd8144926a151d1643a022))
* **android:** draw the routes in the 3d view ([c156d98](https://github.com/colinmcfadden/avtactools/commit/c156d98b171492a4acdd6b07a8f87956ba8bb3db))
* **android:** draw the sets of local points on the map and hold a point ([89669c3](https://github.com/colinmcfadden/avtactools/commit/89669c3ee1eb4f184985f4a747f29ada22b7c2bb)), closes [#RRGGBB](https://github.com/colinmcfadden/avtactools/issues/RRGGBB)
* **android:** draw the slope profile along and across the landing heading ([65299ec](https://github.com/colinmcfadden/avtactools/commit/65299ec37de6123b58b13c876587d929e5ecd7cb))
* **android:** drop the offline limit, so a device without signal stays signed in ([46b22f6](https://github.com/colinmcfadden/avtactools/commit/46b22f6a3d938ac736da2f7be6487e29afebf851))
* **android:** edit a mission pack's LZ/PZs and route sets in the existing editors ([fb0600d](https://github.com/colinmcfadden/avtactools/commit/fb0600d4bc4a21e2641503b42521a97da7e1af95))
* **android:** edit doghouses and keep the flight data headings in step with them ([51d71a9](https://github.com/colinmcfadden/avtactools/commit/51d71a9462814e116d614e677d78a5f76293da1b))
* **android:** everyone else's pointer on the map in a mission pack ([bb9d5ed](https://github.com/colinmcfadden/avtactools/commit/bb9d5ed62e826523ae05d1985d2a92fd4f234412))
* **android:** export a set of routes, or one route, for AMPS ([8fb730d](https://github.com/colinmcfadden/avtactools/commit/8fb730d1f45418e9206e3fe0946416c985142fe9))
* **android:** export sketched routes as an AMPS mission (.msnx) ([d524d48](https://github.com/colinmcfadden/avtactools/commit/d524d488488b9e59751dff105ac4ae698961b5d6))
* **android:** find or build the 3d view's point cloud ([13129d7](https://github.com/colinmcfadden/avtactools/commit/13129d702137e7f683370a252261408dda727568))
* **android:** hand the editors their mission-pack stores ([cdfd5e8](https://github.com/colinmcfadden/avtactools/commit/cdfd5e84a0a8012edde1c2eef6ba006cb07ad466))
* **android:** helicopter names, point types in the bar, and lz/pz tools on the map's edge ([5b4ee55](https://github.com/colinmcfadden/avtactools/commit/5b4ee55809bcc4a6598f7b0eaf3c987fdb72be92))
* **android:** hide the phone's frame with a tap on the map ([9f7cbb5](https://github.com/colinmcfadden/avtactools/commit/9f7cbb543ee1d0002c0fdf7be50761d159175e04))
* **android:** hold an open pack as the web's client session does ([573883c](https://github.com/colinmcfadden/avtactools/commit/573883c26403af7648b458e13d87bbbec9b98f12))
* **android:** hold the 3d view together over the map and in the back ([2dc1007](https://github.com/colinmcfadden/avtactools/commit/2dc10078690fe2dde4bbf9a457c90e854a706159))
* **android:** join the app's mission packs to the editors, and stop them through them ([7a82d82](https://github.com/colinmcfadden/avtactools/commit/7a82d821884194feca97f1b80fd368590cabf273))
* **android:** judge the landing heading and recommend one on the slope tile ([77e5c62](https://github.com/colinmcfadden/avtactools/commit/77e5c62ebb1dbf256e3ee88a876b283e4bb18b68))
* **android:** keep an imported mission as its file, through sync, and write edits back into it ([6e82ea9](https://github.com/colinmcfadden/avtactools/commit/6e82ea97216b07c33af81acb05fdf17616a6a309))
* **android:** keep each person's own fields out of a pack item, and write its history ([4c02e68](https://github.com/colinmcfadden/avtactools/commit/4c02e6849bfc25e16b78bb0b3a46a693787b8871))
* **android:** keep every open lz/pz on the map, with an eye to hide one ([f946136](https://github.com/colinmcfadden/avtactools/commit/f94613609bb8590329b710e9536d7d86a735c432))
* **android:** keep mission packs and their unsent edits on the device ([4b56144](https://github.com/colinmcfadden/avtactools/commit/4b5614468005b4e0eb0b351fcac61fa567c8bdd4))
* **android:** keep or discard the edits a mission pack would not take ([4cd134a](https://github.com/colinmcfadden/avtactools/commit/4cd134a78bb9277d78336fea4c1eecbe709955ce))
* **android:** keep records in Room, held to the same sync scenarios as the in-memory store ([e234a2c](https://github.com/colinmcfadden/avtactools/commit/e234a2c2a4b7ffbe07dc725f9f9061e2d8561665))
* **android:** make a mission pack from the switcher ([2e33f3b](https://github.com/colinmcfadden/avtactools/commit/2e33f3b855e242ccb3daa37eda4b5b4fddda241e))
* **android:** make the slope map's opacity adjustable, and the lz box hideable ([7caaceb](https://github.com/colinmcfadden/avtactools/commit/7caaceb810ec8a5d1db5d47513ffac45dda8a9bd))
* **android:** MIL-STD-2525C symbols and units, drawn from the web's own milsymbol ([f2b0a22](https://github.com/colinmcfadden/avtactools/commit/f2b0a22920716165a91f400f9d4a385d14f147f2))
* **android:** move and add route points from the sheet ([31c3ae8](https://github.com/colinmcfadden/avtactools/commit/31c3ae8a1ede3321ab9ccc0db1a95a63ea3e1fb6))
* **android:** offer a new lz/pz's first step over the map ([fe7c866](https://github.com/colinmcfadden/avtactools/commit/fe7c86683ea7fd62668307140b80a6b4c0de600d))
* **android:** offer analyze after finish, and edit objects from their bar ([058b533](https://github.com/colinmcfadden/avtactools/commit/058b5334a18f799208c77aec93370759b8329a83))
* **android:** open .LPS and .ths files from Files, a mail or the share sheet, asking before importing ([49e07b5](https://github.com/colinmcfadden/avtactools/commit/49e07b5ae1066e975f7535532319ea370cb55ff8))
* **android:** open a mission pack's live stream over OkHttp's own WebSocket ([bc33909](https://github.com/colinmcfadden/avtactools/commit/bc33909d26d489d41691c30244052fb62a7540d7))
* **android:** open the 3d view over the whole screen from the lz/pz panel ([bcd03bc](https://github.com/colinmcfadden/avtactools/commit/bcd03bcc72bbb70f02d9d97143ba5fcf01cea08f))
* **android:** open the diagram that was open when the app comes back ([e488c03](https://github.com/colinmcfadden/avtactools/commit/e488c03d8aaeed5f0ae48b2bcae56177f0140cb3))
* **android:** pick things up on the map with a long press and drag them, and turn what is held ([8fdb3e2](https://github.com/colinmcfadden/avtactools/commit/8fdb3e2e76414cc674a61c644db4274e14611789))
* **android:** plan the held route, with a nav log, winds and ground elevations ([b9ff8f5](https://github.com/colinmcfadden/avtactools/commit/b9ff8f5f7b7dd3f64aa00f12feb22e27b8ca1024))
* **android:** planning graphics geometry, placement and separation alerts held to the web ([e9d92ec](https://github.com/colinmcfadden/avtactools/commit/e9d92ec90128baa19c9f3c65db6416012a6871ac))
* **android:** planning graphics in the sheet and on the map, with the crosshair aligned to the camera ([d3bf72d](https://github.com/colinmcfadden/avtactools/commit/d3bf72d2affe41b4cf2d12b8028dd74461681d4f))
* **android:** poll and show notifications ([d53dc08](https://github.com/colinmcfadden/avtactools/commit/d53dc088d5b698d6891845291c8cc1b8a027a966))
* **android:** port the mission brief's compiler and light data to core-brief ([a61e4bd](https://github.com/colinmcfadden/avtactools/commit/a61e4bdb618d5761b61ed5958a47e70896079512))
* **android:** present a mission brief as a live sandtable on a tablet ([01d2c85](https://github.com/colinmcfadden/avtactools/commit/01d2c853a63bfc9c4207ddef338a77d11cc507e8))
* **android:** put the phone's search, workspace, import and library in one row ([a87d6dc](https://github.com/colinmcfadden/avtactools/commit/a87d6dc656bb3056bde26fde27e15ed338d2fb33))
* **android:** read AMPS .LPS and .ths files, and give the rows of a .ths export ([20464f0](https://github.com/colinmcfadden/avtactools/commit/20464f0e517e1b37b4b30560f79229ee369d3042))
* **android:** read AMPS .msnx missions as the web does ([a7f0098](https://github.com/colinmcfadden/avtactools/commit/a7f0098c877b5416864ea7795cbaf43095302fe3))
* **android:** read and choose the 3d view's point-cloud tiles ([63ceca9](https://github.com/colinmcfadden/avtactools/commit/63ceca90a9fafce02beb233444e8e431bb8d2cdc))
* **android:** read and write the saved LZ diagram as the web does ([f2c4495](https://github.com/colinmcfadden/avtactools/commit/f2c4495ddf668e18b67add06066e7f8119deb827))
* **android:** read the worst slope for every heading from a terrain analysis ([a91843f](https://github.com/colinmcfadden/avtactools/commit/a91843f571bbcbd8b999457557d2b2161910e7b5))
* **android:** reopen the set of routes that was open at launch ([ad88ef8](https://github.com/colinmcfadden/avtactools/commit/ad88ef823bf00231542ce511d2fa073e8314f8cb))
* **android:** run every open and closing mission pack, and keep what a pack refuses ([99bc493](https://github.com/colinmcfadden/avtactools/commit/99bc49363b2d8f325814455178efd665d4f21e76))
* **android:** run mission packs with the app ([0de2522](https://github.com/colinmcfadden/avtactools/commit/0de2522e2d78fc89f61c98f7fd3841f010720c14))
* **android:** saved sets of local points, imported from an LPS file ([394c00b](https://github.com/colinmcfadden/avtactools/commit/394c00ba136d4bde620c7117976046dfd59cf66a))
* **android:** saved sets of routes, drawing a route, and the route scene ([463b0e0](https://github.com/colinmcfadden/avtactools/commit/463b0e004e40d72398fa4a9e835677e26a950668))
* **android:** say how an open pack stands on its chip ([ce9cd71](https://github.com/colinmcfadden/avtactools/commit/ce9cd710dfd2496d54f18f3992e67893b37e0133))
* **android:** say what became of an invitation link, in the app's own toast ([9c23690](https://github.com/colinmcfadden/avtactools/commit/9c236903a74b6c9348df5a6f98cee7646e2de22c))
* **android:** send nothing while the app is below the server's minimum version ([544cd58](https://github.com/colinmcfadden/avtactools/commit/544cd5890243a754ef2f3a2c04bab482a29a7884))
* **android:** send, catch up and follow one mission pack, offline included ([c228381](https://github.com/colinmcfadden/avtactools/commit/c2283816d55fb955f8619e0f601e36b1f1fbc0ac))
* **android:** set a route point's type from its bar ([e074aac](https://github.com/colinmcfadden/avtactools/commit/e074aac82e510a6c0ab1a7f88aebc7616c20a65b))
* **android:** set up, review and share a mission brief on a tablet ([a527beb](https://github.com/colinmcfadden/avtactools/commit/a527beb35016edc2e1eb3126730ff3b45a28c80f))
* **android:** share a route with another app as a GPX or a Garmin flight plan ([75ba562](https://github.com/colinmcfadden/avtactools/commit/75ba5626189b86d312732940a1819c68d47a4d72))
* **android:** sketched routes: the model and everything that can be done to one ([2d448ed](https://github.com/colinmcfadden/avtactools/commit/2d448ed91ee25b6267a52d11a61af7a66f998b34))
* **android:** start packs at once, and say why nothing is sent when the app is too old ([2ce8a69](https://github.com/colinmcfadden/avtactools/commit/2ce8a69e719e2274c204497ab2f159684fe16b3a))
* **android:** stream the 3d view's point cloud from usgs's octree ([15bfd55](https://github.com/colinmcfadden/avtactools/commit/15bfd5557e611e4621d695b7cb2ffcd317d9c852))
* **android:** sync engine with an outbox, a pull by cursor and conflicts kept side by side ([573df32](https://github.com/colinmcfadden/avtactools/commit/573df32e571cc20fdced3e249461aa18e633fd03))
* **android:** sync mission briefs kept in the library ([6fed7a9](https://github.com/colinmcfadden/avtactools/commit/6fed7a90834a06f23a66f804ff5aebbaad152c08))
* **android:** sync saved point sets through the engine ([40eec6d](https://github.com/colinmcfadden/avtactools/commit/40eec6d81390d76b3f6929762c7b42c0a1815af9))
* **android:** sync saved routes through the engine ([765b4e3](https://github.com/colinmcfadden/avtactools/commit/765b4e35b485b39dccce29e4c06318b6d0c29bee))
* **android:** take the brief item kind in mission packs ([fda574e](https://github.com/colinmcfadden/avtactools/commit/fda574ed61d5c5febc5ba22d64cf3a3fc26d74de))
* **android:** tap a planning graphic on the map to hold it ([e25bd79](https://github.com/colinmcfadden/avtactools/commit/e25bd79beefa94dc209396ad39e1a25dbc659687))
* **android:** terrain masks for threats, units that drag by their symbol, a handle on the PZ arrow ([f5b17c4](https://github.com/colinmcfadden/avtactools/commit/f5b17c4934865746d734f07710c9e692c9809d83))
* **android:** the API client, sign-in, token refresh and typed routes ([5c6cc43](https://github.com/colinmcfadden/avtactools/commit/5c6cc43f6d71ab17f561d6da06f0f45abca63c3c))
* **android:** the app shell, an encrypted session, and the plans kept to one account ([beb23b2](https://github.com/colinmcfadden/avtactools/commit/beb23b2a292683c320be5314f7d3f059bfab3514))
* **android:** the held threat's card, remove all, and a mission export that carries its threats ([5898466](https://github.com/colinmcfadden/avtactools/commit/58984663534284c8ac19d28afedda2ba5496930b))
* **android:** the import review, the Import menu and the Imports panel ([93f6a49](https://github.com/colinmcfadden/avtactools/commit/93f6a49e957e2e61b3a55035d4f85b76fbbaa463))
* **android:** the library, with record times, duplicate and replace ([fa397c5](https://github.com/colinmcfadden/avtactools/commit/fa397c523eee14b4af3e042628a06a2924ed204d))
* **android:** the local points section of the sheet, and naming a route point after one ([1a376de](https://github.com/colinmcfadden/avtactools/commit/1a376def4a02a8920dac7dbd47e0deb9c95bb4a5))
* **android:** the lz/pz and routes panels in a mission pack ([c3e5b1f](https://github.com/colinmcfadden/avtactools/commit/c3e5b1fd470f3428484cab2852c04deff6999747))
* **android:** the LZ/PZ panel, with naming and the planning tools as modes ([bf20e17](https://github.com/colinmcfadden/avtactools/commit/bf20e17ae36b64b86c112f5dc085198fe3008f1d))
* **android:** the map is the root of the app, with MGRS under the crosshair ([1b6b47d](https://github.com/colinmcfadden/avtactools/commit/1b6b47db8d497e853186481b95d0880cf2b49278))
* **android:** the map's long-press menu, and open library from the saved toast ([b54a142](https://github.com/colinmcfadden/avtactools/commit/b54a142ad5f303f10781de3dedf18575882b5728))
* **android:** the members of a mission pack ([bcae2c5](https://github.com/colinmcfadden/avtactools/commit/bcae2c5bc70534bebc6776a51259c979001f7e66))
* **android:** the MGRS grid over the map, from the Layers menu ([34da95b](https://github.com/colinmcfadden/avtactools/commit/34da95bf18733fb33b28a5e1c3f3bd3eaa7b645e))
* **android:** the pack panel, with items, history and what changed since you looked ([cab3785](https://github.com/colinmcfadden/avtactools/commit/cab37855f2782e594acc238a19d34cc2ef9b6933))
* **android:** the redesign's palette, type, corners and icons ([4c47435](https://github.com/colinmcfadden/avtactools/commit/4c47435305d4a793b723a6f3ba9d9865b14172b7))
* **android:** the redesigned phone frame, with a three-height panel sheet ([1e058d2](https://github.com/colinmcfadden/avtactools/commit/1e058d22ec61143ad8d0468398bd67d78bc313dc))
* **android:** the routes panel, with naming, sketching and the route's plan as a page ([ead638a](https://github.com/colinmcfadden/avtactools/commit/ead638a738adf043a136eced3f95fe161f872d29))
* **android:** the routes tab, routes on the map, and tapping them ([200ebd6](https://github.com/colinmcfadden/avtactools/commit/200ebd619f9ed4db8568b86fa75c64d14494eb50))
* **android:** the sign-in screens, the .mil gate, and links from emails ([405ce1b](https://github.com/colinmcfadden/avtactools/commit/405ce1b2f96baa30dc79f44fb3a38d3090fd13c3))
* **android:** the tablet's frame, with a rail, the panel's pane and the plan beside the map ([b2e9e0d](https://github.com/colinmcfadden/avtactools/commit/b2e9e0db35a3fc3e2cf0332063a984bd6c2016fa))
* **android:** the teams, from the switcher's teams line ([9a2d8dd](https://github.com/colinmcfadden/avtactools/commit/9a2d8dd649df51dad3f63bbd24e1120eab2bbbd7))
* **android:** the threat picture, kept in memory and a short-lived sealed file, and written as a .ths ([29c0fa1](https://github.com/colinmcfadden/avtactools/commit/29c0fa1bb7e7bd330ae26d30df1d069d60310d55))
* **android:** the threats panel, with the threat's form as a full-screen dialog ([76dde1f](https://github.com/colinmcfadden/avtactools/commit/76dde1f681762989cf8f07e37bd261aaeb9b9fed))
* **android:** the uh-60 as the original green helicopter, to its real dimensions ([c8720b9](https://github.com/colinmcfadden/avtactools/commit/c8720b9948e30890d3a99df738502e5098cfca1b))
* **android:** the weather at an analysed landing zone, kept with its age ([f785d72](https://github.com/colinmcfadden/avtactools/commit/f785d72d67eea8e08bef66db08323109847f3b36))
* **android:** the workspace switcher, to open and leave a mission pack ([ae1c2b4](https://github.com/colinmcfadden/avtactools/commit/ae1c2b4a6f02c1f4de9c28a1882489f30118786c))
* **android:** typed elevation and wind calls, and the pure parts of forecast winds ([256e927](https://github.com/colinmcfadden/avtactools/commit/256e9279c6e54bcd6188ceec7940c98643e94ca5))
* **android:** update a pack's copy from its original, and save a pack's item to the Library ([147cb1f](https://github.com/colinmcfadden/avtactools/commit/147cb1f38483fb8536a682cc6c2428f71e2520e5))
* **android:** work out the operations an edit to a pack item becomes ([fe01c28](https://github.com/colinmcfadden/avtactools/commit/fe01c28037b6dd4477c6581f58e4936ae0001a63))
* **backend,android:** type the sign-up, reset and .mil calls, recorded from the real server ([fab4f85](https://github.com/colinmcfadden/avtactools/commit/fab4f8533c6471af306a3550c180e38b8a63e9c6))
* **backend,android:** typed terrain analysis calls and the contract for them ([ca61960](https://github.com/colinmcfadden/avtactools/commit/ca61960978565b68608b0bd11b424699c65fb28b))
* **backend,contracts:** keep lz surveys in books per team and publish signed pages ([82db463](https://github.com/colinmcfadden/avtactools/commit/82db4636fa4219e06c2c011c1305a925a0d314ab))
* **backend,frontend,android:** print a mission brief as a PDF ([bf54b8c](https://github.com/colinmcfadden/avtactools/commit/bf54b8c47d725a3a851691662aa34fbcd02f8a0d))
* **backend,frontend,contracts:** print strip charts in the brief's appendix ([a416b31](https://github.com/colinmcfadden/avtactools/commit/a416b310c68888673070a06ee884464d473d1493))
* **backend,frontend:** fly the survey's approach along the ground over the tallest tree ([3979e03](https://github.com/colinmcfadden/avtactools/commit/3979e03b1f7fe8c0134d30436ea7531ab3f99c15))
* **backend,frontend:** keep no best exit with a survey ([8e79e68](https://github.com/colinmcfadden/avtactools/commit/8e79e68d90ffdc065067583347b0a8ef024ba979))
* **backend,frontend:** say when the original changed and what updating a pack copy replaces ([634ef00](https://github.com/colinmcfadden/avtactools/commit/634ef000dd59a3bf76392db3b11cfa25d69fc9cc))
* **backend,frontend:** say which edits a refused pack batch had already taken ([718ed9c](https://github.com/colinmcfadden/avtactools/commit/718ed9c12b19557ca0e682b2d223bb33baf6a778))
* **backend:** a live stream for mission packs ([ddca0ff](https://github.com/colinmcfadden/avtactools/commit/ddca0ff60ac227dd7d50a71dc40c5a8a50dbab5f))
* **backend:** add GET /api/config and record which app signed in ([db8e915](https://github.com/colinmcfadden/avtactools/commit/db8e915f72009ddd2b5154d0ad1e656d6bcb235b))
* **backend:** add lz surveys with their asset store, measurement and routes ([4d7e31b](https://github.com/colinmcfadden/avtactools/commit/4d7e31b910dbe8f8cffe2a4fa487d42f73d67556))
* **backend:** answer the weather at a planned time ([eacdf0c](https://github.com/colinmcfadden/avtactools/commit/eacdf0c19103059d763952fb6812802745e3f756))
* **backend:** colour streamed lidar as the place looks ([fce778e](https://github.com/colinmcfadden/avtactools/commit/fce778e404fd643c89f2010a3855fcf75814c9b3))
* **backend:** draw the survey's lidar profile close in, as the old book's was ([0e83310](https://github.com/colinmcfadden/avtactools/commit/0e833100c2335b384596f74e641422d9e464140c))
* **backend:** import the old survey book's pages as drafts and measure each in the background ([17193a6](https://github.com/colinmcfadden/avtactools/commit/17193a6ce7189347aadbd315d846186bad6d75c7))
* **backend:** keep a notification outbox the apps poll ([c8b1ef6](https://github.com/colinmcfadden/avtactools/commit/c8b1ef67ea9fdaac4cf11048e6736fc25a6152b7))
* **backend:** keep a sign-in while the account is active, and give the web a cookie refresh ([3a6cfaf](https://github.com/colinmcfadden/avtactools/commit/3a6cfafa3bc876588566b1ac1a4fef186256be9c))
* **backend:** keep a survey's 3d capture with the camera that took it ([2191f0a](https://github.com/colinmcfadden/avtactools/commit/2191f0a27db657e00b08eb50dee552fc389760e6))
* **backend:** keep mission briefs in the library ([3be6779](https://github.com/colinmcfadden/avtactools/commit/3be6779ef15feacf09645f79a3975823c066c784))
* **backend:** keep mission packs off until they launch with the redesign ([23e0ce7](https://github.com/colinmcfadden/avtactools/commit/23e0ce776ebccd4cc705c5b3418f5957e31f4a0d))
* **backend:** leave the best exit off the survey's page ([7fc2ab1](https://github.com/colinmcfadden/avtactools/commit/7fc2ab154386af618aaa0f823dda148077293a48))
* **backend:** make cached lidar pieces photographic in the background ([3001640](https://github.com/colinmcfadden/avtactools/commit/30016406138e2e53173343ad376419be64913429))
* **backend:** measure and print the approach alone, not the exit ([d78ad3e](https://github.com/colinmcfadden/avtactools/commit/d78ad3e03c100efba3e8ace6f06b629bd1583956))
* **backend:** measure the approach from the touchdown point and where to descend from 100 ft AGL ([ea8ff0e](https://github.com/colinmcfadden/avtactools/commit/ea8ff0ef3a940efa6fe4980741ebae444088ef94))
* **backend:** measure what stands on and round an lz from the usgs lidar ([285a7bc](https://github.com/colinmcfadden/avtactools/commit/285a7bcc72c54a7c6c7d667107c118cf36160956))
* **backend:** mission packs, teams and invites ([d74d579](https://github.com/colinmcfadden/avtactools/commit/d74d57984a0fa95e774e2d83de9966a7fa577964))
* **backend:** open brief items in a pack to every member ([255d33d](https://github.com/colinmcfadden/avtactools/commit/255d33d4efc6719768c4fcca1e7c9d08db49ff24))
* **backend:** plan each way at 30° from the survey's height above the highest obstacle ([9fd69a0](https://github.com/colinmcfadden/avtactools/commit/9fd69a067d6ffca607b580fc69fdf1dcb91a4a59))
* **backend:** print the whole survey book as one pdf in the background ([bb3f257](https://github.com/colinmcfadden/avtactools/commit/bb3f25772fa55bf4ffdb50db680430ee01f0cd6c))
* **backend:** record the server's real responses for the native clients, and share the affiliation gate ([69bf31d](https://github.com/colinmcfadden/avtactools/commit/69bf31d854dd539dbb11417dcf2f9da19b9da82d))
* **backend:** refresh tokens, device sessions and account deletion for the native apps ([136b15f](https://github.com/colinmcfadden/avtactools/commit/136b15fa0f4ba33a9bb2d9453e810ad7e85b0ca6))
* **backend:** remember how far each person has looked in a pack ([582e2e6](https://github.com/colinmcfadden/avtactools/commit/582e2e61b71dba32e2fcdb6275e504a37cbb00d7))
* **backend:** report the worst slope for every heading ([ceabe10](https://github.com/colinmcfadden/avtactools/commit/ceabe10f8f64942ec18db057191c34317610b5bb))
* **backend:** send contour lines of the ground in and round an lz with its slope ([58be940](https://github.com/colinmcfadden/avtactools/commit/58be940dafe92517fad15bfc057af53a6802607b))
* **backend:** send the ground along lines through an lz for its slope profile ([b2950ac](https://github.com/colinmcfadden/avtactools/commit/b2950ac9c286fb55deef182024dab77408197f25))
* **backend:** sync a user's custom aircraft profiles like saved records ([ef279f9](https://github.com/colinmcfadden/avtactools/commit/ef279f974eb51f2cf0f11a0b0052c741d6bc9c24))
* **backend:** sync saved LZs, routes and point sets across devices ([b4bb5b7](https://github.com/colinmcfadden/avtactools/commit/b4bb5b7773e34268240066a6d57f581e7843adfd))
* **contracts:** add golden fixtures for mgrs, coordinates and planning ([49634e3](https://github.com/colinmcfadden/avtactools/commit/49634e3a5aefa2a91b9bbf511dac3d2fe6cca184))
* **contracts:** pin what a mission-pack operation does ([a87a790](https://github.com/colinmcfadden/avtactools/commit/a87a790c9449840bfa52a00dd4eeb14143f4e9bc))
* **frontend,android:** copy the map menu's grid when it is clicked ([ba5cd67](https://github.com/colinmcfadden/avtactools/commit/ba5cd6760d75d73c05504917b9626a24a0f72a97))
* **frontend,android:** let the slope profile be closed ([b294659](https://github.com/colinmcfadden/avtactools/commit/b2946596e84cc6a56c13226ee00bb37ec97128a7))
* **frontend,android:** show a helicopter on the slope profile ([097bf0b](https://github.com/colinmcfadden/avtactools/commit/097bf0ba39c8795c23ba7c4d80f9c9f838a7a982))
* **frontend,contracts:** compile a mission brief and work out its light data ([5f7b7a3](https://github.com/colinmcfadden/avtactools/commit/5f7b7a38889cc7863270f17f5f1e62f3d867c676))
* **frontend,contracts:** present a mission brief on the live sandtable ([c75d0c8](https://github.com/colinmcfadden/avtactools/commit/c75d0c81b3341bc402036ddf656c87aae35539aa))
* **frontend,contracts:** take brief edits in a read-only pack, in batches of their own ([1657c8d](https://github.com/colinmcfadden/avtactools/commit/1657c8d3be16c8b83de6fd81f7f132874c6cab0b))
* **frontend:** a client for every mission-pack, team and invitation route ([89e6ba3](https://github.com/colinmcfadden/avtactools/commit/89e6ba3d1e38d6e29081f7ef565fae71ff0afacf))
* **frontend:** accept a pack invitation from its link ([113ef66](https://github.com/colinmcfadden/avtactools/commit/113ef660bbb0fd78faf49bdc5dddddc3c40f60eb))
* **frontend:** accept a pack invitation from its link once signed in ([d231175](https://github.com/colinmcfadden/avtactools/commit/d23117592d160d681d16703b3cb12c92917db326))
* **frontend:** add an mgrs grid switch to the map's layers control ([06683d6](https://github.com/colinmcfadden/avtactools/commit/06683d67a4966c03147481c398bfb8fa931f6a20))
* **frontend:** add the lz survey book and its editor for admins ([aa2873f](https://github.com/colinmcfadden/avtactools/commit/aa2873f3123bcdd9d461c3e4aa6329a1c4e2cec5))
* **frontend:** capture the lz's 3d view for its survey page ([dc26894](https://github.com/colinmcfadden/avtactools/commit/dc2689473ad813967ee47d3f90d1b011ddc9faf6))
* **frontend:** convert utm and mgrs grids back to latitude and longitude in the browser ([9d35be0](https://github.com/colinmcfadden/avtactools/commit/9d35be093b81f74c0c080c388ff64db781b290d7))
* **frontend:** draw contour lines on the slope map ([f213ada](https://github.com/colinmcfadden/avtactools/commit/f213ada54a20ee9e4d5ea129434f0f17aaacf8f9))
* **frontend:** draw the mgrs grid on the map in panes of its own ([7995ec2](https://github.com/colinmcfadden/avtactools/commit/7995ec2677e69dcded4d574f7b4d1588d7b7ab33))
* **frontend:** draw the slope profile along and across the landing heading ([94fe3cc](https://github.com/colinmcfadden/avtactools/commit/94fe3cc0a03f44e455296eea4393cbd25911498b))
* **frontend:** import the old book into a survey book and show what each page left to check ([ca40101](https://github.com/colinmcfadden/avtactools/commit/ca401013fdc0601eb15f4cd3b8ff4b68086d3339))
* **frontend:** judge the landing heading and recommend one on the slope tile ([9d76045](https://github.com/colinmcfadden/avtactools/commit/9d76045cffd26987ee40b21825d0f60a32964918))
* **frontend:** keep a mission pack's route sets in step with the sketch ([4dcdae0](https://github.com/colinmcfadden/avtactools/commit/4dcdae0e81faf9f26b5e89def2a01ecb71f03d31))
* **frontend:** keep an open mission pack in step with everyone's edits ([6faf7fc](https://github.com/colinmcfadden/avtactools/commit/6faf7fcbf43bf3aca54702fb6e8592ee3b5989da))
* **frontend:** keep the mgrs grid off exported lz cards ([115bbaa](https://github.com/colinmcfadden/avtactools/commit/115bbaaf8c5dab2edd906e84d8ab0ef94e9ba539))
* **frontend:** keep the workspace's LZ/PZs in step with an open mission pack ([7be301a](https://github.com/colinmcfadden/avtactools/commit/7be301a04956e06bd81b45f0bd9588edf96a5057))
* **frontend:** make the slope map's opacity adjustable ([4bac2c4](https://github.com/colinmcfadden/avtactools/commit/4bac2c4d80e756a36b44da43873159dbf9446456))
* **frontend:** open survey books by team, publish a survey and preview its page ([ec9f433](https://github.com/colinmcfadden/avtactools/commit/ec9f433a5150efdddacd2fa966a5ec1b5197ff5e))
* **frontend:** open the workspace switcher from the library's footer link to mission packs ([4d7a53d](https://github.com/colinmcfadden/avtactools/commit/4d7a53d50f18cb9cb89fd160885b273369448039))
* **frontend:** pack actions, keeping refused edits, and how far each person has looked ([b326152](https://github.com/colinmcfadden/avtactools/commit/b3261527abcc14c8bf8cbe38c87305b2f15d0f46))
* **frontend:** print and download the whole survey book from its page ([5099100](https://github.com/colinmcfadden/avtactools/commit/5099100683b94889a78850935a74fc60a21420a3))
* **frontend:** rename, describe, leave, delete and hand over a mission pack ([c9afa29](https://github.com/colinmcfadden/avtactools/commit/c9afa2921c7c276f4fcd1a6c3f8fa61ca86c056c))
* **frontend:** save the lz measurement line with the diagram ([81c2461](https://github.com/colinmcfadden/avtactools/commit/81c2461edf1898b8928481b515138ea192a084b4))
* **frontend:** set up a mission brief from the plan ([98b0c37](https://github.com/colinmcfadden/avtactools/commit/98b0c37f86a8df84f45b257338dbbb74eb04094f))
* **frontend:** share a pack with a team to edit or only to view, and change it later ([daed68d](https://github.com/colinmcfadden/avtactools/commit/daed68d814dfa826d87405d9afd3480435e683e1))
* **frontend:** show a mission pack's point sets beside one's own, in step with the pack ([a2f9ea9](https://github.com/colinmcfadden/avtactools/commit/a2f9ea92c0d18f6d18d5a092a88dd02b2ae6183c))
* **frontend:** show a spinner while a slow action is under way ([6dd37c0](https://github.com/colinmcfadden/avtactools/commit/6dd37c0f3ee5440049ec888b753aac5233175cdd))
* **frontend:** show each way's plan and type the approach's angle in place of it ([fbc3662](https://github.com/colinmcfadden/avtactools/commit/fbc3662755623c0f6f9596d45859c51a79a8fbf0))
* **frontend:** show the approach alone in the lidar panel ([1517b2e](https://github.com/colinmcfadden/avtactools/commit/1517b2efea0737c30e4e7857d96df562e016b523))
* **frontend:** show the approach's descent from 100 ft AGL and let the editor type its angle ([c9628db](https://github.com/colinmcfadden/avtactools/commit/c9628db8892571ab5302601aae1ec50f324bdacf))
* **frontend:** show what the lidar found in the survey editor and on its map ([5824739](https://github.com/colinmcfadden/avtactools/commit/582473954f5195399a36988f2be22d1fd6c44f44))
* **frontend:** stay signed in with a refresh cookie and a token kept in memory ([ed50c2e](https://github.com/colinmcfadden/avtactools/commit/ed50c2e74db403f8a496297d025f484249a831a6))
* **frontend:** the mission pack screens and manage teams ([6c3ec8f](https://github.com/colinmcfadden/avtactools/commit/6c3ec8f668e554e391da16281e45a5f3cf0b481e))
* **frontend:** the redesigned frame: top bar, dock, save flows, library and imports ([537dd51](https://github.com/colinmcfadden/avtactools/commit/537dd51e1aa5f8bf063a7e4dbaa339d342a1287d))
* **frontend:** turn an editor's change to a pack item into pack operations ([dc792ad](https://github.com/colinmcfadden/avtactools/commit/dc792ad0ac4497c2da035a1b219fea489d83303c))
* **frontend:** work out the mgrs grid lines and labels for a map view ([5246942](https://github.com/colinmcfadden/avtactools/commit/5246942b092a8bfdbd70ab3d1910e55c6ff32a5a))
* stream the 3d view's point cloud from usgs's octree ([ca8ada4](https://github.com/colinmcfadden/avtactools/commit/ca8ada4e9cb9b4768d6c1327f70206dc2b58a725))
* version the api apart from the product, and fail ci on a breaking change ([12c20c9](https://github.com/colinmcfadden/avtactools/commit/12c20c9ff650dafb9e9cf96dbdd15b023788bc7b))

### Bug Fixes

* **android:** a 404 on a create is a server problem, not a record that is gone ([4ecddce](https://github.com/colinmcfadden/avtactools/commit/4ecddcedd9f88d0163f3571cd192bb9c5aa5822d))
* **android:** a route point moved in a mission pack reaches the other device in place ([41bdcf0](https://github.com/colinmcfadden/avtactools/commit/41bdcf0f79dda8c497c6c9113b0935ac60e016b6))
* **android:** an open diagram follows the new id the sync engine gives it ([c04d539](https://github.com/colinmcfadden/avtactools/commit/c04d539b41848445bf2beeb22ce866c6ebf2cee4))
* **android:** draw aircraft at their true size on a device ([f34cf24](https://github.com/colinmcfadden/avtactools/commit/f34cf24ce07f387177e2d012a860d3da0ef67bbe))
* **android:** draw the LZ/PZ plan page at the design's sizes ([304142a](https://github.com/colinmcfadden/avtactools/commit/304142adf51bf0adedff24a8c270d37de42821e3))
* **android:** give the sandtable's focus back, and name its pack and keys ([e4ca4aa](https://github.com/colinmcfadden/avtactools/commit/e4ca4aafcb310b82a913e1395d57bb93e1f88a0f))
* **android:** keep a route's plan values whole and a point's first values apart in a mission pack ([c6aedd5](https://github.com/colinmcfadden/avtactools/commit/c6aedd5f94726c6afb85af26de16122fe5845fd7))
* **android:** keep a threat's range rings continuous across the antimeridian, and never leave a threat blank ([6404151](https://github.com/colinmcfadden/avtactools/commit/6404151957f04460dcc7186756b3f146eb97e317))
* **android:** keep back, focus and the build lookup with the 3d view ([60be45a](https://github.com/colinmcfadden/avtactools/commit/60be45a2228f2bbd9be4bba060f0b6bce41ad601))
* **android:** keep fetched ground and winds only for a point still where they were asked ([e8e6627](https://github.com/colinmcfadden/avtactools/commit/e8e66278e3d7e97490ded0e64b2c3661bcf05a12))
* **android:** keep one account's invitation from the next, and tell every try ([2462d4a](https://github.com/colinmcfadden/avtactools/commit/2462d4a705817bbff5189db97a16f7e7a7151770))
* **android:** keep one machine's JDK choice out of the shared Gradle settings ([7be70fe](https://github.com/colinmcfadden/avtactools/commit/7be70fe277e72e09b8fd8ff2db25dbe47646be71))
* **android:** keep the shell's pack gate and its way to the back in order on a device ([e832c62](https://github.com/colinmcfadden/avtactools/commit/e832c62cf1481791e1b0430e3f6b9092530e82a9))
* **android:** keep the uh-60's tail loop whole at high zoom ([106904c](https://github.com/colinmcfadden/avtactools/commit/106904c38b83ea14ad3b3dfafca24a8b02399f56))
* **android:** launch from the run script without a false error ([e1d8dcc](https://github.com/colinmcfadden/avtactools/commit/e1d8dcc880eb8c496e06301ccf4931fcce48cd21))
* **android:** let a background sync restore the session in a process the app did not start ([5510381](https://github.com/colinmcfadden/avtactools/commit/5510381c0fe21e9ab38b841e2db39a0cd86c6b4c))
* **android:** name a turned doghouse and its new heading, as the web does ([e43f9cc](https://github.com/colinmcfadden/avtactools/commit/e43f9cc266ba48908aeae732a9f3b01e6a592f3d))
* **android:** pass lint, and the tests on a Windows machine with a newer JDK ([9d4d128](https://github.com/colinmcfadden/avtactools/commit/9d4d128f03dbe519142c439a4f118ced2d6d5661))
* **android:** put down a held lz/pz when the one worked on closes ([9b747a2](https://github.com/colinmcfadden/avtactools/commit/9b747a225bf2b381083aee74d44114d518ac0b55))
* **android:** put the 3d view's buffers up as long as what they hold ([2b68401](https://github.com/colinmcfadden/avtactools/commit/2b68401c540b967f547959206f39ff01661c6d46))
* **android:** put the modifier first among ThreatsHost's optional parameters ([4b4b625](https://github.com/colinmcfadden/avtactools/commit/4b4b62594e825506e772c00c27b56914e8339d7b))
* **android:** read a mission on a device, where the xml parser refuses a setting the jvm accepts ([f61459a](https://github.com/colinmcfadden/avtactools/commit/f61459afcf19fecc3790c5b8acb300448a8c6454))
* **android:** read a response off the main thread ([feeb9dc](https://github.com/colinmcfadden/avtactools/commit/feeb9dc04451f957bf84d40bf1a8a803690ee8de))
* **android:** read library documents, sends and mission files longer than a cursor window ([3a616ca](https://github.com/colinmcfadden/avtactools/commit/3a616ca5365d0ca237a56305e71148eb76149465))
* **android:** read the session for notifications without the offline rule ([f649933](https://github.com/colinmcfadden/avtactools/commit/f649933e2086f3a30e359755458b87d30d51c1ee))
* **android:** reword two mission-pack refusals ([ed16c7b](https://github.com/colinmcfadden/avtactools/commit/ed16c7bdfdd646bcb3542d05b31deb3a77c140f8))
* **android:** send an lz/pz graphic's place whole in a mission pack ([4fc9188](https://github.com/colinmcfadden/avtactools/commit/4fc91888a723c26f6d1a0081397ca0660402c14b))
* **android:** send nothing below the minimum, invitations included, and hear a raised one ([a2ff989](https://github.com/colinmcfadden/avtactools/commit/a2ff9895207c7f84ff40fa44a220dda72a0e0da2))
* **android:** send what an analysis found whole in a mission pack ([25be4b7](https://github.com/colinmcfadden/avtactools/commit/25be4b77a517ee8b88ed7646b7d4a783ae2cf338))
* **android:** settle the 3d view's point budget and keep its ground under the trees ([dd1cdfa](https://github.com/colinmcfadden/avtactools/commit/dd1cdfa0c26f3e5981230bc2d4c0074b01afdc53))
* **android:** settle the 3d view's routes and the final reviews' findings ([efb7dd1](https://github.com/colinmcfadden/avtactools/commit/efb7dd1d84b1a9a05d6eb3d5beb7bbdba786ed5d))
* **android:** show a change sent as several operations as one line of a pack's history ([67e895e](https://github.com/colinmcfadden/avtactools/commit/67e895ea5d595e4b59c77a92072dfa3a88a1701f))
* **android:** stop the clock form and the unit builder putting back what changed under them ([afd535c](https://github.com/colinmcfadden/avtactools/commit/afd535cd2ce30ea0f2c5b8450a21329312d8a9f5))
* **android:** what the review of the redesigned screens found ([2ba0006](https://github.com/colinmcfadden/avtactools/commit/2ba00069d215a63118087478ba2b4bd447d31ef7))
* **android:** write the byte order mark the rename trims as an escape, as lint requires ([51f569d](https://github.com/colinmcfadden/avtactools/commit/51f569d3990b4a210caaff4fbaa1a3cdde6b75c9))
* **backend,frontend:** count everyone who can open a pack, its shared team included ([bdf11ed](https://github.com/colinmcfadden/avtactools/commit/bdf11edde65a40b4d3db89108a4560ed6cf26029))
* **backend,frontend:** refuse a survey boundary no lz could have and a diagram that is not json ([a913071](https://github.com/colinmcfadden/avtactools/commit/a913071ce119c869a737f577060a72eb48dc9e95))
* **backend:** give the uh-60 its 15° nose-up and 6° nose-down slope limits ([3175f3e](https://github.com/colinmcfadden/avtactools/commit/3175f3e52713dd4d61b386ea82e27d7e21664898))
* **backend:** hold a signed survey page to what was measured and signed ([08ce3a4](https://github.com/colinmcfadden/avtactools/commit/08ce3a4f315b83fbf389dde56cb52dbf5fac859d))
* **backend:** import odd pages with warnings and keep the measuring going through faults ([134af99](https://github.com/colinmcfadden/avtactools/commit/134af99ac7864ef2e77f33c45310cadcaa42ef3a))
* **backend:** keep a signed survey page free of the measure-again note ([0856fec](https://github.com/colinmcfadden/avtactools/commit/0856fec758a99e253ca865c812f13e48459cad80))
* **backend:** keep one live 3d capture, and caption it as the editor says it ([acf6eb9](https://github.com/colinmcfadden/avtactools/commit/acf6eb9a5606a6a71df5a6aa298b1c0df8c9135d))
* **backend:** keep the contours' labels 40 m apart ([d4fd4f6](https://github.com/colinmcfadden/avtactools/commit/d4fd4f6c86c936ef7cda55f02cd12001a1187073))
* **backend:** link a pack copy of a pre-sync library record to that record, not another ([fcca0e0](https://github.com/colinmcfadden/avtactools/commit/fcca0e0d1f22711d85cf404921ff4350262954bb))
* **backend:** measure the lidar's obstacles above the noise, and bounded ([baa8ffa](https://github.com/colinmcfadden/avtactools/commit/baa8ffabe3a6675cbbd94022d111ee62b9226f58))
* **backend:** pin psycopg2-binary 2.9.11, which has wheels for python 3.14 ([3e41e41](https://github.com/colinmcfadden/avtactools/commit/3e41e41918b0bb895dfc7bebb56b4f5479886e32))
* **backend:** print the survey book within its space, at its size, and once ([8a22624](https://github.com/colinmcfadden/avtactools/commit/8a22624f14635eb797768c8291505c6a860f5ae4))
* **backend:** read a pack's role and deletion again once its lock is held, on every route that writes ([f204a8f](https://github.com/colinmcfadden/avtactools/commit/f204a8f588ae7214980c6159eb9606494a2eab11))
* **backend:** see the local DEMs on a machine that booted moments ago ([75f9450](https://github.com/colinmcfadden/avtactools/commit/75f945085151e5e74d983d6a45ca81692bf83ec9))
* **backend:** stop the lidar routes giving away where 3d views were built ([42e8fe6](https://github.com/colinmcfadden/avtactools/commit/42e8fe63588afdb1f10b8a38fb00d319179f41b9))
* **backend:** trust a .mil address only once its code is entered ([eedcde6](https://github.com/colinmcfadden/avtactools/commit/eedcde6677880edacf4b498a8e8af3fa87424e4c))
* **backend:** write the threat kmz's mask outlines as simple polygons so foreflight opens them ([4ba5732](https://github.com/colinmcfadden/avtactools/commit/4ba573271085b5abacdf7dae0294847bc8e62ab9))
* cleanup ([317237d](https://github.com/colinmcfadden/avtactools/commit/317237dbcb957ca8887da3f6c8cbc3b32f6010d2))
* **frontend,android:** draw the slope profile's toggle as the app's dropdowns are ([13a504a](https://github.com/colinmcfadden/avtactools/commit/13a504a1f582f245b5ba4c4af54a8e030f829d21))
* **frontend,android:** frame the 3d view's sphere across the narrower side of the view ([b069ba6](https://github.com/colinmcfadden/avtactools/commit/b069ba6e67563c67c2d2005d0bab83661e0a8176))
* **frontend:** ask for a cookie session only of a server that offers one ([ce80285](https://github.com/colinmcfadden/avtactools/commit/ce80285880faca3d8823316442f27daacda12b42))
* **frontend:** capture the page's whole 3d box at its detail, and never hang ([044ac38](https://github.com/colinmcfadden/avtactools/commit/044ac38dd36208c65d022eb4cf97249942a6058f))
* **frontend:** choose who to invite to a pack first, then send with the invite button ([98dde05](https://github.com/colinmcfadden/avtactools/commit/98dde05d55d502d417c9dc40cb49c6c1b0e7315f))
* **frontend:** clear original changed once an item is updated from its original ([b2096e7](https://github.com/colinmcfadden/avtactools/commit/b2096e7ca455cf1d2ff274285b9a8ce6cd8397af))
* **frontend:** count a pack's shared team when adding a library item to it ([f082f18](https://github.com/colinmcfadden/avtactools/commit/f082f1825cb8d3c51b0a2865889aa7e4236c2ddc))
* **frontend:** cut explanatory text the controls already say ([2855eb6](https://github.com/colinmcfadden/avtactools/commit/2855eb64412395fb6f409a4b39226409804c0872))
* **frontend:** draw grid zone boundaries as white dashes, not a route-like orange ([d549984](https://github.com/colinmcfadden/avtactools/commit/d5499842601fe14b96653fe4313b33bddae57fae))
* **frontend:** draw the map handles with the redesign's move and rotate icons ([ae4027f](https://github.com/colinmcfadden/avtactools/commit/ae4027f78cca512fed0aa6c9a820fef9b4a1e496))
* **frontend:** drop the sidebar import msnx button that skipped the review ([3a40ed2](https://github.com/colinmcfadden/avtactools/commit/3a40ed2e5fcfd927db738838a54b64a7980bf580))
* **frontend:** end a map move quietly when the pack turns read-only mid-gesture ([0d2fd64](https://github.com/colinmcfadden/avtactools/commit/0d2fd646f5168391967e3c025b68eac84383fcf4))
* **frontend:** end the map at the bottom sheet's top on narrow windows ([dc099c9](https://github.com/colinmcfadden/avtactools/commit/dc099c93870d661f31e55ff6af96a3469c8881d0))
* **frontend:** even out the padding of the dock and its menus ([532f957](https://github.com/colinmcfadden/avtactools/commit/532f957417a521696a214610607df1ca82db1fe8))
* **frontend:** export an lz card from the lz/pz card menu and in a read-only pack ([e58bc3e](https://github.com/colinmcfadden/avtactools/commit/e58bc3e0b676c602801efff43777ae2198f92446))
* **frontend:** follow an imported survey while it is measured, and keep focus in the import ([07e10d3](https://github.com/colinmcfadden/avtactools/commit/07e10d3512730c343b2114cd5541cf7f359ca8e7))
* **frontend:** follow the doghouses' headings only when the doghouses change ([8dc8c81](https://github.com/colinmcfadden/avtactools/commit/8dc8c81a66a3a3d173f0d196916abcaaad56a377))
* **frontend:** give the invite field its own line on a phone ([da5179e](https://github.com/colinmcfadden/avtactools/commit/da5179e1d0c9349c1d2d84be10533684e4b3aab0))
* **frontend:** give up a closed pack's waiting edits at sign-out instead of sending them tokenless ([60c8421](https://github.com/colinmcfadden/avtactools/commit/60c8421e5c74f7e6d235c1478ae608dd045c7dc4))
* **frontend:** keep a grid value labelled at the bottom or right inside the view ([4e4c722](https://github.com/colinmcfadden/avtactools/commit/4e4c7221c605b40d2528e5eaf8bb85f012cc6a34))
* **frontend:** keep a menu opened inside a dialog above it ([a9229f4](https://github.com/colinmcfadden/avtactools/commit/a9229f4be071d003253f0aa408c88a48d2c3a406))
* **frontend:** keep a new team chosen and name the team a pack is shared with ([c7fa50d](https://github.com/colinmcfadden/avtactools/commit/c7fa50ddfae2f6582f287c8bedb2143a4b7ee53a))
* **frontend:** keep imported points when the pack refuses them, and say where they went ([2c7535c](https://github.com/colinmcfadden/avtactools/commit/2c7535c711244d2bd48dd5a844f0ef2f09215162))
* **frontend:** keep the book's PDF panel true to the server ([1844e88](https://github.com/colinmcfadden/avtactools/commit/1844e88bc5252ae66eafa99d7e9cd6c8200423f7))
* **frontend:** keep the brief's weather when a refresh gets no answer ([b4a2e3d](https://github.com/colinmcfadden/avtactools/commit/b4a2e3da3f80a1ecae70125650cd0017816fd9bc))
* **frontend:** keep the frame's text at 11 px or more ([c9b42d2](https://github.com/colinmcfadden/avtactools/commit/c9b42d224bbc695817c2a65a6ed05522c4429b1c))
* **frontend:** keep the pack history current while it is open ([1520ff4](https://github.com/colinmcfadden/avtactools/commit/1520ff41280bedba2646a4e18b007d0741338df8))
* **frontend:** keep two saved route sets apart when both open before a render ([c811abc](https://github.com/colinmcfadden/avtactools/commit/c811abc70be57fb86c41f217348494a2dbc98ff8))
* **frontend:** label grid lines on the opposite edge when an edge is covered ([df851a2](https://github.com/colinmcfadden/avtactools/commit/df851a2d86e5ebb8133348772245c22fbc31e864))
* **frontend:** make add threat the threats panel's primary button ([50c14dd](https://github.com/colinmcfadden/avtactools/commit/50c14ddebfb566f65e47557c13bba36b43664592))
* **frontend:** measure changed-since-you-looked from the opened pack's own marker ([816cd66](https://github.com/colinmcfadden/avtactools/commit/816cd6690130fab7f58d6499de6449f8cf911f14))
* **frontend:** name a turned doghouse and its new heading in a pack's history ([a5d3aed](https://github.com/colinmcfadden/avtactools/commit/a5d3aeddbe32d154944e33c5c1af42435c2c2a39))
* **frontend:** never offer back a pack edit the server took, and keep a pack's finish whole ([67554b1](https://github.com/colinmcfadden/avtactools/commit/67554b18f5a9b521f32f1534f3cef0c9d5279003))
* **frontend:** offer analyze and draw boundary on mobile ([ed22487](https://github.com/colinmcfadden/avtactools/commit/ed22487e65feb2166913384b70210969e7fe3322))
* **frontend:** offer no map edits or renames in a read-only pack ([41f7de1](https://github.com/colinmcfadden/avtactools/commit/41f7de1dfad1edc27b783ed95d4036db9dfb111d))
* **frontend:** open a saved route set beside unsaved sketches, not into them ([1a01140](https://github.com/colinmcfadden/avtactools/commit/1a0114032e63a69c7efb41586813cd1032c76ef7))
* **frontend:** put a team's role and share button below its name on a phone ([1093297](https://github.com/colinmcfadden/avtactools/commit/1093297d4dd9e537a45113ace5d69c463d1c878c))
* **frontend:** report route exports in a toast and make no .ths when the .msnx fails ([3095b45](https://github.com/colinmcfadden/avtactools/commit/3095b4527e288b14abb626d47946109bafee775f))
* **frontend:** retry a pack that failed to open, and name it in the top bar meanwhile ([d4cc63a](https://github.com/colinmcfadden/avtactools/commit/d4cc63a2ec31e1d744249bd743e6ac75f377e690))
* **frontend:** say "1 member" for a one-person team in the members dialog's share list ([a145859](https://github.com/colinmcfadden/avtactools/commit/a14585900dc86e862f1d6aa6499060ba620b5dca))
* **frontend:** say a survey never measured is not measured, not that it has nothing to look at ([4bca9e0](https://github.com/colinmcfadden/avtactools/commit/4bca9e0e1a116c7957b927987bfc0ad57f453bcf))
* **frontend:** say re-analyze and what an analysis unlocked in the sidebar's analysis card ([31b72ad](https://github.com/colinmcfadden/avtactools/commit/31b72aded7a40f374ff6960372d594e1d7584a20))
* **frontend:** say when a library list could not be loaded instead of nothing saved ([8acbed8](https://github.com/colinmcfadden/avtactools/commit/8acbed8b4ec967c33a309cf8df29a2b7aa96ff7a))
* **frontend:** send a closed pack's waiting edits instead of dropping them ([d178e88](https://github.com/colinmcfadden/avtactools/commit/d178e88c21002da7274781c0587e119dd8d570d5))
* **frontend:** set a route's clock when a mission pack left a point's values null ([3ccfc71](https://github.com/colinmcfadden/avtactools/commit/3ccfc71f1a3fbf134e68c7d490adb16f7e9b4709))
* **frontend:** show a change sent as several operations as one line of a pack's history ([b0860f5](https://github.com/colinmcfadden/avtactools/commit/b0860f5bafddbc589717f62ec0e9d16f97a38498))
* **frontend:** show a survey measured before it changed, and drop late answers ([9b981cc](https://github.com/colinmcfadden/avtactools/commit/9b981ccd34d7cab94c3eeb673b61dfd75c29248e))
* **frontend:** show another team after leaving or deleting one ([282da12](https://github.com/colinmcfadden/avtactools/commit/282da1236d65d5b028f3bd703adef4c2bcafa5e9))
* **frontend:** show the lidar's obstacles as they are measured now ([3f5b8b6](https://github.com/colinmcfadden/avtactools/commit/3f5b8b6cdd78904b56ce997cb7d3702a181a685f))
* **frontend:** show the lz/pz tools as off until the lz/pz is analysed ([2252c42](https://github.com/colinmcfadden/avtactools/commit/2252c42465ea10bc955d52b6e84a496202f90753))
* **frontend:** show the phone's capture and export above the sheet ([6a780e5](https://github.com/colinmcfadden/avtactools/commit/6a780e5516e6343f0fbf43f16f84b6c8cabcf729))
* **frontend:** show the wind speed's unit again over the route plan's wind boxes ([b92bc4f](https://github.com/colinmcfadden/avtactools/commit/b92bc4f0d8efda60a481999363bb5d69b9c7d962))
* **frontend:** stack the floating windows and alerts under menus, dialogs and toasts ([c3f6c78](https://github.com/colinmcfadden/avtactools/commit/c3f6c78a1a88a1b22acc2526e63a6cb07da67d84))
* **frontend:** stay in a pack opened while leaving or deleting another ([ffd9ae0](https://github.com/colinmcfadden/avtactools/commit/ffd9ae034f4ae6bb7b7b3380d3d8821591567179))
* **frontend:** title an imported mission's route set by its .msnx file name ([0257835](https://github.com/colinmcfadden/avtactools/commit/02578358650eec729d86400151b6986f82faf27c))
* **frontend:** zoom the map a whole level per wheel notch and per button press ([9c6e5b0](https://github.com/colinmcfadden/avtactools/commit/9c6e5b0ee7691b40967495d75215720c94e49ae3))
* play the bundled falcon screech sound ([114e36c](https://github.com/colinmcfadden/avtactools/commit/114e36c01cde198904cf6699b39adeb6ecffb824))
* **tools:** read the old book's thousands, footer labels and untitled profiles ([3843b7f](https://github.com/colinmcfadden/avtactools/commit/3843b7f26d5e4ad2b00122324ff0e4c548966bd0))

### Reverts

* Revert "docs: record that a survey keeps no best exit" ([a76e6d5](https://github.com/colinmcfadden/avtactools/commit/a76e6d57a45c6c8257a913d0ed5cfba70acfcbc7))
* Revert "docs: record that the survey's page prints no best exit" ([ab96008](https://github.com/colinmcfadden/avtactools/commit/ab9600878e48cdab214f49abba10abbd9d4dee99))
* Revert "feat(backend,frontend): keep no best exit with a survey" ([cdcbc4a](https://github.com/colinmcfadden/avtactools/commit/cdcbc4a520e88c284e9a193fe44198c21a3aac6e))
* Revert "feat(backend): leave the best exit off the survey's page" ([caee0a4](https://github.com/colinmcfadden/avtactools/commit/caee0a4afb69195837ffee2c163334d88560ab9f))

## [1.7.8](https://github.com/colinmcfadden/avtactools/compare/v1.7.7...v1.7.8) (2026-10-29)


### Bug Fixes

* **backend:** write the threat kmz's mask outlines as simple polygons so foreflight opens them ([dae40a0](https://github.com/colinmcfadden/avtactools/commit/dae40a0d7b053dc26e2694ba0fef86d9305a05f9))

## [1.7.7](https://github.com/colinmcfadden/avtactools/compare/v1.7.6...v1.7.7) (2026-10-02)


### Bug Fixes

* colour collection point clouds from the right place ([d4dd21b](https://github.com/colinmcfadden/avtactools/commit/d4dd21b571118a05a69dfbcf9e9ad2aa8f988aea))

## [1.7.6](https://github.com/colinmcfadden/avtactools/compare/v1.7.5...v1.7.6) (2026-10-02)


### Bug Fixes

* load Cesium from its prebuilt bundle ([b3a9fc7](https://github.com/colinmcfadden/avtactools/commit/b3a9fc72e7c8786f72c89016502556583c40a07a))

## [1.7.5](https://github.com/colinmcfadden/avtactools/compare/v1.7.4...v1.7.5) (2026-10-02)


### Bug Fixes

* read WKT coordinate systems from the downloaded LiDAR ([530eae3](https://github.com/colinmcfadden/avtactools/commit/530eae3b4d831fae3e7c0b8a74a54f91b952d111))

## [1.7.4](https://github.com/colinmcfadden/avtactools/compare/v1.7.3...v1.7.4) (2026-10-02)


### Bug Fixes

* keep the build service Dockerfile intact under Coolify ([3f10a82](https://github.com/colinmcfadden/avtactools/commit/3f10a82ace935a28988dcc47e5b3d055f53bd524))

## [1.7.3](https://github.com/colinmcfadden/avtactools/compare/v1.7.2...v1.7.3) (2026-10-02)


### Bug Fixes

* name the Postgres driver and pin SQLAlchemy ([e48dbe1](https://github.com/colinmcfadden/avtactools/commit/e48dbe10c5c265c99e9adf01c58a60abdeed6958))

## [1.7.2](https://github.com/colinmcfadden/avtactools/compare/v1.7.1...v1.7.2) (2026-10-02)


### Bug Fixes

* install the geoid grids where pyproj reads them ([fc64f0e](https://github.com/colinmcfadden/avtactools/commit/fc64f0ea2365381579fe68fed6efe450b9ee16d2))

## [1.7.1](https://github.com/colinmcfadden/avtactools/compare/v1.7.0...v1.7.1) (2026-10-02)


### Bug Fixes

* add pyproj to backend requirements ([844ce24](https://github.com/colinmcfadden/avtactools/commit/844ce248279af5a9f61988074333cb9b3882c72b))

# [1.7.0](https://github.com/colinmcfadden/avtactools/compare/v1.6.0...v1.7.0) (2026-10-02)


### Bug Fixes

* a downloaded collection can hold more than one survey ([8010f36](https://github.com/colinmcfadden/avtactools/commit/8010f362d43d4f0289fad426d58a82fc16c6201c))
* apply Secure cookie and real client IP behind any declared proxy ([a4475a0](https://github.com/colinmcfadden/avtactools/commit/a4475a010e29cd82f606affd96995128bb5ef37d))
* apply startup secret checks to all production hosts ([98fbbdd](https://github.com/colinmcfadden/avtactools/commit/98fbbddc6aac94bfe5fd56baafe859cf42b9d3fb))
* draw the slope heat map at about half opacity ([b7f4ae3](https://github.com/colinmcfadden/avtactools/commit/b7f4ae3ef25520ac5159c7a0753e914a8ab73ee4))
* find a tileset by the area it covers, not by a rounded key ([7865387](https://github.com/colinmcfadden/avtactools/commit/786538792d242750f84ee44baab88e3e308f451f))
* keep 3D builds working while the API and build service differ ([939b41f](https://github.com/colinmcfadden/avtactools/commit/939b41f9e5a0b23592349ae480f867e98ee4448c))
* lighten the slope heat map ([025bfe8](https://github.com/colinmcfadden/avtactools/commit/025bfe80c3a33791ddbe16baa8afff8a2e5260dd))
* never invent sea level where the DEM has no data ([8f41b72](https://github.com/colinmcfadden/avtactools/commit/8f41b72dc1c7845daf7035f53b2dad87dbde2f75))
* point size and camera placement in the 3D view ([ccf939d](https://github.com/colinmcfadden/avtactools/commit/ccf939d6df43c73b536300dd0c01be50f82dd309))
* put pickup and landing zones on the ground in 3D ([f3ef806](https://github.com/colinmcfadden/avtactools/commit/f3ef806b3f02aea06550fd0a6c218b721a107d9e))
* read only the part of each DEM a terrain tile needs ([3377b54](https://github.com/colinmcfadden/avtactools/commit/3377b54afbcf4cc948f99d32df322628acba2991))
* restore the Google client ID to the frontend env example ([570fb20](https://github.com/colinmcfadden/avtactools/commit/570fb20fd0f44535d3260a812625eca42f74e4b1))
* say why the 3D window cannot build on its own ([60c8303](https://github.com/colinmcfadden/avtactools/commit/60c8303df88e761d2f60f378020786bb18098270))
* serve terrain at every level Cesium asks for ([37950dd](https://github.com/colinmcfadden/avtactools/commit/37950dd637fe2a604ef9e325f1840a5b8664b539))
* soften point rendering, and expose the scene for diagnosis ([dc8d094](https://github.com/colinmcfadden/avtactools/commit/dc8d094d004af370173e29e04686d2a8816fbfce))
* stop building point clouds nobody is waiting for ([020bf25](https://github.com/colinmcfadden/avtactools/commit/020bf25126b2d5ca718bc67a3268c399d7d81134))
* stop thinning large builds to protect memory that was never at risk ([1a54d2f](https://github.com/colinmcfadden/avtactools/commit/1a54d2f3d78174464b352301df1d6b93fe475e2b))
* terrain callback must never return null ([be28fa4](https://github.com/colinmcfadden/avtactools/commit/be28fa4df5b3d9c8988fc2437ef59975e6c7d2be))
* treat a lost terrain cache race as success ([cb68f7f](https://github.com/colinmcfadden/avtactools/commit/cb68f7f9b0cc44d5d4a9adf05da1cfaa03500c9e))


### Features

* add a compass to the 3D view ([97acb89](https://github.com/colinmcfadden/avtactools/commit/97acb8914e4ddb41506decd6f65df226491758a7))
* add authenticated LiDAR tileset API and 3D LZ viewer ([90eeb80](https://github.com/colinmcfadden/avtactools/commit/90eeb80682a51b9bef7a0f5f4ca467f062d1a4d4))
* add LiDAR point cloud pipeline and Cesium 3D viewer ([b22efc4](https://github.com/colinmcfadden/avtactools/commit/b22efc49d1a4f104782f02e6723f1f9a1143a875))
* build a tileset for a target from one command ([15df7da](https://github.com/colinmcfadden/avtactools/commit/15df7da6ba8a61d2203f1062112d74f213667018))
* build an LZ's point cloud when it is saved ([89596d0](https://github.com/colinmcfadden/avtactools/commit/89596d067ff0b5710e8418d1909d157971c6c895))
* build point clouds 500 m around each LZ ([67310ed](https://github.com/colinmcfadden/avtactools/commit/67310edb53fa99f7b96a8ea3b33a95374b5a1a1d))
* build point clouds automatically when the 3D view opens ([205046f](https://github.com/colinmcfadden/avtactools/commit/205046f7d4e2da39dd7f864ed42a0556e89ed2e6))
* build tilesets from a downloaded tile collection ([fc43bee](https://github.com/colinmcfadden/avtactools/commit/fc43beed4ae68f1c06ae16994edfd1035e5e6351))
* context ring for range, and a resizable 3D window ([999d778](https://github.com/colinmcfadden/avtactools/commit/999d77873e44adbdf54c171f88d62e9ce1f2df3c))
* convert lat long to mgrs in search ([674116c](https://github.com/colinmcfadden/avtactools/commit/674116c4a834152bf7895daa1dc8883c67a2ff95))
* draw routes in the 3D view ([b688b85](https://github.com/colinmcfadden/avtactools/commit/b688b850c53324deeb5592d4fdb56397468ea691))
* find the right LiDAR to download for an area ([26f276a](https://github.com/colinmcfadden/avtactools/commit/26f276a9bac62849a8f62fe92149fed6c29eee41))
* full point density, sharper imagery, and a ground that draws ([e5b5e2c](https://github.com/colinmcfadden/avtactools/commit/e5b5e2c746c14b378f5ddcd764cfb8a066211ad2))
* open 3D from each LZ row instead of the bottom bar ([ecdf2cf](https://github.com/colinmcfadden/avtactools/commit/ecdf2cf1743ce939cc6526b0b157bb4b284b9d26))
* pick the survey per target, and light the point cloud ([e82de47](https://github.com/colinmcfadden/avtactools/commit/e82de47215ad989d4cc998ca09fcf9ac3a8a22ff))
* point size control in the 3D window ([c338168](https://github.com/colinmcfadden/avtactools/commit/c3381684dcb3451c451ddee9a065cdbf5581b06b))
* render terrain and imagery, not points in a void ([f132905](https://github.com/colinmcfadden/avtactools/commit/f132905b2541f089e7d7f77e40f1689488f0c1b8))
* show the MGRS grid under the cursor, with a crosshair over the map ([9144aeb](https://github.com/colinmcfadden/avtactools/commit/9144aeb158b1f6b90884c5e2c6987c95eb95ef39))
* warn at startup when the 3D view will be missing something ([3032a9a](https://github.com/colinmcfadden/avtactools/commit/3032a9af1f94c71f8fcce25fe996b73f932c075f))


### Performance Improvements

* colour point clouds from level 18 imagery ([bd24cc5](https://github.com/colinmcfadden/avtactools/commit/bd24cc5573b4eafe267453c59564ac543a2c65ee))
* hold 3D terrain loading while an analysis runs ([1a05b29](https://github.com/colinmcfadden/avtactools/commit/1a05b29ab2f2162ae4f8a01370a6629798826311))
* load 3D terrain from a disk cache, warmed ahead of time ([7394d6c](https://github.com/colinmcfadden/avtactools/commit/7394d6c231d98214a04766a73208613cf9ea53f4))
* work out right-clicked grids in the browser ([59f6c03](https://github.com/colinmcfadden/avtactools/commit/59f6c031741a2d9a3b65030588495ee639c52929))

# [1.6.0](https://github.com/colinmcfadden/avtactools/compare/v1.5.3...v1.6.0) (2026-07-28)


### Features

* aircaft profiles added to app and admin ([2be9c72](https://github.com/colinmcfadden/avtactools/commit/2be9c72c5123eaa3c05dce1cf5748f74e3164fd4))

## [1.5.3](https://github.com/colinmcfadden/avtactools/compare/v1.5.2...v1.5.3) (2026-07-18)


### Bug Fixes

* login duplicate error resolved ([a3b0854](https://github.com/colinmcfadden/avtactools/commit/a3b08549cd6ea555299eef654345473c8d343ddc))
* mobile ui updates ([8101379](https://github.com/colinmcfadden/avtactools/commit/81013798db4228e929501b839a363b1dedd3127d))

## [1.5.2](https://github.com/colinmcfadden/avtactools/compare/v1.5.1...v1.5.2) (2026-07-17)


### Bug Fixes

* hide serpentine routes and threat ui fix ([5f8afb2](https://github.com/colinmcfadden/avtactools/commit/5f8afb2079bf825c9b149630c7e8b3aa95d2eb1c))
* resolve export issues and tailor design ([40eb9ca](https://github.com/colinmcfadden/avtactools/commit/40eb9ca9e7e2016a28ecbf87c3a01c8cbd406382))
* stabilize route errors in console ([06426fd](https://github.com/colinmcfadden/avtactools/commit/06426fdd3135356d4ba2ca3746aa99ed63efd52d))

## [1.5.1](https://github.com/colinmcfadden/avtactools/compare/v1.5.0...v1.5.1) (2026-07-16)


### Bug Fixes

* ensure consistent move/drag controls and correct capacity issues ([37bf10a](https://github.com/colinmcfadden/avtactools/commit/37bf10ac37a4ac62e80f9e2c278c294139835c24))
* fix elevations endpoint ([fc9815d](https://github.com/colinmcfadden/avtactools/commit/fc9815d2526252f235c124f429f6b760a0a8c1c8))
* fix slope analysis tool for accuracy ([c537424](https://github.com/colinmcfadden/avtactools/commit/c5374240147914d21053a93b947842cd450e4eac))
* lzpz sessions update ([3fc5deb](https://github.com/colinmcfadden/avtactools/commit/3fc5debb40ac25ee0a8edf754753c36a9d240767))

# [1.5.0](https://github.com/colinmcfadden/avtactools/compare/v1.4.0...v1.5.0) (2026-07-14)


### Features

* add units generated with symbol.army library ([8180963](https://github.com/colinmcfadden/avtactools/commit/8180963a2e26651d84b64d74b4b06072f3e3ed43))

# [1.4.0](https://github.com/colinmcfadden/avtactools/compare/v1.3.0...v1.4.0) (2026-07-13)


### Bug Fixes

* fix mobile ui issues ([c6eb510](https://github.com/colinmcfadden/avtactools/commit/c6eb510793f9dbb4cce2efe76c13fbdbbde88732))
* more mobile dialogue fixes ([e0aff82](https://github.com/colinmcfadden/avtactools/commit/e0aff8262e5d6eed4b81abe429468f5e5b891f14))


### Features

* add editing of imported msnx routes; save local points ([cc2100c](https://github.com/colinmcfadden/avtactools/commit/cc2100c11a8b08a132b067d42dd0c1794d7ce642))
* add threats to mission planning ([a5b9123](https://github.com/colinmcfadden/avtactools/commit/a5b9123ec0adff5a25e37463b57ea150c4240b5b))
* add ths export and make tooltips permanent on local points ([1b52bce](https://github.com/colinmcfadden/avtactools/commit/1b52bce1148b21fc7243aac6adec28518fe07c2f))
* download threat kmz ([ca00d17](https://github.com/colinmcfadden/avtactools/commit/ca00d1732a6f4ae6fa0ce035c905267020359d0a))
* use local points as route points and correct checkpoint types ([75b842e](https://github.com/colinmcfadden/avtactools/commit/75b842e28be435fbb269c005a5e6808b7df6b25d))

# [1.3.0](https://github.com/colinmcfadden/avtactools/compare/v1.2.0...v1.3.0) (2026-07-07)


### Features

* add vfr sectional to maps ([f146cc8](https://github.com/colinmcfadden/avtactools/commit/f146cc8e6432006705c280443ba6be6e3016c5da))

# [1.2.0](https://github.com/colinmcfadden/avtactools/compare/v1.1.5...v1.2.0) (2026-07-07)


### Features

* combine route and lzpz save menus in to one menu ([6e3be62](https://github.com/colinmcfadden/avtactools/commit/6e3be62d2ca670e9deb73399dabfa6dd961a48bf))
* edit imported routes, export to ff, save routes ([ea420e7](https://github.com/colinmcfadden/avtactools/commit/ea420e7d63ffdaf8918b90ee6ed45b479c125d61))
* move topo map toggle to an actual layer selector ([5127285](https://github.com/colinmcfadden/avtactools/commit/5127285952ae3f80604d60b4f4c5a40ca5daadd8))

## [1.1.5](https://github.com/colinmcfadden/avtactools/compare/v1.1.4...v1.1.5) (2026-07-06)


### Bug Fixes

* login issues ([8fa1c74](https://github.com/colinmcfadden/avtactools/commit/8fa1c74a6ac3528f3c008c4149cf122004736051))

## [1.1.4](https://github.com/colinmcfadden/avtactools/compare/v1.1.3...v1.1.4) (2026-07-06)


### Bug Fixes

* add deleted file ([59b4795](https://github.com/colinmcfadden/avtactools/commit/59b4795758b5baaeb2fa4f9f00468528e958c430))

## [1.1.3](https://github.com/colinmcfadden/avtactools/compare/v1.1.2...v1.1.3) (2026-06-14)


### Bug Fixes

* fix capacity logic for feet ([f526518](https://github.com/colinmcfadden/avtactools/commit/f52651885cd0b5568cac742c0dd6d73dc3b2a4a9))

## [1.1.2](https://github.com/colinmcfadden/avtactools/compare/v1.1.1...v1.1.2) (2026-06-14)


### Bug Fixes

* change meters to feet ([f2f6f50](https://github.com/colinmcfadden/avtactools/commit/f2f6f5082150ec08dfb2e907e3c12248b74eb550))
* potential firefox fix ([53ac55c](https://github.com/colinmcfadden/avtactools/commit/53ac55cfbc9d8af36e15a11ad179b56ab4f39ed0))

## [1.1.1](https://github.com/colinmcfadden/avtactools/compare/v1.1.0...v1.1.1) (2026-06-11)


### Bug Fixes

* refactor code into features instead of large components ([172727f](https://github.com/colinmcfadden/avtactools/commit/172727fb2934f909d7d50718d783e9f3baa06a3f))

# [1.1.0](https://github.com/colinmcfadden/avtactools/compare/v1.0.2...v1.1.0) (2026-06-10)


### Features

* update card values and formatting ([4f336a3](https://github.com/colinmcfadden/avtactools/commit/4f336a39da0cb7b8555e06632d0dd8bab8b996d6))

## [1.0.2](https://github.com/colinmcfadden/avtactools/compare/v1.0.1...v1.0.2) (2026-02-26)


### Bug Fixes

* attempt to fix trim issue on A co computers ([b155620](https://github.com/colinmcfadden/avtactools/commit/b1556209409f21526ea4c8c161415b3d59d34110))

## [1.0.1](https://github.com/colinmcfadden/avtactools/compare/v1.0.0...v1.0.1) (2026-02-25)


### Bug Fixes

* improve capture area bugs on mobile, resolve issues on desktop ([90e9aef](https://github.com/colinmcfadden/avtactools/commit/90e9aefbcd131a45b74841e6957b772cec0d572f))
* wrap versioning import with try/catch to resolve local dev errors ([1a367cc](https://github.com/colinmcfadden/avtactools/commit/1a367cc59fe8a6fbe053b4228d846fd39c85794d))

# 1.0.0 (2026-02-23)


### Bug Fixes

* finalize semantic versioning and readme ([a966bed](https://github.com/colinmcfadden/avtactools/commit/a966bed0ed412df3e3f507bbb99400e996d0c94b))
