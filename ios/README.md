# EZ/PZ for iPhone and iPad

The Swift app. Its plan, the steps and the owner's decisions are in [`docs/IOS_PLAN.md`](../docs/IOS_PLAN.md); the rules every
native client follows (the web is the reference, the fixtures are the contract) are in [`AGENTS.md`](../AGENTS.md) §17.

**Where it stands:** I0, the foundations. The app builds and shows its version; it plans nothing yet.

```
ios/
├─ EZPZ.xcodeproj         the app target (its sources are the App folder, kept in step by Xcode itself)
├─ App/                   the composition root
├─ Config/Version.xcconfig  the app's own version, its API majors, the placeholder bundle id
├─ Packages/EZPZCore/     the pure modules: Foundation only, tested with `swift test` on the Mac
│   CoreJS                what JavaScript does that every port must do the same way: JSONTree, Js, JsNumber
│   CoreTesting           reads contracts/fixtures, compares JSON with a tolerance
└─ Scripts/               number_text_sample.mjs, the Node check of JsNumber.toText
```

## Build and test

Xcode 26 or later (the owner's Mac has 27.2).

```bash
swift test --package-path ios/Packages/EZPZCore
```

```bash
swift format lint --strict --recursive ios/App ios/Packages/EZPZCore/Sources ios/Packages/EZPZCore/Tests ios/Packages/EZPZCore/Package.swift
```

```bash
xcodebuild -project ios/EZPZ.xcodeproj -scheme EZPZ -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

Or open `ios/EZPZ.xcodeproj` in Xcode and run the EZPZ scheme on a simulator. Signing waits on the owner's Apple team and bundle id
(docs/IOS_PLAN.md §15).

**`JsNumber.toText` against Node**, when the number writer changes (Node 22):

```bash
node ios/Scripts/number_text_sample.mjs /tmp/number_sample.json
```

```bash
EZPZ_NUMBER_TEXT_SAMPLE=/tmp/number_sample.json swift test --package-path ios/Packages/EZPZCore --filter everyNumberNodeWrote
```

On 2026-11-21, 461,755 doubles were written exactly as Node writes them.
