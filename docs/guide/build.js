// Собирает руководство из content.js: Tongway-руководство.docx и .txt (PDF — из .docx через LibreOffice).
// Запуск: node docs/guide/build.js
const fs = require("fs");
const path = require("path");
const {
  Document, Packer, Paragraph, TextRun, HeadingLevel, AlignmentType, LevelFormat, Table, TableRow, TableCell,
  WidthType, ShadingType, BorderStyle, Footer, PageNumber, PageBreak,
} = require("docx");
const guide = require("./content.js");

const OUT = path.join(__dirname, "Tongway-руководство");
const ACCENT = "EA5F0A";
const FONT = "Arial";

// ---------- .txt ----------
function wrap(text, width = 90, indent = "") {
  const words = text.split(/\s+/);
  const lines = [];
  let line = indent;
  for (const w of words) {
    if (line.trim().length && (line + " " + w).length > width) {
      lines.push(line);
      line = indent.replace(/./g, " ") + w;
    } else {
      line = line.trim().length ? line + " " + w : line + w;
    }
  }
  if (line.trim().length) lines.push(line);
  return lines.join("\n");
}

function txt() {
  const out = [];
  out.push(guide.title.toUpperCase() + " — " + guide.subtitle.toUpperCase(), guide.version, "", "СОДЕРЖАНИЕ", "");
  guide.blocks.filter((b) => b.h1).forEach((b) => out.push("  " + b.h1));
  out.push("");
  for (const b of guide.blocks) {
    if (b.h1) out.push("", "=".repeat(90), b.h1.toUpperCase(), "=".repeat(90), "");
    else if (b.h2) out.push("", b.h2, "-".repeat(b.h2.length), "");
    else if (b.p) out.push(wrap(b.p), "");
    else if (b.ul) { b.ul.forEach((i) => out.push(wrap(i, 90, "  • "))); out.push(""); }
    else if (b.ol) { b.ol.forEach((i, n) => out.push(wrap(i, 90, `  ${n + 1}. `))); out.push(""); }
    else if (b.note) out.push(wrap(b.note, 90, "  ВАЖНО: "), "");
    else if (b.tip) out.push(wrap(b.tip, 90, "  СОВЕТ: "), "");
    else if (b.table) {
      for (const row of b.table.rows) {
        out.push(wrap(row[0], 90, "  • "));
        row.slice(1).forEach((c, i) => out.push(wrap(b.table.head[i + 1] + ": " + c, 90, "      ")));
      }
      out.push("");
    }
  }
  return out.join("\n").replace(/\n{3,}/g, "\n\n") + "\n";
}

// ---------- .docx ----------
const run = (text, opts = {}) => new TextRun({ text, font: FONT, ...opts });

function box(label, text, fill, border) {
  return new Paragraph({
    spacing: { before: 120, after: 160 },
    shading: { type: ShadingType.CLEAR, color: "auto", fill },
    border: { left: { style: BorderStyle.SINGLE, size: 24, color: border, space: 8 } },
    indent: { left: 200, right: 100 },
    children: [run(label + " ", { bold: true, color: border, size: 21 }), run(text, { size: 21 })],
  });
}

function table(t) {
  const total = 9360;
  const widths = t.head.length === 2 ? [3000, 6360] : [2600, 3380, 3380];
  const cell = (text, i, head) => new TableCell({
    width: { size: widths[i], type: WidthType.DXA },
    shading: head ? { type: ShadingType.CLEAR, color: "auto", fill: "2B2B2B" } : undefined,
    margins: { top: 80, bottom: 80, left: 120, right: 120 },
    children: [new Paragraph({ children: [run(text, { size: 19, bold: head || i === 0, color: head ? "FFFFFF" : undefined })] })],
  });
  return new Table({
    width: { size: total, type: WidthType.DXA },
    columnWidths: widths,
    rows: [
      new TableRow({ tableHeader: true, children: t.head.map((h, i) => cell(h, i, true)) }),
      ...t.rows.map((r) => new TableRow({ children: r.map((c, i) => cell(c, i, false)) })),
    ],
  });
}

