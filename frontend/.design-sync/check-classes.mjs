// Usage: node check-classes.mjs <css> <class...>  — prints ok/NO per class
import { readFileSync } from 'node:fs';
const css = readFileSync(process.argv[2], 'utf8');
const cssEscape = (c) => c.replace(/[:\/.\[\]#%]/g, (m) => '\\' + m);
const reEscape = (s) => s.replace(/[.*+?^${}()|[\]\\\/]/g, '\\$&');
for (const c of process.argv.slice(3)) {
  const re = new RegExp('\\.' + reEscape(cssEscape(c)) + '(?=[,{\\s:>)\\[])');
  console.log((re.test(css) ? 'ok  ' : 'NO  ') + c);
}
