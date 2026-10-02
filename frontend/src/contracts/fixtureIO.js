// Reading and writing contracts/fixtures from the web-reference generators.
//
// Normally a generator *verifies* that the committed fixtures still equal what the
// web code produces. After an intended change to a formula, regenerate and review
// the diff (the native test suites will then fail until they follow):
//
//   cd frontend && UPDATE_CONTRACTS=1 CI=true npx react-scripts test --watchAll=false src/contracts
const fs = require("fs");
const path = require("path");

const { FIXTURES } = require("./readFixture");

const UPDATE = process.env.UPDATE_CONTRACTS === "1";

/** One entry per line, so a regenerated fixture diffs case by case. */
const dumps = (document) => {
  const lines = ["{"];
  const keys = Object.keys(document);
  keys.forEach((key, i) => {
    const value = document[key];
    const comma = i < keys.length - 1 ? "," : "";
    if (Array.isArray(value) && value.length > 0 && typeof value[0] === "object") {
      lines.push(`  ${JSON.stringify(key)}: [`);
      lines.push(value.map((v) => `    ${JSON.stringify(v)}`).join(",\n"));
      lines.push(`  ]${comma}`);
    } else {
      lines.push(`  ${JSON.stringify(key)}: ${JSON.stringify(value)}${comma}`);
    }
  });
  lines.push("}");
  return `${lines.join("\n")}\n`;
};

/**
 * Pretty JSON that keeps anything short on one line (a point, a band), so a regenerated
 * fixture diffs record by record and a 400-point file is not 4,000 lines.
 */
const compact = (value, indent = "", width = 200) => {
  const flat = JSON.stringify(value);
  if (flat === undefined || flat.length <= width || value === null || typeof value !== "object") return flat;
  const inner = `${indent}  `;
  if (Array.isArray(value)) {
    return `[\n${value.map((v) => inner + compact(v, inner, width)).join(",\n")}\n${indent}]`;
  }
  return `{\n${Object.entries(value).map(([k, v]) => `${inner}${JSON.stringify(k)}: ${compact(v, inner, width)}`).join(",\n")}\n${indent}}`;
};

const fixturePath = (name) => path.join(FIXTURES, name);

const writeFixture = (name, content) => {
  const file = fixturePath(name);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, content);
};

module.exports = { UPDATE, dumps, compact, fixturePath, writeFixture, FIXTURES };
