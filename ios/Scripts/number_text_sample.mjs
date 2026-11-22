// What Node writes for a few hundred thousand doubles (String(n)), for CoreJS's everyNumberNodeWroteIsWrittenTheSame test, which
// holds JsNumber.toText to it when EZPZ_NUMBER_TEXT_SAMPLE names the file. Random bit patterns, then the decimals, thirds, large
// and small numbers and powers of ten where a writer's choices show. Not a fixture: run it when the number writer changes.
//
//   node ios/Scripts/number_text_sample.mjs /tmp/number_sample.json
//   EZPZ_NUMBER_TEXT_SAMPLE=/tmp/number_sample.json swift test --package-path ios/Packages/EZPZCore --filter everyNumberNodeWrote
import { randomFillSync } from "node:crypto";
import { writeFileSync } from "node:fs";
const out = [];
const buf = new BigUint64Array(1);
const f = new Float64Array(buf.buffer);
const hex = (x) => { f[0] = x; return buf[0].toString(16); };
const add = (x) => { if (Number.isFinite(x)) out.push([hex(x), String(x)]); };
const bits = new BigUint64Array(300000);
randomFillSync(bits);
for (const b of bits) { buf[0] = b; add(f[0]); }
for (let i = -20000; i <= 20000; i++) { add(i / 1000); add(i / 3); add(i * 1e15); add(i * 1e-7); }
for (let e = -324; e <= 308; e++) { add(Number(`1e${e}`)); add(Number(`9.999999999999999e${e}`)); add(Number(`5e${e}`)); }
writeFileSync(process.argv[2], JSON.stringify(out));
console.log(out.length);
