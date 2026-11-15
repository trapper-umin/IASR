"""Build the thesis .docx from the Markdown chapters in this directory.

    python3 docs/dissertation/thesis/build.py

Layout follows the usual Russian university rules (ГОСТ 7.32 style): A4,
margins 30/15/20/20 mm, Times New Roman 14 pt, 1.5 line spacing, 1.25 cm
first-line indent, page numbers centred at the bottom, no number on the title
page. Tables, figures and formulas are numbered within a chapter.

Markdown subset understood here:

    # ГЛАВА 1. TITLE            chapter (new page); # ВВЕДЕНИЕ etc. unnumbered;
                                # ПРИЛОЖЕНИЕ А. TITLE  appendix (numbers А.1)
    ## 1.1. Title               section,  ### 1.1.1. Title  subsection
    Таблица: Caption {#tbl:id}  caption line followed by a pipe table
    ![Caption](figures/x.png){#fig:id width=16}
    $$ formula $$ {#eq:id}      display formula, numbered at the right
    [@tbl:id] [@fig:id] [@eq:id] cross references (number only)
    {key} {key1, key2}          citations, numbered by first appearance
    - item / 1) item            list items
    ```                         listing (monospace)
    **bold**, *italic*, `code`  inline markup
    <<<pagebreak>>>             explicit page break

Citations of a key missing from references.json and entries never cited both
stop the build: an unused source is the first thing a reviewer looks for.
"""
import json
import re
import sys
from pathlib import Path

from docx import Document
from docx.enum.section import WD_SECTION
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_BREAK, WD_LINE_SPACING, WD_TAB_ALIGNMENT
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor

HERE = Path(__file__).resolve().parent
CHAPTERS = ["00-abstract.md", "01-introduction.md", "10-chapter1.md", "20-chapter2.md",
            "30-chapter3.md", "40-chapter4.md", "50-conclusion.md", "90-appendices.md"]
OUT = HERE / "Диссертация_IASR.docx"
FONT = "Times New Roman"
MONO = "Courier New"

CITE = re.compile(r"\{([a-z_0-9]+(?:\s*,\s*[a-z_0-9]+)*)\}")
XREF = re.compile(r"\[@((?:tbl|fig|eq):[A-Za-z0-9_-]+)\]")
INLINE = re.compile(r"(\*\*.+?\*\*|\*[^*\s][^*]*?\*|`[^`]+`)")
LABEL = re.compile(r"\{#((?:tbl|fig|eq):[A-Za-z0-9_-]+)([^}]*)\}\s*$")


