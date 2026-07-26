// Chord-chart core: parsing Ultimate Guitar's markup, transposing, and working
// out where each chord sits above the lyric. Pure functions, no DOM — so it can
// be tested outside a browser (see tools/test.mjs).

(function (root) {
  "use strict";

  var SHARP = ["C","C#","D","D#","E","F","F#","G","G#","A","A#","B"];
  var FLAT  = ["C","Db","D","Eb","E","F","Gb","G","Ab","A","Bb","B"];

  var IDX = { "C":0,"B#":0,"C#":1,"Db":1,"D":2,"D#":3,"Eb":3,"E":4,"Fb":4,
              "E#":5,"F":5,"F#":6,"Gb":6,"G":7,"G#":8,"Ab":8,"A":9,"A#":10,
              "Bb":10,"B":11,"Cb":11 };

  // Keys conventionally written with flats.
  var FLATKEY = { 1:1, 3:1, 5:1, 8:1, 10:1 };

  function shiftNote(n, semis, flat) {
    var i = IDX[n];
    if (i === undefined) return n;
    var t = ((i + semis) % 12 + 12) % 12;
    return flat ? FLAT[t] : SHARP[t];
  }

  // Handles quality suffixes and slash bass: Cadd9, F#m7b5, D/F#.
  function shiftChord(c, semis, flat) {
    if (!semis) return c;
    return String(c).split("/").map(function (p) {
      var m = /^([A-G][#b]?)(.*)$/.exec(p);
      return m ? shiftNote(m[1], semis, flat) + m[2] : p;
    }).join("/");
  }

  // Should this key be spelled with flats once shifted?
  function useFlat(rootNote, semis) {
    var i = IDX[rootNote];
    if (i === undefined) return false;
    return !!FLATKEY[((i + semis) % 12 + 12) % 12];
  }

  // Just the root of a UG tonality_name ("Am" -> "A", "Bb" -> "Bb").
  function keyRoot(tonality) {
    var m = /^([A-G][#b]?)/.exec(tonality || "");
    return m ? m[1] : "";
  }

  function parse(raw) {
    // [tab]...[/tab] are alignment wrappers in chord sheets, not tablature.
    // Drop the markers, keep the text inside.
    var text = String(raw == null ? "" : raw)
      .replace(/\[\/?tab\]/g, "")
      .replace(/\r/g, "");

    return text.split("\n").map(function (line) {
      if (line.indexOf("[ch]") === -1) {
        var s = /^\s*\[([^\]]+)\]\s*$/.exec(line);
        if (s) return { t: "sect", text: s[1] };
        if (!line.trim()) return { t: "gap" };
        return { t: "lyric", text: line };
      }

      var items = [], col = 0, i = 0;
      while (i < line.length) {
        if (line.substr(i, 4) === "[ch]") {
          var end = line.indexOf("[/ch]", i);
          if (end === -1) { col++; i++; continue; }
          var c = line.slice(i + 4, end);
          items.push({ col: col, chord: c });
          col += c.length;
          i = end + 5;
        } else {
          col++; i++;
        }
      }
      return items.length ? { t: "chords", items: items } : { t: "gap" };
    });
  }

  // Place chords for one line at a given transposition.
  // Transposing can widen a name (G -> G#), which would otherwise let two
  // chords collide and shear the alignment. Walk left to right and push any
  // chord that would overlap its neighbour.
  function layout(items, semis, flat) {
    var out = [], cursor = -1;
    for (var i = 0; i < items.length; i++) {
      var name = shiftChord(items[i].chord, semis, flat);
      var col = items[i].col;
      if (col <= cursor) col = cursor + 1;
      cursor = col + name.length;
      out.push({ col: col, name: name });
    }
    return out;
  }

  root.CH = {
    SHARP: SHARP, FLAT: FLAT, IDX: IDX, FLATKEY: FLATKEY,
    shiftNote: shiftNote, shiftChord: shiftChord, useFlat: useFlat,
    keyRoot: keyRoot, parse: parse, layout: layout
  };
})(typeof globalThis !== "undefined" ? globalThis : this);
