#!/usr/bin/env python3
from pathlib import Path

OUTPUT = Path(__file__).resolve().parents[1] / "samples/contracts/northstar-bakery-electricity.pdf"

LINES = [
    "FICTIONAL SAMPLE CONTRACT",
    "NOT A REAL AGREEMENT - FOR SOFTWARE TESTING ONLY",
    "",
    "CUSTOMER: Northstar Bakery",
    "PROVIDER: Example Energy",
    "PRICING_MODEL: FIXED",
    "",
    "Service address: 42 Example Street, Sampletown",
    "Start date: 1 January 2027",
    "End date: 31 December 2027",
    "Annual usage: 8,400 kWh",
    "All names, addresses, dates, and terms on this page are invented.",
]

def literal(value):
    return value.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")

def build_pdf():
    commands = ["BT", "/F1 13 Tf", "54 750 Td"]
    for index, line in enumerate(LINES):
        if index:
            commands.append("0 -27 Td")
        commands.append(f"({literal(line)}) Tj")
    commands.append("ET")
    stream = ("\n".join(commands) + "\n").encode("ascii")
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        b"<< /Length " + str(len(stream)).encode("ascii") + b" >>\nstream\n" + stream + b"endstream",
    ]
    pdf = bytearray(b"%PDF-1.4\n")
    offsets = [0]
    for number, body in enumerate(objects, start=1):
        offsets.append(len(pdf))
        pdf.extend(f"{number} 0 obj\n".encode("ascii"))
        pdf.extend(body + b"\nendobj\n")
    xref_offset = len(pdf)
    pdf.extend(f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode("ascii"))
    for offset in offsets[1:]:
        pdf.extend(f"{offset:010d} 00000 n \n".encode("ascii"))
    pdf.extend(
        f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref_offset}\n%%EOF\n".encode("ascii")
    )
    return bytes(pdf)

if __name__ == "__main__":
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_bytes(build_pdf())
    print(f"Wrote fictional sample PDF: {OUTPUT.relative_to(Path(__file__).resolve().parents[1])}")
