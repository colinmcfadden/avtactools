// Loads a golden fixture from the repository's contracts/ folder. The same
// files are read by the backend's pytest and by the Android and iOS tests, so a
// planning formula that changes shows up in every client at once.
//
// Used only by the *.test.js files beside it; nothing in the app imports this.
const fs = require("fs");
const path = require("path");

const FIXTURES = path.resolve(__dirname, "../../../contracts/fixtures");

const readFixture = (name) =>
  JSON.parse(fs.readFileSync(path.join(FIXTURES, name), "utf8"));

module.exports = { readFixture, FIXTURES };
