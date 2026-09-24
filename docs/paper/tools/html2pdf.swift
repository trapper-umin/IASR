// Paginated A4 PDF from a print-HTML file, using the WebKit that ships with
// macOS — no browser or office suite needed.
//
//   swift docs/paper/tools/html2pdf.swift in.html out.pdf
//
// Margins (2 / 2 / 3 / 1.5 cm, as in the .docx section) come from the HTML's
// @page rule; WKWebView printing does not apply NSPrintInfo margins.
import AppKit
import WebKit
import PDFKit

let args = CommandLine.arguments
guard args.count == 3 else { FileHandle.standardError.write("usage: html2pdf in.html out.pdf\n".data(using: .utf8)!); exit(2) }
let input = URL(fileURLWithPath: args[1]).standardizedFileURL
let output = URL(fileURLWithPath: args[2]).standardizedFileURL
let cm: CGFloat = 72.0 / 2.54

final class Printer: NSObject, WKNavigationDelegate {
    let web: WKWebView
    let window: NSWindow
    init(size: NSSize) {
        web = WKWebView(frame: NSRect(origin: .zero, size: size))
        window = NSWindow(contentRect: web.frame, styleMask: [.borderless], backing: .buffered, defer: false)
        super.init()
        window.contentView = web
        web.navigationDelegate = self
    }
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        // Give fonts and images a moment to settle before pagination.
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { self.print() }
    }
    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { fail(error) }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { fail(error) }
    func fail(_ e: Error) { FileHandle.standardError.write("load failed: \(e)\n".data(using: .utf8)!); exit(1) }

    func print() {
        let info = NSPrintInfo(dictionary: [
            .jobDisposition: NSPrintInfo.JobDisposition.save,
            .jobSavingURL: output,
        ])
        info.paperSize = NSSize(width: 21.0 * cm, height: 29.7 * cm)
        // WKWebView printing ignores NSPrintInfo margins (measured: text ran to
        // the paper edge), so the margins live in the HTML's @page rule.
        info.topMargin = 0
        info.bottomMargin = 0
        info.leftMargin = 0
        info.rightMargin = 0
        info.horizontalPagination = .fit
        info.verticalPagination = .automatic
        info.isHorizontallyCentered = false
        info.isVerticallyCentered = false
        let op = web.printOperation(with: info)
        op.showsPrintPanel = false
        op.showsProgressPanel = false
        op.view?.frame = web.bounds
        op.runModal(for: window, delegate: self, didRun: #selector(done(_:success:contextInfo:)), contextInfo: nil)
    }
    @objc func done(_ op: NSPrintOperation, success: Bool, contextInfo: UnsafeMutableRawPointer?) {
        if success {
            // WebKit can emit a trailing page with nothing on it (the last
            // element's bottom margin spills over); drop blank pages at the end.
            if let pdf = PDFDocument(url: output) {
                while pdf.pageCount > 1,
                      let last = pdf.page(at: pdf.pageCount - 1),
                      (last.string ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                      last.annotations.isEmpty {
                    pdf.removePage(at: pdf.pageCount - 1)
                }
                pdf.write(to: output)
            }
            Swift.print("written \(output.path)"); exit(0)
        }
        FileHandle.standardError.write("print failed\n".data(using: .utf8)!); exit(1)
    }
}

let app = NSApplication.shared
app.setActivationPolicy(.prohibited)
// Width of the text block (21 − 3 − 1.5 cm) so the layout matches the page.
let printer = Printer(size: NSSize(width: 16.5 * cm, height: 100))
printer.web.loadFileURL(input, allowingReadAccessTo: input.deletingLastPathComponent().deletingLastPathComponent())
app.run()