# ----------------------------------------------------------------- parsing
def read_blocks():
    """Split all chapters into blocks: (kind, payload)."""
    blocks = []
    for name in CHAPTERS:
        lines = (HERE / name).read_text(encoding="utf-8").splitlines()
        i = 0
        para = []

        def flush():
            if para:
                blocks.append(("p", " ".join(s.strip() for s in para)))
                para.clear()

        while i < len(lines):
            line = lines[i]
            s = line.strip()
            if not s:
                flush(); i += 1; continue
            if s.startswith("<!--"):
                flush()
                while "-->" not in lines[i]:
                    i += 1
                i += 1; continue
            if s == "<<<pagebreak>>>":
                flush(); blocks.append(("break", None)); i += 1; continue
            m = re.match(r"^(#{1,3})\s+(.*)$", s)
            if m:
                flush(); blocks.append(("h%d" % len(m.group(1)), m.group(2).strip())); i += 1; continue
            if s.startswith("```"):
                flush(); i += 1; code = []
                while not lines[i].strip().startswith("```"):
                    code.append(lines[i]); i += 1
                blocks.append(("code", code)); i += 1; continue
            if s.startswith("$$"):
                flush()
                body = s[2:]
                label = None
                lm = LABEL.search(body)
                if lm:
                    label = lm.group(1); body = body[:lm.start()].rstrip()
                body = body.rstrip()
                assert body.endswith("$$"), line
                blocks.append(("eq", (body[:-2].strip(), label))); i += 1; continue
            if s.startswith("Таблица:"):
                flush()
                cap = s[len("Таблица:"):].strip()
                label = None
                lm = LABEL.search(cap)
                if lm:
                    label = lm.group(1); cap = cap[:lm.start()].rstrip()
                i += 1
                rows = []
                while i < len(lines) and lines[i].strip().startswith("|"):
                    cells = [c.strip() for c in lines[i].strip().strip("|").split("|")]
                    if not all(re.fullmatch(r":?-{3,}:?", c) for c in cells):
                        rows.append(cells)
                    i += 1
                blocks.append(("table", (cap, label, rows))); continue
            m = re.match(r"^!\[(.*)\]\((.*?)\)(\{.*\})?\s*$", s)
            if m:
                flush()
                attrs = m.group(3) or ""
                label = re.search(r"#(fig:[A-Za-z0-9_-]+)", attrs)
                width = re.search(r"width=([0-9.]+)", attrs)
                blocks.append(("fig", (m.group(1), m.group(2), label.group(1) if label else None,
                                       float(width.group(1)) if width else 16.0)))
                i += 1; continue
            if re.match(r"^(- |\d+\) )", s):
                flush()
                item = [s]
                i += 1
                while i < len(lines) and lines[i].startswith("  ") and lines[i].strip():
                    item.append(lines[i].strip()); i += 1
                text = " ".join(item)
                blocks.append(("li", "– " + text[2:] if text.startswith("- ") else text))
                continue
            para.append(line); i += 1
        flush()
    return blocks


def number_objects(blocks):
    """Assign chapter-relative numbers to tables, figures and formulas."""
    labels = {}
    chapter = None
    counts = {"tbl": 0, "fig": 0, "eq": 0}
    numbered = []
    for kind, data in blocks:
        if kind == "h1":
            m = re.match(r"^ГЛАВА\s+(\d+)\.", data, re.I) or re.match(r"^ПРИЛОЖЕНИЕ\s+([А-Я])", data, re.I)
            chapter = m.group(1) if m else None
            counts = {"tbl": 0, "fig": 0, "eq": 0}
        if kind in ("table", "fig", "eq"):
            key = {"table": "tbl", "fig": "fig", "eq": "eq"}[kind]
            counts[key] += 1
            num = f"{chapter}.{counts[key]}" if chapter else str(counts[key])
            label = data[1] if kind != "fig" else data[2]
            if label:
                assert label not in labels, label
                labels[label] = num
            numbered.append((kind, data, num))
        else:
            numbered.append((kind, data, None))
    return numbered, labels


def citation_order(blocks, refs):
    order = []

    def scan(text):
        for m in CITE.finditer(text):
            for k in (x.strip() for x in m.group(1).split(",")):
                if k not in refs:
                    raise SystemExit(f"unknown citation key: {k}")
                if k not in order:
                    order.append(k)

    for kind, data, _ in blocks:
        if kind in ("p", "li"):
            scan(data)
        elif kind == "table":
            scan(data[0]); [scan(c) for row in data[2] for c in row]
        elif kind == "fig":
            scan(data[0])
    unused = [k for k in refs if k not in order]
    if unused:
        raise SystemExit("uncited references: " + ", ".join(unused))
    return {k: i + 1 for i, k in enumerate(order)}, order


def compress(ns):
    out, i = [], 0
    while i < len(ns):
        j = i
        while j + 1 < len(ns) and ns[j + 1] == ns[j] + 1:
            j += 1
        out.append(f"{ns[i]}–{ns[j]}" if j - i >= 2 else ", ".join(map(str, ns[i:j + 1])))
        i = j + 1
    return ", ".join(out)


