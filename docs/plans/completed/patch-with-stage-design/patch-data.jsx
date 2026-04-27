// Patch view shared data: fixtures, gel libraries, beam presets

window.PATCH_DATA = {
  // Fixtures already patched (mirrors the screenshots)
  fixtures: [
    { id: "beam-bar-1", addr: "0-001", uni: 0, ch: 1, type: "Equinox Slender Beam Bar Quad", mode: "1-Channel (Show Presets)", chCount: 1, name: "Beam Bar 1", key: "beam-bar-1", groups: ["bars","lasers"], pos: "LX1", x: 50, y: 18, fxKind: "bar" },
    { id: "hex2",  addr: "0-017", uni: 0, ch: 17, type: "Chauvet Freedom Par Hex", chCount: 12, name: "Hex 2", key: "hex2", groups: ["hexes","lasers","bars"], pos: "ADV 1", x: 24, y: 48, fxKind: "par" },
    { id: "hex3",  addr: "0-065", uni: 0, ch: 65, type: "Chauvet Freedom Par Hex", chCount: 12, name: "Hex 3", key: "hex3", groups: ["hexes"], pos: "ADV 1", x: 50, y: 44, fxKind: "par" },
    { id: "hex4",  addr: "0-081", uni: 0, ch: 81, type: "Chauvet Freedom Par Hex", chCount: 12, name: "Hex 4", key: "hex4", groups: ["hexes"], pos: "ADV 1", x: 76, y: 48, fxKind: "par" },
    { id: "scan",  addr: "0-193", uni: 0, ch: 193, type: "Equinox Scantastic 4", mode: "17-Channel (Full Control)", chCount: 17, name: "Scantastic", key: "scantastic", groups: [], pos: "FOH", x: 60, y: 90, fxKind: "laser" },
    { id: "laser1",addr: "0-257", uni: 0, ch: 257, type: "Laserworld CS-1000RGB MK3", chCount: 13, name: "Laser 1", key: "laser1", groups: ["lasers"], pos: "FOH", x: 16, y: 84, fxKind: "laser" },
    { id: "spot-r",addr: "0-273", uni: 0, ch: 273, type: "Equinox Fusion 100 Spot MKII", mode: "15-Channel (Full Control)", chCount: 15, name: "Moving Right", key: "spot-right", groups: ["spots"], pos: "LX2", x: 68, y: 70, fxKind: "moving" },
    { id: "spot-l",addr: "0-289", uni: 0, ch: 289, type: "Equinox Fusion 100 Spot MKII", mode: "15-Channel (Full Control)", chCount: 15, name: "Moving Left",  key: "spot-left",  groups: ["spots"], pos: "LX2", x: 32, y: 70, fxKind: "moving" },
    { id: "haze",  addr: "0-305", uni: 0, ch: 305, type: "Hazer", chCount: 2, name: "Hazer", key: "hazer", groups: [], pos: "USR", x: 90, y: 80, fxKind: "haze" },
    { id: "laser2",addr: "0-321", uni: 0, ch: 321, type: "Laserworld CS-1000RGB MK3", chCount: 13, name: "Laser 2", key: "laser2", groups: ["lasers"], pos: "FOH", x: 84, y: 84, fxKind: "laser" },
    { id: "bar1",  addr: "0-400", uni: 0, ch: 400, type: "Showtec LED Lightbar 12 Pixel", mode: "48-Channel (Pixel Control)", chCount: 48, name: "Bar 1", key: "bar1", groups: ["bars"], pos: "LX1", x: 32, y: 18, fxKind: "bar" },
    { id: "bar2",  addr: "0-450", uni: 0, ch: 450, type: "Showtec LED Lightbar 12 Pixel", mode: "48-Channel (Pixel Control)", chCount: 48, name: "Bar 2", key: "bar2", groups: ["bars"], pos: "LX1", x: 68, y: 18, fxKind: "bar" },
    { id: "sdff",  addr: "1-001", uni: 1, ch: 1, type: "Generic Dimmer", chCount: 2, name: "Side Front Fill", key: "sdff", groups: [], pos: "ADV 2", x: 12, y: 38, fxKind: "dimmer", beam: 26, gel: "L201", isGeneric: true },
    { id: "fph1",  addr: "1-003", uni: 1, ch: 3, type: "Chauvet Freedom Par Hex", chCount: 12, name: "Freedom Par Hex 1", key: "freedom-par-hex-1", groups: ["bars"], pos: "ADV 2" },
    { id: "fph21", addr: "1-015", uni: 1, ch: 15, type: "Chauvet Freedom Par Hex", chCount: 12, name: "Freedom Par Hex 1", key: "freedom-par-hex-2-1", groups: [], pos: "" },
    { id: "fph2",  addr: "1-027", uni: 1, ch: 27, type: "Chauvet Freedom Par Hex", chCount: 12, name: "Freedom Par Hex 2", key: "freedom-par-hex-2", groups: [], pos: "" },
    { id: "fsm",   addr: "1-039", uni: 1, ch: 39, type: "Equinox Fusion 100 Spot MKII", mode: "15-Channel (Full Control)", chCount: 15, name: "Fusion 100 Spot MKII", key: "fusion-100-spot-mkii", groups: [], pos: "" },
    { id: "fsm2",  addr: "1-054", uni: 1, ch: 54, type: "Equinox Fusion 100 Spot MKII", mode: "15-Channel (Full Control)", chCount: 15, name: "Fusion 100 Spot MKII 2", key: "fusion-100-spot-mkii-2", groups: [], pos: "" },
  ],

  groups: [
    { id: "bars", name: "bars", count: 5 },
    { id: "hexes", name: "hexes", count: 3 },
    { id: "l", name: "l", count: 0 },
    { id: "la", name: "la", count: 0 },
    { id: "las", name: "las", count: 0 },
    { id: "lasers", name: "lasers", count: 4 },
    { id: "spots", name: "spots", count: 2 },
  ],

  // Recently-used / common rigging positions
  positionPresets: ["FOH", "LX1", "LX2", "LX3", "ADV 1", "ADV 2", "USR", "DSL", "DSR", "USL", "MID", "BOOM L", "BOOM R"],

  beamPresets: [
    { name: "Spot",   deg: 14 },
    { name: "Narrow", deg: 26 },
    { name: "Medium", deg: 36 },
    { name: "Wide",   deg: 50 },
    { name: "Flood",  deg: 70 },
  ],

  // Common Lee + Rosco gels with hex previews
  gels: {
    Lee: [
      { code: "L004", name: "Medium Bastard Amber", color: "#fbb27a" },
      { code: "L007", name: "Pale Yellow", color: "#fde88a" },
      { code: "L022", name: "Dark Amber", color: "#e88830" },
      { code: "L079", name: "Just Blue", color: "#1d3a8a" },
      { code: "L106", name: "Primary Red", color: "#d12028" },
      { code: "L120", name: "Deep Blue", color: "#1c2c8e" },
      { code: "L132", name: "Medium Blue", color: "#3060c8" },
      { code: "L139", name: "Primary Green", color: "#1b8c3a" },
      { code: "L147", name: "Apricot", color: "#f5b07b" },
      { code: "L152", name: "Pale Gold", color: "#f3d27a" },
      { code: "L154", name: "Pale Rose", color: "#f3b9c2" },
      { code: "L162", name: "Bastard Amber", color: "#fbcfa0" },
      { code: "L181", name: "Congo Blue", color: "#161a8e" },
      { code: "L195", name: "Zenith Blue", color: "#1f3aa0" },
      { code: "L201", name: "Full CT Blue", color: "#9bbede" },
      { code: "L202", name: "Half CT Blue", color: "#bfd7ec" },
      { code: "L203", name: "Quarter CT Blue", color: "#dbe7f2" },
      { code: "L204", name: "Full CT Orange", color: "#f9c089" },
      { code: "L205", name: "Half CT Orange", color: "#fbd7ad" },
      { code: "L237", name: "CID 1/2", color: "#e2eef9" },
    ],
    Rosco: [
      { code: "R02",  name: "Bastard Amber", color: "#fbcc9a" },
      { code: "R08",  name: "Pale Gold", color: "#f0c970" },
      { code: "R26",  name: "Light Red", color: "#ee5b5e" },
      { code: "R33",  name: "No Color Pink", color: "#fde0e6" },
      { code: "R51",  name: "Surprise Pink", color: "#f5a8c2" },
      { code: "R59",  name: "Indigo", color: "#3a2778" },
      { code: "R64",  name: "Light Steel Blue", color: "#b0d2ec" },
      { code: "R68",  name: "Sky Blue", color: "#65a8db" },
      { code: "R80",  name: "Primary Blue", color: "#1c3aa0" },
      { code: "R83",  name: "Medium Blue", color: "#2058b8" },
      { code: "R90",  name: "Dark Yellow Green", color: "#5b8a2a" },
      { code: "R91",  name: "Primary Green", color: "#1f8a3c" },
    ],
  },
};

// Helpers
window.PATCH_HELP = {
  findGel(code) {
    if (!code) return null;
    for (const brand of Object.keys(window.PATCH_DATA.gels)) {
      const g = window.PATCH_DATA.gels[brand].find(x => x.code === code);
      if (g) return { ...g, brand };
    }
    return null;
  },
  searchGels(q, brand) {
    const norm = (s) => s.toLowerCase();
    const Q = norm(q || "");
    const set = brand && brand !== "All" ? [brand] : Object.keys(window.PATCH_DATA.gels);
    const out = [];
    for (const b of set) {
      for (const g of window.PATCH_DATA.gels[b]) {
        if (!Q || norm(g.code).includes(Q) || norm(g.name).includes(Q)) {
          out.push({ ...g, brand: b });
        }
      }
    }
    return out;
  },
  // Other fixture positions to draw on the mini-stage as context
  contextFixtures() {
    return window.PATCH_DATA.fixtures.filter(f => typeof f.x === "number" && typeof f.y === "number");
  },
};
