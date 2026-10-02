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