# ----------------------------------------------------------------- rendering
class Writer:
    def __init__(self, numbers, labels):
        self.doc = Document()
        self.numbers = numbers
        self.labels = labels
        self.setup()

    def resolve(self, text):
        def cite(m):
            ns = sorted({self.numbers[k.strip()] for k in m.group(1).split(",")})
            return "[" + compress(ns) + "]"

        def xref(m):
            if m.group(1) not in self.labels:
                raise SystemExit("unknown cross reference " + m.group(1))
            return self.labels[m.group(1)]
        return XREF.sub(xref, CITE.sub(cite, text))

    def setup(self):
        doc = self.doc
        sec = doc.sections[0]
        sec.page_height, sec.page_width = Cm(29.7), Cm(21.0)
        sec.left_margin, sec.right_margin = Cm(3.0), Cm(1.5)
        sec.top_margin, sec.bottom_margin = Cm(2.0), Cm(2.0)
        sec.different_first_page_header_footer = True
        st = doc.styles["Normal"]
        st.font.name = FONT
        st.font.size = Pt(14)
        st.element.rPr.rFonts.set(qn("w:eastAsia"), FONT)
        pf = st.paragraph_format
        pf.line_spacing_rule = WD_LINE_SPACING.ONE_POINT_FIVE
        pf.space_before = Pt(0)
        pf.space_after = Pt(0)
        pf.first_line_indent = Cm(1.25)
        pf.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY
        pf.widow_control = True
        for level, size in ((1, 14), (2, 14), (3, 14)):
            h = doc.styles[f"Heading {level}"]
            h.font.name = FONT
            h.font.size = Pt(size)
            h.font.bold = True
            h.font.italic = False
            h.font.color.rgb = RGBColor(0, 0, 0)
            rpr = h.element.get_or_add_rPr()
            fonts = rpr.find(qn("w:rFonts"))
            if fonts is None:
                fonts = OxmlElement("w:rFonts"); rpr.append(fonts)
            for attr in ("w:ascii", "w:hAnsi", "w:eastAsia", "w:cs"):
                fonts.set(qn(attr), FONT)
            for attr in ("w:asciiTheme", "w:hAnsiTheme", "w:eastAsiaTheme", "w:cstheme"):
                if fonts.get(qn(attr)) is not None:
                    del fonts.attrib[qn(attr)]
            hp = h.paragraph_format
            hp.space_before = Pt(0 if level == 1 else 12)
            hp.space_after = Pt(12 if level == 1 else 6)
            hp.line_spacing_rule = WD_LINE_SPACING.ONE_POINT_FIVE
            hp.keep_with_next = True
            hp.first_line_indent = Cm(0) if level == 1 else Cm(1.25)
            hp.alignment = WD_ALIGN_PARAGRAPH.CENTER if level == 1 else WD_ALIGN_PARAGRAPH.LEFT
        # page number in the footer of every page except the title page
        footer = sec.footer.paragraphs[0]
        footer.alignment = WD_ALIGN_PARAGRAPH.CENTER
        footer.paragraph_format.first_line_indent = Cm(0)
        self.field(footer, "PAGE")
        # ask Word to refresh the table of contents when the file is opened
        settings = doc.settings.element
        upd = OxmlElement("w:updateFields"); upd.set(qn("w:val"), "true")
        settings.append(upd)

    def field(self, paragraph, instr, placeholder=""):
        run = paragraph.add_run()
        b = OxmlElement("w:fldChar"); b.set(qn("w:fldCharType"), "begin")
        t = OxmlElement("w:instrText"); t.set(qn("xml:space"), "preserve"); t.text = instr
        s = OxmlElement("w:fldChar"); s.set(qn("w:fldCharType"), "separate")
        run._r.append(b); run._r.append(t); run._r.append(s)
        r2 = paragraph.add_run(placeholder)
        e = OxmlElement("w:fldChar"); e.set(qn("w:fldCharType"), "end")
        r2._r.append(e)

    def runs(self, paragraph, text, size=None, bold=False):
        text = self.resolve(text)
        for part in INLINE.split(text):
            if not part:
                continue
            if part.startswith("**") and part.endswith("**") and len(part) > 4:
                r = paragraph.add_run(part[2:-2]); r.bold = True
            elif part.startswith("`") and part.endswith("`"):
                r = paragraph.add_run(part[1:-1]); r.font.name = MONO
                r._element.rPr.rFonts.set(qn("w:hAnsi"), MONO)
                r.font.size = Pt((size or 14) - 2)
            elif part.startswith("*") and part.endswith("*") and len(part) > 2:
                r = paragraph.add_run(part[1:-1]); r.italic = True
            else:
                r = paragraph.add_run(part)
            if size:
                r.font.size = Pt(size) if not part.startswith("`") else r.font.size
            if bold:
                r.bold = True

    def para(self, text, indent=True, align=None, size=None, bold=False, before=0, after=0, keep=False):
        p = self.doc.add_paragraph()
        pf = p.paragraph_format
        if not indent:
            pf.first_line_indent = Cm(0)
        if align is not None:
            pf.alignment = align
        pf.space_before, pf.space_after = Pt(before), Pt(after)
        if keep:
            pf.keep_with_next = True
        self.runs(p, text, size, bold)
        return p

    def page_break(self):
        self.doc.add_paragraph().add_run().add_break(WD_BREAK.PAGE)

    # -------------------------------------------------------------- pieces
    def title_page(self, meta):
        c = WD_ALIGN_PARAGRAPH.CENTER
        for line in meta["organization"]:
            self.para(line, indent=False, align=c)
        self.para("", indent=False)
        self.para(meta["department"], indent=False, align=c)
        for _ in range(3):
            self.para("", indent=False)
        self.para("ВЫПУСКНАЯ КВАЛИФИКАЦИОННАЯ РАБОТА", indent=False, align=c, bold=True)
        self.para("(магистерская диссертация)", indent=False, align=c)
        self.para("", indent=False)
        self.para("на тему:", indent=False, align=c)
        self.para("«" + meta["title"] + "»", indent=False, align=c, bold=True)
        self.para("", indent=False)
        self.para(meta["direction"], indent=False, align=c)
        self.para(meta["program"], indent=False, align=c)
        for _ in range(3):
            self.para("", indent=False)
        r = WD_ALIGN_PARAGRAPH.LEFT
        for label, value in meta["signatures"]:
            p = self.para(label, indent=False, align=r)
            p.paragraph_format.left_indent = Cm(8)
            p = self.para(value, indent=False, align=r, after=6)
            p.paragraph_format.left_indent = Cm(8)
        for _ in range(2):
            self.para("", indent=False)
        self.para(meta["city_year"], indent=False, align=c)
        self.page_break()

    def contents(self):
        self.para("СОДЕРЖАНИЕ", indent=False, align=WD_ALIGN_PARAGRAPH.CENTER, bold=True, after=12)
        p = self.doc.add_paragraph()
        p.paragraph_format.first_line_indent = Cm(0)
        self.field(p, 'TOC \\o "1-2" \\h \\z \\u',
                   "Оглавление обновляется при открытии файла (или: правая кнопка мыши → «Обновить поле»).")
        self.page_break()

    def heading(self, level, text, first=False):
        if level == 1 and not first:
            self.page_break()
        shown = text.upper() if level == 1 else text
        h = self.doc.add_heading(level=level)
        self.runs(h, shown)

    def equation(self, body, num):
        p = self.doc.add_paragraph()
        pf = p.paragraph_format
        pf.first_line_indent = Cm(0)
        pf.alignment = WD_ALIGN_PARAGRAPH.LEFT
        pf.space_before, pf.space_after = Pt(6), Pt(6)
        pf.tab_stops.add_tab_stop(Cm(8.25), WD_TAB_ALIGNMENT.CENTER)
        pf.tab_stops.add_tab_stop(Cm(16.5), WD_TAB_ALIGNMENT.RIGHT)
        p.add_run("\t")
        r = p.add_run(self.resolve(body)); r.italic = True; r.font.name = "Cambria Math"
        r._element.rPr.rFonts.set(qn("w:hAnsi"), "Cambria Math")
        p.add_run(f"\t({num})")

    def table(self, cap, rows, num):
        self.para(f"Таблица {num} – {cap}", indent=False, align=WD_ALIGN_PARAGRAPH.LEFT,
                  before=6, keep=True)
        ncol = max(len(r) for r in rows)
        t = self.doc.add_table(rows=len(rows), cols=ncol)
        t.style = "Table Grid"
        t.alignment = WD_TABLE_ALIGNMENT.CENTER
        for ri, row in enumerate(rows):
            tr = t.rows[ri]
            trPr = tr._tr.get_or_add_trPr()
            cant = OxmlElement("w:cantSplit"); trPr.append(cant)
            if ri == 0:
                hdr = OxmlElement("w:tblHeader"); trPr.append(hdr)
            for ci in range(ncol):
                text = row[ci] if ci < len(row) else ""
                cell = tr.cells[ci]
                p = cell.paragraphs[0]
                pf = p.paragraph_format
                pf.first_line_indent = Cm(0)
                pf.line_spacing_rule = WD_LINE_SPACING.SINGLE
                pf.alignment = WD_ALIGN_PARAGRAPH.LEFT if ci == 0 else WD_ALIGN_PARAGRAPH.CENTER
                if ri == 0:
                    pf.alignment = WD_ALIGN_PARAGRAPH.CENTER
                    pf.keep_with_next = True
                self.runs(p, text, size=12, bold=(ri == 0))
        self.para("", indent=False)

    def figure(self, cap, path, num, width):
        p = self.doc.add_paragraph()
        pf = p.paragraph_format
        pf.first_line_indent = Cm(0)
        pf.alignment = WD_ALIGN_PARAGRAPH.CENTER
        pf.keep_with_next = True
        pf.space_before = Pt(6)
        p.add_run().add_picture(str(HERE / path), width=Cm(width))
        self.para(f"Рисунок {num} – {cap}", indent=False, align=WD_ALIGN_PARAGRAPH.CENTER, after=6)

    def code(self, lines):
        for line in lines:
            p = self.doc.add_paragraph()
            pf = p.paragraph_format
            pf.first_line_indent = Cm(0)
            pf.alignment = WD_ALIGN_PARAGRAPH.LEFT
            pf.line_spacing_rule = WD_LINE_SPACING.SINGLE
            r = p.add_run(line if line else " ")
            r.font.name = MONO
            r._element.rPr.rFonts.set(qn("w:hAnsi"), MONO)
            r.font.size = Pt(10)

    def references(self, order, refs):
        self.heading(1, "СПИСОК ИСПОЛЬЗОВАННЫХ ИСТОЧНИКОВ")
        for i, k in enumerate(order, 1):
            p = self.doc.add_paragraph()
            pf = p.paragraph_format
            pf.alignment = WD_ALIGN_PARAGRAPH.LEFT
            r = p.add_run(f"{i}. {refs[k]}")


