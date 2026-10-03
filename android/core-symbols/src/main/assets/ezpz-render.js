// Evaluated in the JavaScript sandbox after milsymbol.js, which defines the global `ms`. The sandbox has no DOM, and none is needed:
// milsymbol builds its SVG as a string. The app calls `ezpzRenderSymbol(sidc, optionsJson)` and reads back one JSON string.
//
// It does what the web's `symbolParts` does (frontend/src/feature/symbols/milsym.js), and the web's contract test runs this very file against
// milsymbol in a context with no DOM and holds the answer to the web's own (frontend/src/contracts/symbolFixtures.test.js).
(function (global) {
  global.ezpzRenderSymbol = function (sidc, optionsJson) {
    try {
      var options = optionsJson ? JSON.parse(optionsJson) : {};
      var symbol = new global.ms.Symbol(sidc, Object.assign({ size: 30 }, options));
      if (!symbol.isValid()) return JSON.stringify({ valid: false });
      var size = symbol.getSize();
      var anchor = symbol.getAnchor();
      return JSON.stringify({ valid: true, svg: symbol.asSVG(), width: size.width, height: size.height, anchorX: anchor.x, anchorY: anchor.y });
    } catch (e) {
      return JSON.stringify({ valid: false });
    }
  };
})(this);
