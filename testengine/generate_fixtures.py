"""Synthetic PDFs only; never read or copy the user's original PDF into fixtures."""
from pathlib import Path
import sys
from pypdf import PdfReader, PdfWriter
from pypdf.generic import (
    ArrayObject, ByteStringObject, DecodedStreamObject, DictionaryObject,
    NameObject, NumberObject, TextStringObject,
)


def save(writer, target):
    with target.open("wb") as stream:
        writer.write(stream)


def clone(path):
    writer = PdfWriter()
    writer.clone_document_from_reader(PdfReader(path))
    return writer


def main(directory):
    directory.mkdir(parents=True, exist_ok=True)
    writer = PdfWriter()
    font = writer._add_object(DictionaryObject({
        NameObject("/Type"): NameObject("/Font"),
        NameObject("/Subtype"): NameObject("/Type1"),
        NameObject("/BaseFont"): NameObject("/Helvetica"),
    }))
    for number in range(1, 7):
        page = writer.add_blank_page(612, 792)
        page[NameObject("/Resources")] = DictionaryObject({
            NameObject("/Font"): DictionaryObject({NameObject("/F1"): font})
        })
        content = DecodedStreamObject()
        content.set_data(f"BT /F1 16 Tf 72 720 Td (ENGINE TEST PAGE {number}) Tj ET".encode())
        page[NameObject("/Contents")] = writer._add_object(content)
    writer.add_metadata({"/Title": "Fixture metadata retained", "/Author": "Engine test"})
    plain = directory / "plain.pdf"
    save(writer, plain)

    existing = clone(plain)
    parent = existing.add_outline_item("原有一级", 1)
    existing.add_outline_item("原有子级", 2, parent=parent)
    save(existing, directory / "existing.pdf")
    external = clone(plain)
    link = external.add_outline_item("网站链接", 0)
    link.get_object()[NameObject("/A")] = DictionaryObject({
        NameObject("/S"): NameObject("/URI"),
        NameObject("/URI"): TextStringObject("https://example.invalid/"),
    })
    missing = external.add_outline_item("失效目标", 0)
    del missing.get_object()[NameObject("/A")]
    missing.get_object()[NameObject("/Dest")] = ArrayObject([NumberObject(999), NameObject("/Fit")])
    save(external, directory / "external-outline.pdf")

    for name, password in [("encrypted-blank", ""), ("encrypted-password", "test-password")]:
        encrypted = clone(plain)
        encrypted.encrypt(user_password=password, owner_password="fixture-owner")
        save(encrypted, directory / f"{name}.pdf")

    for name, signed in [("empty-signature", False), ("signed", True)]:
        signature = clone(plain)
        field = DictionaryObject({
            NameObject("/FT"): NameObject("/Sig"),
            NameObject("/T"): TextStringObject("Fixture signature"),
        })
        if signed:
            field[NameObject("/V")] = signature._add_object(DictionaryObject({
                NameObject("/Type"): NameObject("/Sig"),
                NameObject("/Filter"): NameObject("/Adobe.PPKLite"),
                NameObject("/SubFilter"): NameObject("/adbe.pkcs7.detached"),
                NameObject("/ByteRange"): ArrayObject([NumberObject(n) for n in (0, 1, 2, 3)]),
                NameObject("/Contents"): ByteStringObject(b"synthetic signature marker"),
            }))
        signature._root_object[NameObject("/AcroForm")] = signature._add_object(
            DictionaryObject({NameObject("/Fields"): ArrayObject([signature._add_object(field)])})
        )
        save(signature, directory / f"{name}.pdf")

    save(PdfWriter(), directory / "no-pages.pdf")
    flags = clone(plain)
    flags._root_object[NameObject("/AcroForm")] = flags._add_object(DictionaryObject({
        NameObject("/Fields"): ArrayObject(), NameObject("/SigFlags"): NumberObject(1),
    }))
    save(flags, directory / "signature-flags.pdf")
    (directory / "invalid.pdf").write_bytes(b"not a PDF\n")
    cycle = clone(directory / "existing.pdf")
    outline = cycle._root_object["/Outlines"]
    first = outline.raw_get("/First")
    first.get_object()[NameObject("/Next")] = first
    save(cycle, directory / "cyclic-outline.pdf")
    print("Synthetic PDF fixtures generated: 11")


if __name__ == "__main__":
    main(Path(sys.argv[1]))