# ----------------------------------------------------------------- HTML preview
HTML_CSS = """<!doctype html><html lang="ru"><head><meta charset="utf-8"><style>
@page { size: A4; margin: 2cm 1.5cm 2cm 3cm; }
html, body { margin: 0; padding: 0; background: #fff; }
body { font-family: "Times New Roman", Times, serif; font-size: 14pt; line-height: 1.5; color: #000; }
p { margin: 0; text-indent: 1.25cm; text-align: justify; orphans: 2; widows: 2; hyphens: auto; -webkit-hyphens: auto; }
p.c { text-align: center; text-indent: 0; } p.l { text-align: left; text-indent: 0; }
h1 { font-size: 14pt; text-align: center; margin: 0 0 12pt; break-before: page; page-break-before: always; }
h1.first { break-before: auto; page-break-before: auto; }
h2, h3 { font-size: 14pt; margin: 12pt 0 6pt; text-indent: 1.25cm; break-after: avoid; page-break-after: avoid; }
.brk { break-after: page; page-break-after: always; }
table { width: 100%; border-collapse: collapse; font-size: 12pt; line-height: 1.15; margin-bottom: 8pt; }
th, td { border: 0.6pt solid #000; padding: 2pt 4pt; text-align: center; } td:first-child { text-align: left; }
th { font-weight: bold; } tr { break-inside: avoid; page-break-inside: avoid; }
figure { margin: 6pt 0; text-align: center; break-inside: avoid; page-break-inside: avoid; }
figure img { display: block; margin: 0 auto 3pt; }
pre { font-family: "Courier New", monospace; font-size: 10pt; line-height: 1.15; margin: 4pt 0; white-space: pre-wrap; }
.eq { display: flex; justify-content: space-between; margin: 6pt 0; font-style: italic; font-family: "Cambria Math", serif; }
.eq span:last-child { font-style: normal; } code { font-family: "Courier New", monospace; font-size: 12pt; }
.toc p { text-indent: 0; text-align: left; } .toc p.s { padding-left: 1cm; }
</style></head><body>
"""


