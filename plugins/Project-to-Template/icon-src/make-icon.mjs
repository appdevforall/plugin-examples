import { Resvg } from '@resvg/resvg-js';
import { writeFileSync } from 'node:fs';

// "Project to Template": a solid source (project) card, an arrow, and a dashed
// template card. Two separate cards, left-to-right transformation.
function svg({ tile, card, line, outline, arrow }) {
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 192 192" width="192" height="192">
  <rect x="8" y="8" width="176" height="176" rx="42" fill="${tile}"/>
  <!-- project (source) card: solid, left -->
  <rect x="22" y="56" width="52" height="80" rx="12" fill="${card}"/>
  <rect x="34" y="80"  width="28" height="8" rx="4" fill="${line}"/>
  <rect x="34" y="98"  width="28" height="8" rx="4" fill="${line}"/>
  <rect x="34" y="116" width="18" height="8" rx="4" fill="${line}"/>
  <!-- arrow: project -> template -->
  <path d="M78,89 L98,89 L98,79 L114,96 L98,113 L98,103 L78,103 Z" fill="${arrow}"/>
  <!-- template (generated) card: dashed outline, right -->
  <rect x="118" y="56" width="52" height="80" rx="12" fill="none" stroke="${outline}"
        stroke-width="6" stroke-linecap="round" stroke-dasharray="15 12"/>
</svg>`;
}

const day = svg({ tile: '#E7ECFB', card: '#3E5488', line: '#E7ECFB', outline: '#7A8EC4', arrow: '#3E5488' });
const night = svg({ tile: '#1E2740', card: '#B1C5FF', line: '#1E2740', outline: '#6E82BC', arrow: '#B1C5FF' });

for (const [name, s] of [['icon_day', day], ['icon_night', night]]) {
  const png = new Resvg(s, { fitTo: { mode: 'width', value: 192 } }).render().asPng();
  writeFileSync(new URL(`./${name}.png`, import.meta.url), png);
  writeFileSync(new URL(`./${name}.svg`, import.meta.url), s);
  console.log('wrote', name);
}
