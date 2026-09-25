// Shared layout and citation handling for both manuscripts.
//
// Citations are written as {key} or {key1,key2} in the text. Numbers are
// assigned in order of first appearance, as GOST-style reference lists expect,
// and the build fails if the list contains a source the text never cites —
// an uncited entry is the first thing a reviewer checks for.

const fs = require('fs');
const d = require('docx');
const { Paragraph, TextRun, AlignmentType, ImageRun, Table, TableRow, TableCell,
        WidthType, ShadingType, TableLayoutType } = d;

const FONT = 'Times New Roman';
const SZ = 24;
const SZS = 20;
const IND = { firstLine: 709 };
// A4 with 3 cm left and 1.5 cm right margins leaves 16.5 cm of text width.
const TEXT_W = 11906 - 1701 - 850;

const CITE = /\{([a-z_0-9]+(?:\s*,\s*[a-z_0-9]+)*)\}/g;

function numberCitations(blocks, refs) {
  const order = [];
  const scan = (s) => {
    for (const m of s.matchAll(CITE)) {
      for (const k of m[1].split(',').map((x) => x.trim())) {
        if (!refs[k]) throw new Error(`unknown citation key: ${k}`);
        if (!order.includes(k)) order.push(k);
      }
    }
  };
  for (const b of blocks) {
    if (b.t === 'p' || b.t === 'center') scan(b.text);
    if (b.t === 'rich') b.parts.forEach((p) => scan(p[0]));
  }
  const unused = Object.keys(refs).filter((k) => !order.includes(k));
  if (unused.length) throw new Error(`uncited references: ${unused.join(', ')}`);
  const num = Object.fromEntries(order.map((k, i) => [k, i + 1]));
  const resolve = (s) => s.replace(CITE, (_, g) => {
    const ns = g.split(',').map((x) => num[x.trim()]).sort((a, b) => a - b);
    return '[' + compress(ns) + ']';
  });
  return { order, resolve };
}

// [3, 4, 5, 9] -> "3–5, 9"
function compress(ns) {
  const out = [];
  for (let i = 0; i < ns.length; i++) {
    let j = i;
    while (j + 1 < ns.length && ns[j + 1] === ns[j] + 1) j++;
    out.push(j - i >= 2 ? `${ns[i]}–${ns[j]}` : ns.slice(i, j + 1).join(', '));
    i = j;
  }
  return out.join(', ');
}

const run = (text, o = {}) => new TextRun({ text, font: FONT, size: o.size || SZ,
  bold: !!o.b, italics: !!o.i });

function render(blocks, resolve) {
  const out = [];
  for (const b of blocks) {
    const o = b.o || {};
    switch (b.t) {
      case 'p':
        out.push(new Paragraph({
          alignment: o.align || AlignmentType.JUSTIFIED,
          indent: o.noIndent ? undefined : IND,
          spacing: { line: 276, before: o.before || 0, after: o.after === undefined ? 0 : o.after },
          children: [run(resolve(b.text), o)],
        }));
        break;
      case 'rich':
        out.push(new Paragraph({
          alignment: o.align || AlignmentType.JUSTIFIED,
          indent: o.noIndent ? undefined : IND,
          spacing: { line: 276, before: o.before || 0, after: o.after === undefined ? 0 : o.after },
          children: b.parts.map(([t, po]) => run(resolve(t), po || {})),
        }));
        break;
      case 'center':
        out.push(new Paragraph({
          alignment: AlignmentType.CENTER,
          spacing: { line: 276, before: o.before || 0, after: o.after === undefined ? 100 : o.after },
          children: [run(resolve(b.text), o)],
        }));
        break;
      case 'gap':
        out.push(new Paragraph({ spacing: { after: 0 }, children: [run('')] }));
        break;
      case 'img':
        out.push(new Paragraph({
          alignment: AlignmentType.CENTER, keepNext: true,
          spacing: { before: 160, after: 60 },
          children: [new ImageRun({ type: 'png', data: fs.readFileSync(b.file),
            transformation: { width: b.w, height: b.h } })],
        }));
        break;
      case 'cap':
        b.lines.forEach(([text, co], i) => out.push(new Paragraph({
          alignment: AlignmentType.CENTER,
          spacing: { after: i === b.lines.length - 1 ? 200 : 0 },
          children: [run(text, Object.assign({ size: SZS }, co))],
        })));
        break;
      case 'table':
        out.push(table(b.widths, b.rows));
        break;
      default:
        throw new Error('unknown block ' + b.t);
    }
  }
  return out;
}

function table(widths, rows) {
  const scale = TEXT_W / widths.reduce((a, b) => a + b, 0);
  const w = widths.map((x) => Math.round(x * scale));
  w[w.length - 1] += TEXT_W - w.reduce((a, b) => a + b, 0);
  return new Table({
    layout: TableLayoutType.FIXED,
    columnWidths: w,
    width: { size: TEXT_W, type: WidthType.DXA },
    rows: rows.map((cells, ri) => new TableRow({
      tableHeader: ri === 0, cantSplit: true,
      children: cells.map((t, ci) => new TableCell({
        width: { size: w[ci], type: WidthType.DXA },
        shading: ri === 0 ? { type: ShadingType.CLEAR, fill: 'EDEDED' } : undefined,
        margins: { top: 40, bottom: 40, left: 80, right: 80 },
        children: [new Paragraph({
          alignment: ci === 0 ? AlignmentType.LEFT : AlignmentType.CENTER,
          spacing: { after: 0 },
          children: [run(String(t), { b: ri === 0, size: SZS })],
        })],
      })),
    })),
  });
}