function docx() {
  const children = [
    new Paragraph({ spacing: { before: 2400, after: 120 }, children: [run(guide.title, { size: 72, bold: true, color: ACCENT })] }),
    new Paragraph({ spacing: { after: 240 }, children: [run(guide.subtitle, { size: 36 })] }),
    new Paragraph({
      spacing: { after: 600 },
      border: { bottom: { style: BorderStyle.SINGLE, size: 12, color: ACCENT, space: 6 } },
      children: [run(guide.version, { size: 20, color: "666666" })],
    }),
    new Paragraph({ spacing: { after: 120 }, children: [run("Переводчик английского текста на русский поверх любых приложений Android: кружок-прицел, текст с картинок и видео, перевод всего экрана, пальцевый перевод и Live-перевод субтитров.", { size: 22 })] }),
    new Paragraph({ children: [new PageBreak()] }),
    new Paragraph({ spacing: { after: 200 }, children: [run("Содержание", { size: 32, bold: true, color: ACCENT })] }),
    // Содержание — обычным списком разделов: поле оглавления Word не заполняется при сохранении в PDF.
    ...guide.blocks.filter((b) => b.h1).map((b) => new Paragraph({ spacing: { after: 90 }, indent: { left: 200 }, children: [run(b.h1, { size: 23 })] })),
    new Paragraph({ children: [new PageBreak()] }),
  ];
  for (const b of guide.blocks) {
    if (b.h1) children.push(new Paragraph({ heading: HeadingLevel.HEADING_1, children: [run(b.h1)] }));
    else if (b.h2) children.push(new Paragraph({ heading: HeadingLevel.HEADING_2, children: [run(b.h2)] }));
    else if (b.p) children.push(new Paragraph({ spacing: { after: 140 }, children: [run(b.p)] }));
    else if (b.ul) b.ul.forEach((i) => children.push(new Paragraph({ numbering: { reference: "bullets", level: 0 }, spacing: { after: 70 }, children: [run(i)] })));
    else if (b.ol) {
      const ref = "num" + children.length;
      numberingConfigs.push({ reference: ref, levels: [{ level: 0, format: LevelFormat.DECIMAL, text: "%1.", alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 540, hanging: 360 } } } }] });
      b.ol.forEach((i) => children.push(new Paragraph({ numbering: { reference: ref, level: 0 }, spacing: { after: 70 }, children: [run(i)] })));
    } else if (b.note) children.push(box("Важно.", b.note, "FFF1E8", ACCENT));
    else if (b.tip) children.push(box("Совет.", b.tip, "F2F2F2", "777777"));
    else if (b.table) children.push(table(b.table), new Paragraph({ spacing: { after: 120 }, children: [] }));
  }
  return new Document({
    creator: "Tongway",
    title: guide.title + " — " + guide.subtitle,
    styles: {
      default: { document: { run: { font: FONT, size: 22 } } },
      paragraphStyles: [
        { id: "Heading1", name: "Heading 1", basedOn: "Normal", next: "Normal", quickFormat: true,
          run: { size: 32, bold: true, color: ACCENT, font: FONT }, paragraph: { spacing: { before: 360, after: 160 }, outlineLevel: 0, keepNext: true } },
        { id: "Heading2", name: "Heading 2", basedOn: "Normal", next: "Normal", quickFormat: true,
          run: { size: 26, bold: true, color: "222222", font: FONT }, paragraph: { spacing: { before: 240, after: 120 }, outlineLevel: 1, keepNext: true } },
      ],
    },
    numbering: { config: numberingConfigs },
    sections: [{
      properties: { page: { margin: { top: 1200, bottom: 1200, left: 1300, right: 1300 } } },
      footers: { default: new Footer({ children: [new Paragraph({ alignment: AlignmentType.CENTER, children: [run("Tongway · руководство пользователя · стр. ", { size: 16, color: "888888" }), new TextRun({ children: [PageNumber.CURRENT], size: 16, color: "888888", font: FONT })] })] }) },
      children,
    }],
  });
}

const numberingConfigs = [
  { reference: "bullets", levels: [{ level: 0, format: LevelFormat.BULLET, text: "•", alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 540, hanging: 300 } } } }] },
];

fs.writeFileSync(OUT + ".txt", txt());
Packer.toBuffer(docx()).then((buf) => {
  fs.writeFileSync(OUT + ".docx", buf);
  console.log("готово:", OUT + ".docx", OUT + ".txt");
});
