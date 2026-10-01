# -*- coding: utf-8 -*-
"""Tracked-change editing of word/document.xml, in place.

Every edit becomes a Word revision (w:ins / w:del) attributed to AUTHOR, so the
co-author sees each change and can accept or reject it individually. Nothing
outside the edited ranges is touched: the teacher's text, highlights and
formatting stay exactly as she left them.
"""
import copy
from xml.dom import minidom

W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
XML = 'http://www.w3.org/XML/1998/namespace'
AUTHOR = 'Бобков А.Н.'
DATE = '2026-09-29T12:00:00Z'


class Doc:
    def __init__(self, path):
        self.path = path
        self.dom = minidom.parse(path)
        self.body = self.dom.getElementsByTagNameNS(W, 'body')[0]
        self._id = 20000

    # ------------------------------------------------------------ plumbing
    def el(self, tag):
        return self.dom.createElementNS(W, 'w:' + tag)

    def rev(self, tag):
        e = self.el(tag)
        self._id += 1
        e.setAttributeNS(W, 'w:id', str(self._id))
        e.setAttributeNS(W, 'w:author', AUTHOR)
        e.setAttributeNS(W, 'w:date', DATE)
        return e

    def save(self, path=None):
        with open(path or self.path, 'w', encoding='utf-8') as fh:
            fh.write(self.dom.toxml())

    @staticmethod
    def kids(node, name):
        return [c for c in node.childNodes if c.nodeType == 1 and c.localName == name]

    def paragraphs(self):
        return list(self.body.getElementsByTagNameNS(W, 'p'))

    def runs(self, p):
        return self.kids(p, 'r')

    def rtext(self, r):
        return ''.join((t.firstChild.data if t.firstChild else '') for t in self.kids(r, 't'))

    def ptext(self, p):
        return ''.join(self.rtext(r) for r in self.runs(p))

    def find(self, contains, nth=0, start_after=None):
        ps = self.paragraphs()
        if start_after is not None:
            ps = ps[ps.index(start_after) + 1:]
        hits = [p for p in ps if contains in self.ptext(p)]
        assert hits, 'paragraph not found: %r' % contains
        return hits[nth]

    # ------------------------------------------------------------ runs
    def _normalize(self, r):
        """Merge several w:t in one run into one, so it can be split."""
        # lastRenderedPageBreak is a layout cache Word recomputes on open;
        # dropping it from a run that is being split changes nothing visible.
        for x in self.kids(r, 'lastRenderedPageBreak'):
            r.removeChild(x)
        ts = self.kids(r, 't')
        other = [c for c in r.childNodes if c.nodeType == 1 and c.localName not in ('rPr', 't')]
        assert not other, 'run has non-text content: %s' % [c.localName for c in other]
        if len(ts) > 1:
            text = ''.join((t.firstChild.data if t.firstChild else '') for t in ts)
            for t in ts[1:]:
                r.removeChild(t)
            self._set(r, text)

    def _set(self, r, text):
        t = self.kids(r, 't')[0]
        while t.firstChild:
            t.removeChild(t.firstChild)
        t.appendChild(self.dom.createTextNode(text))
        t.setAttributeNS(XML, 'xml:space', 'preserve')

    def _split(self, r, k):
        """Split run r at character k; return the right-hand run."""
        self._normalize(r)
        s = self.rtext(r)
        right = r.cloneNode(True)
        self._set(r, s[:k])
        self._set(right, s[k:])
        r.parentNode.insertBefore(right, r.nextSibling)
        return right

    def _plain_rpr(self, r):
        """Copy of a run's formatting without the teacher's highlight."""
        rpr = self.kids(r, 'rPr')
        if not rpr:
            return None
        rpr = rpr[0].cloneNode(True)
        for h in self.kids(rpr, 'highlight'):
            rpr.removeChild(h)
        return rpr

    def _new_run(self, text, rpr):
        r = self.el('r')
        if rpr is not None:
            r.appendChild(rpr)
        t = self.el('t')
        t.setAttributeNS(XML, 'xml:space', 'preserve')
        t.appendChild(self.dom.createTextNode(text))
        r.appendChild(t)
        return r

    def _delete_run(self, r):
        d = self.rev('del')
        r.parentNode.insertBefore(d, r)
        r.parentNode.removeChild(r)
        d.appendChild(r)
        for t in self.kids(r, 't'):
            dt = self.el('delText')
            dt.setAttributeNS(XML, 'xml:space', 'preserve')
            dt.appendChild(self.dom.createTextNode(t.firstChild.data if t.firstChild else ''))
            r.replaceChild(dt, t)
        return d

    # ------------------------------------------------------------ edits
    def replace(self, p, old, new, at=None):
        """Tracked replacement of `old` by `new`.

        `old` must be unique in the paragraph unless its position is pinned
        with `at` (used for the leading number of a reference entry).
        """
        full = self.ptext(p)
        if at is not None:
            assert full[at:at + len(old)] == old, (full[:20], old)
            i = at
        else:
            i = full.find(old)
            assert i >= 0, 'not found: %r\n in: %r' % (old, full[:300])
            assert full.find(old, i + 1) < 0, 'not unique: %r' % old
        a, b = i, i + len(old)
        # split so that [a, b) is made of whole runs
        pos = 0
        for r in list(self.runs(p)):
            n = len(self.rtext(r))
            if pos < a < pos + n:
                self._split(r, a - pos)
                break
            pos += n
        pos = 0
        for r in list(self.runs(p)):
            n = len(self.rtext(r))
            if pos < b < pos + n:
                self._split(r, b - pos)
                break
            pos += n
        # collect the runs covering [a, b), and the run just before a
        pos, doomed, before, after = 0, [], None, None
        for r in self.runs(p):
            n = len(self.rtext(r))
            if pos + n <= a and n:
                before = r
            if a <= pos and pos + n <= b and n:
                doomed.append(r)
            if pos >= b and after is None and n:
                after = r
            pos += n
        template = before or (doomed[0] if doomed else after)
        anchor = doomed[0] if doomed else after
        if new:
            ins = self.rev('ins')
            ins.appendChild(self._new_run(new, self._plain_rpr(template)))
            if anchor is not None:
                p.insertBefore(ins, anchor)
            else:
                p.appendChild(ins)
        for r in doomed:
            self._delete_run(r)

    def insert_after(self, p, anchor, text):
        """Tracked insertion of `text` right after the unique `anchor`."""
        full = self.ptext(p)
        i = full.find(anchor)
        assert i >= 0 and full.find(anchor, i + 1) < 0, 'anchor not unique/found: %r' % anchor
        k = i + len(anchor)
        pos, before, after = 0, None, None
        for r in list(self.runs(p)):
            n = len(self.rtext(r))
            if pos < k < pos + n:
                after = self._split(r, k - pos)
                before = r
                break
            if pos + n == k:
                before = r
            pos += n
        if after is None and before is not None:
            nxt = before.nextSibling
            while nxt is not None and not (nxt.nodeType == 1 and nxt.localName in ('r', 'ins', 'del')):
                nxt = nxt.nextSibling
            after = nxt
        ins = self.rev('ins')
        ins.appendChild(self._new_run(text, self._plain_rpr(before)))
        if after is not None:
            p.insertBefore(ins, after)
        else:
            p.appendChild(ins)

    def _mark_paragraph(self, p, kind):
        ppr = self.kids(p, 'pPr')
        if not ppr:
            ppr = [self.el('pPr')]
            p.insertBefore(ppr[0], p.firstChild)
        ppr = ppr[0]
        rpr = self.kids(ppr, 'rPr')
        if not rpr:
            rpr = self.el('rPr')
            tail = [c for c in ppr.childNodes if c.nodeType == 1 and c.localName in ('sectPr', 'pPrChange')]
            ppr.insertBefore(rpr, tail[0] if tail else None)
        else:
            rpr = rpr[0]
        rpr.insertBefore(self.rev(kind), rpr.firstChild)

    def delete_paragraph(self, p):
        for r in self.runs(p):
            self._delete_run(r)
        self._mark_paragraph(p, 'del')

    def insert_paragraph_after(self, ref, text, template):
        p = self.el('p')
        tppr = self.kids(template, 'pPr')
        if tppr:
            ppr = tppr[0].cloneNode(True)
            for x in self.kids(ppr, 'rPr'):
                ppr.removeChild(x)
            p.appendChild(ppr)
        truns = self.runs(template)
        ins = self.rev('ins')
        ins.appendChild(self._new_run(text, self._plain_rpr(truns[0]) if truns else None))
        p.appendChild(ins)
        ref.parentNode.insertBefore(p, ref.nextSibling)
        self._mark_paragraph(p, 'ins')
        return p

    def renumber(self, p, old_n, new_n):
        full = self.ptext(p)
        assert full.startswith('%d.' % old_n), full[:20]
        self.replace(p, '%d.' % old_n, '%d.' % new_n, at=0)

    # elements that must follow w:highlight inside w:rPr (ECMA-376 order)
    _AFTER_HIGHLIGHT = ('u', 'effect', 'bdr', 'shd', 'fitText', 'vertAlign', 'rtl',
                        'cs', 'em', 'lang', 'eastAsianLayout', 'specVanish', 'oMath')

    def _highlight_run(self, r, color):
        rpr = self.kids(r, 'rPr')
        if rpr:
            rpr = rpr[0]
        else:
            rpr = self.el('rPr')
            r.insertBefore(rpr, r.firstChild)
        for h in self.kids(rpr, 'highlight'):
            rpr.removeChild(h)
        h = self.el('highlight')
        h.setAttributeNS(W, 'w:val', color)
        nxt = [c for c in rpr.childNodes if c.nodeType == 1 and c.localName in self._AFTER_HIGHLIGHT]
        rpr.insertBefore(h, nxt[0] if nxt else None)

    def bake(self, color='cyan'):
        """Apply all revisions as plain text; mark every inserted run with a
        highlight instead of change-tracking underline/strike-through."""
        for ins in list(self.dom.getElementsByTagNameNS(W, 'ins')):
            if ins.parentNode is not None and ins.parentNode.localName != 'rPr':
                for r in ins.getElementsByTagNameNS(W, 'r'):
                    self._highlight_run(r, color)
        self.accept_all()

    def accept_all(self):
        """Produce the clean copy: drop deletions, unwrap insertions."""
        for tag in ('del',):
            for d in list(self.dom.getElementsByTagNameNS(W, tag)):
                par = d.parentNode
                if par.localName == 'rPr':      # deleted paragraph mark
                    p = par.parentNode.parentNode
                    par.removeChild(d)
                    if not self.runs(p) and not self.kids(p, 'ins') and p.parentNode:
                        p.parentNode.removeChild(p)
                else:
                    par.removeChild(d)
        for d in list(self.dom.getElementsByTagNameNS(W, 'ins')):
            par = d.parentNode
            if par.localName == 'rPr':
                par.removeChild(d)
            else:
                for c in list(d.childNodes):
                    par.insertBefore(c, d)
                par.removeChild(d)
