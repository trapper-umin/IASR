# -*- coding: utf-8 -*-
"""Integrity checks for the tracked-changes document.

1. Structure: revisions carry the right content (w:delText only inside w:del,
   w:t inside w:ins), ids are unique, all revisions have author and date.
2. Redline integrity: rejecting every revision must give back the teacher's
   document text exactly, paragraph by paragraph. Any difference would be an
   edit that escaped change tracking.
3. Accepting every revision must give the clean copy's text exactly.
"""
import sys, zipfile, copy
from xml.dom import minidom
sys.path.insert(0, __import__('os').path.dirname(__file__))
from track import Doc, W

orig_docx, tracked_docx, clean_docx = sys.argv[1:4]


def load(docx):
    return minidom.parseString(zipfile.ZipFile(docx).read('word/document.xml'))


def para_texts(dom):
    out = []
    for p in dom.getElementsByTagNameNS(W, 'p'):
        s = ''.join(t.firstChild.data if t.firstChild else ''
                    for t in p.getElementsByTagNameNS(W, 't'))
        out.append(s)
    return [s for s in out if s.strip()]


tr = load(tracked_docx)
problems = []
ids = []
for tag in ('ins', 'del'):
    for e in tr.getElementsByTagNameNS(W, tag):
        ids.append(e.getAttributeNS(W, 'id'))
        if not e.getAttributeNS(W, 'author') or not e.getAttributeNS(W, 'date'):
            problems.append('revision without author/date')
        if e.parentNode.localName == 'rPr':
            continue
        if tag == 'del' and e.getElementsByTagNameNS(W, 't').length:
            problems.append('w:t inside w:del')
        if tag == 'ins' and e.getElementsByTagNameNS(W, 'delText').length:
            problems.append('w:delText inside w:ins')
for dt in tr.getElementsByTagNameNS(W, 'delText'):
    anc, ok = dt.parentNode, False
    while anc is not None and anc.nodeType == 1:
        if anc.localName == 'del':
            ok = True
            break
        anc = anc.parentNode
    if not ok:
        problems.append('w:delText outside w:del')
if len(ids) != len(set(ids)):
    problems.append('duplicate revision ids')
print('structure:', 'OK' if not problems else sorted(set(problems)))


def reject_all(dom):
    for e in list(dom.getElementsByTagNameNS(W, 'ins')):
        par = e.parentNode
        if par.localName == 'rPr':                      # inserted paragraph
            p = par.parentNode.parentNode
            p.parentNode.removeChild(p)
        elif e.parentNode is not None:
            par.removeChild(e)
    for e in list(dom.getElementsByTagNameNS(W, 'del')):
        par = e.parentNode
        if par is None:
            continue
        if par.localName == 'rPr':
            par.removeChild(e)
            continue
        for dt in list(e.getElementsByTagNameNS(W, 'delText')):
            t = dom.createElementNS(W, 'w:t')
            t.appendChild(dom.createTextNode(dt.firstChild.data if dt.firstChild else ''))
            dt.parentNode.replaceChild(t, dt)
        for c in list(e.childNodes):
            par.insertBefore(c, e)
        par.removeChild(e)
    return dom


a = para_texts(reject_all(load(tracked_docx)))
b = para_texts(load(orig_docx))
same = a == b
print('reject all == teacher original:', same)
if not same:
    for i, (x, y) in enumerate(zip(a, b)):
        if x != y:
            print('  first diff at paragraph', i, '\n   got:', x[:150], '\n  want:', y[:150])
            break
    print('  counts', len(a), len(b))

# accept all == clean copy
tmp = load(tracked_docx)
dd = Doc.__new__(Doc)
dd.dom = tmp
dd.body = tmp.getElementsByTagNameNS(W, 'body')[0]
dd.accept_all()
print('accept all == clean copy:', para_texts(tmp) == para_texts(load(clean_docx)))