// Left-aligned: justified entries with long URLs stretch the word gaps.
const refPara = (text, style) => new Paragraph({
  alignment: AlignmentType.LEFT,
  indent: style === 'vuz' ? IND : { left: 340, hanging: 340 },
  spacing: { after: 40 },
  children: [run(text, { size: SZS })],
});

module.exports = { d, FONT, SZ, SZS, numberCitations, render, refPara, run, AlignmentType };

// ---------------------------------------------------------------- HTML/PDF
// The same blocks rendered as print HTML, so a PDF made from it cannot drift
// from the .docx in content. Page geometry mirrors the Word section: A4,
// margins 2 / 2 / 3 / 1.5 cm, Times New Roman 12 pt, 1.15 line spacing.
const esc = (t) => String(t).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const sty = (o = {}) => [
  o.b ? 'font-weight:bold' : '', o.i ? 'font-style:italic' : '',
  o.size ? `font-size:${o.size / 2}pt` : '',
].filter(Boolean).join(';');
const alignCss = (a) => ({ [AlignmentType.LEFT]: 'left', [AlignmentType.RIGHT]: 'right',
  [AlignmentType.CENTER]: 'center' }[a] || 'justify');

function renderHtml(blocks, resolve) {
  const out = [];
  for (const b of blocks) {
    const o = b.o || {};
    const box = (inner) => `<p style="text-align:${alignCss(o.align)};${o.noIndent ? 'text-indent:0;' : ''}` +
      `${o.before ? `margin-top:${o.before / 20}pt;` : ''}${o.after ? `margin-bottom:${o.after / 20}pt;` : ''}">${inner}</p>`;
    switch (b.t) {
      case 'p': out.push(box(`<span style="${sty(o)}">${esc(resolve(b.text))}</span>`)); break;
      case 'rich': out.push(box(b.parts.map(([t, po]) => `<span style="${sty(po)}">${esc(resolve(t))}</span>`).join(''))); break;
      case 'center': out.push(`<p class="c" style="${sty(o)};${o.before ? `margin-top:${o.before / 20}pt;` : ''}margin-bottom:${(o.after === undefined ? 100 : o.after) / 20}pt">${esc(resolve(b.text))}</p>`); break;
      case 'gap': out.push('<p class="gap">&nbsp;</p>'); break;
      case 'img': out.push(`<figure><img src="${b.file}" style="width:${b.w}px;height:${b.h}px">`); break;
      case 'cap': out.push(b.lines.map(([t, co]) => `<figcaption style="${sty(Object.assign({ size: SZS }, co))}">${esc(t)}</figcaption>`).join('') + '</figure>'); break;
      case 'table': {
        const tot = b.widths.reduce((a, c) => a + c, 0);
        const cols = b.widths.map((w) => `<col style="width:${(100 * w / tot).toFixed(2)}%">`).join('');
        const rows = b.rows.map((r, ri) => '<tr>' + r.map((c, ci) =>
          `<${ri ? 'td' : 'th'} style="text-align:${ci ? 'center' : 'left'}">${esc(c)}</${ri ? 'td' : 'th'}>`).join('') + '</tr>').join('');
        out.push(`<table><colgroup>${cols}</colgroup>${rows}</table>`);
        break;
      }
      default: throw new Error('unknown block ' + b.t);
    }
  }
  return out.join('\n');
}

const HTML_HEAD = `<!doctype html><html lang="ru"><head><meta charset="utf-8"><style>
@page { size: A4; margin: 2cm 1.5cm 2cm 3cm; }
html, body { margin: 0; padding: 0; background: #fff; }
body { font-family: "Times New Roman", Times, serif; font-size: 12pt; line-height: 1.15; color: #000;
       hyphens: auto; -webkit-hyphens: auto; }
p { margin: 0; text-indent: 1.25cm; text-align: justify; orphans: 2; widows: 2; }
p.c { text-align: center; text-indent: 0; }
p.gap { text-indent: 0; }
figure { margin: 8pt 0 10pt; text-align: center; break-inside: avoid; }
figure img { display: block; margin: 0 auto 3pt; }
figcaption { text-align: center; }
table { width: 100%; border-collapse: collapse; table-layout: fixed; font-size: 10pt; break-inside: avoid; }
th, td { border: 0.6pt solid #000; padding: 2pt 4pt; text-indent: 0; }
th { background: #ededed; font-weight: bold; }
.ref { text-indent: 1.25cm; text-align: left; font-size: 10pt; margin-bottom: 2pt; hyphens: manual; }
</style></head><body>`;

// 'vak' mirrors the .docx hanging indent (number outdented); default mirrors
// the university sample's first-line indent.
const refHtml = (text, style) => style === 'vak'
  ? `<p class="ref" style="text-indent:-0.6cm;padding-left:0.6cm">${esc(text)}</p>`
  : `<p class="ref">${esc(text)}</p>`;

module.exports.renderHtml = renderHtml;
module.exports.HTML_HEAD = HTML_HEAD;
module.exports.refHtml = refHtml;
