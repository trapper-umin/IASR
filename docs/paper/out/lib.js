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