def html_inline(w, text):
    import html as H
    out = []
    for part in INLINE.split(w.resolve(text)):
        if not part:
            continue
        if part.startswith("**") and part.endswith("**") and len(part) > 4:
            out.append("<b>" + H.escape(part[2:-2]) + "</b>")
        elif part.startswith("`") and part.endswith("`"):
            out.append("<code>" + H.escape(part[1:-1]) + "</code>")
        elif part.startswith("*") and part.endswith("*") and len(part) > 2:
            out.append("<i>" + H.escape(part[1:-1]) + "</i>")
        else:
            out.append(H.escape(part))
    return "".join(out)


def write_html(w, blocks, meta, order, refs, path):
    import html as H
    o = [HTML_CSS]
    for line in meta["organization"]:
        o.append(f'<p class="c">{H.escape(line)}</p>')
    o.append(f'<p class="c"><br>{H.escape(meta["department"])}<br><br><br><br></p>')
    o.append('<p class="c"><b>ВЫПУСКНАЯ КВАЛИФИКАЦИОННАЯ РАБОТА</b></p><p class="c">(магистерская диссертация)</p>')
    o.append(f'<p class="c"><br>на тему:</p><p class="c"><b>«{H.escape(meta["title"])}»</b><br><br></p>')
    o.append(f'<p class="c">{H.escape(meta["direction"])}</p><p class="c">{H.escape(meta["program"])}<br><br><br></p>')
    for label, value in meta["signatures"]:
        o.append(f'<p class="l" style="margin-left:8cm">{H.escape(label)}<br>{H.escape(value)}</p>')
    o.append(f'<p class="c"><br><br>{H.escape(meta["city_year"])}</p><div class="brk"></div>')
    heads = [(k, d) for k, d, _ in blocks if k in ("h1", "h2")]
    first = True
    for kind, data, num in blocks:
        if kind == "h1":
            if data.upper().startswith("ВВЕДЕНИЕ"):
                o.append('<h1>СОДЕРЖАНИЕ</h1><div class="toc">')
                started = listed = False
                for k, d in heads:
                    started = started or d.upper().startswith("ВВЕДЕНИЕ")
                    if not started:
                        continue
                    if k == "h1" and d.upper().startswith("ПРИЛОЖЕНИЕ") and not listed:
                        o.append('<p>СПИСОК ИСПОЛЬЗОВАННЫХ ИСТОЧНИКОВ</p>')
                        listed = True
                    o.append(f'<p class="{"s" if k == "h2" else ""}">{H.escape(d.upper() if k == "h1" else d)}</p>')
                o.append("</div>")
            if data.upper().startswith("ПРИЛОЖЕНИЕ") and not any("СПИСОК ИСПОЛЬЗОВАННЫХ" in x and x.startswith("<h1") for x in o):
                o.append("<h1>СПИСОК ИСПОЛЬЗОВАННЫХ ИСТОЧНИКОВ</h1>")
                o += [f'<p class="l">{i}. {H.escape(refs[k])}</p>' for i, k in enumerate(order, 1)]
            o.append(f'<h1 class="{"first" if first else ""}">{html_inline(w, data.upper())}</h1>')
            first = False
        elif kind in ("h2", "h3"):
            o.append(f"<{kind}>{html_inline(w, data)}</{kind}>")
        elif kind in ("p", "li"):
            o.append(f"<p>{html_inline(w, data)}</p>")
        elif kind == "break":
            o.append('<div class="brk"></div>')
        elif kind == "eq":
            o.append(f'<div class="eq"><span></span><span>{html_inline(w, data[0])}</span><span>({num})</span></div>')
        elif kind == "table":
            cap, _, rows = data
            o.append(f'<p class="l">Таблица {num} – {html_inline(w, cap)}</p><table>')
            for ri, row in enumerate(rows):
                tag = "th" if ri == 0 else "td"
                o.append("<tr>" + "".join(f"<{tag}>{html_inline(w, c)}</{tag}>" for c in row) + "</tr>")
            o.append("</table>")
        elif kind == "fig":
            cap, src, _, width = data
            o.append(f'<figure><img src="{src}" style="width:{width}cm"><p class="c">Рисунок {num} – {html_inline(w, cap)}</p></figure>')
        elif kind == "code":
            o.append("<pre>" + H.escape("\n".join(data)) + "</pre>")
    o.append("</body></html>")
    path.write_text("\n".join(o), encoding="utf-8")


def main():
    meta = json.loads((HERE / "meta.json").read_text(encoding="utf-8"))
    refs = json.loads((HERE / "references.json").read_text(encoding="utf-8"))
    blocks, labels = number_objects(read_blocks())
    numbers, order = citation_order(blocks, refs)
    counts = {
        "%SOURCES%": str(len(order)),
        "%FIGURES%": str(sum(1 for k, _, _ in blocks if k == "fig")),
        "%TABLES%": str(sum(1 for k, _, _ in blocks if k == "table")),
        "%APPENDICES%": str(sum(1 for k, d, _ in blocks if k == "h1" and d.upper().startswith("ПРИЛОЖЕНИЕ"))),
        "%PAGES%": str(meta.get("pages", "[N]")),
    }

    def fill(text):
        for key, value in counts.items():
            text = text.replace(key, value)
        return text
    blocks = [(k, fill(d) if k in ("p", "li") else d, n) for k, d, n in blocks]
    w = Writer(numbers, labels)
    w.title_page(meta)
    first = True
    contents_done = False
    refs_done = False
    for kind, data, num in blocks:
        if kind == "h1":
            if data.upper().startswith("ВВЕДЕНИЕ") and not contents_done:
                w.page_break()
                w.contents(); contents_done = True; first = True
            if data.upper().startswith("ПРИЛОЖЕНИЕ") and not refs_done:
                w.references(order, refs); refs_done = True
            w.heading(1, data, first); first = False
        elif kind in ("h2", "h3"):
            w.heading(int(kind[1]), data)
        elif kind == "p":
            w.para(data)
        elif kind == "li":
            w.para(data)
        elif kind == "break":
            w.page_break()
        elif kind == "eq":
            w.equation(data[0], num)
        elif kind == "table":
            w.table(data[0], data[2], num)
        elif kind == "fig":
            w.figure(data[0], data[1], num, data[3])
        elif kind == "code":
            w.code(data)
    if not refs_done:
        w.references(order, refs)
    w.doc.save(OUT)
    write_html(w, blocks, meta, order, refs, OUT.with_suffix(".html"))
    words = sum(len(re.findall(r"\w+", d if isinstance(d, str) else " ".join(map(str, d))))
                for k, d, _ in blocks if k in ("p", "li"))
    print(f"{OUT.name}: {len(order)} sources, {len(labels)} labels, ~{words} words in body text")


if __name__ == "__main__":
    sys.exit(main())
