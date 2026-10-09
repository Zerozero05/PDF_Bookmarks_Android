"""Independent pypdf verification of files written by the production Android AAR."""
import hashlib
import json
from pathlib import Path
import sys
from pypdf import PdfReader


def digest(path, limit=None):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        remaining = limit
        while remaining is None or remaining > 0:
            chunk = stream.read(65536 if remaining is None else min(65536, remaining))
            if not chunk:
                break
            result.update(chunk)
            if remaining is not None:
                remaining -= len(chunk)
    assert remaining in (None, 0), "truncated PDF prefix"
    return result.hexdigest()


def flatten(reader, nodes, level=1):
    rows = []
    for node in nodes:
        if isinstance(node, list):
            rows.extend(flatten(reader, node, level + 1))
        else:
            page = reader.get_destination_page_number(node)
            rows.append({"level": level, "title": str(node.title), "pdfPage": page + 1})
    return rows


def page_details(page):
    contents = page.get_contents()
    return (
        page.indirect_reference.idnum, page.indirect_reference.generation,
        tuple(page.mediabox), tuple(page.cropbox), page.get("/Rotate", 0),
        b"" if contents is None else contents.get_data(), page.extract_text(),
    )


def verify(manifest_path, report_path):
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    records = []
    for item in manifest["outputs"]:
        source, output = Path(item["source"]), Path(item["output"])
        source_sha = digest(source)
        assert source_sha == digest(output, source.stat().st_size), "original prefix changed"
        before, after = PdfReader(source), PdfReader(output)
        assert not after.is_encrypted
        assert len(before.pages) == len(after.pages), "page count changed"
        assert flatten(after, after.outline) == item["expected"], "outline tree/Unicode/page mismatch"
        assert dict(before.metadata or {}) == dict(after.metadata or {}), "metadata changed"
        for first, second in zip(before.pages, after.pages):
            assert page_details(first) == page_details(second), "page object/box/content/text changed"
        assert source_sha == digest(source), "original input changed"
        record = {
            "source": str(source), "output": str(output), "inputBytes": source.stat().st_size,
            "outputBytes": output.stat().st_size, "pages": len(after.pages),
            "bookmarks": len(item["expected"]), "inputSha256": source_sha,
            "originalPrefixSha256": digest(output, source.stat().st_size),
            "outputSha256": digest(output), "originalUnchanged": True,
            "allPageObjectsBoxesContentAndTextUnchanged": True,
            "metadataUnchanged": True, "outlineMatches": True,
        }
        records.append(record)
        print(f"PASS pypdf: {output.name}: {record['pages']} pages, {record['bookmarks']} bookmarks")
    report = {"hostTests": manifest["hostTests"], "independentVerifier": "pypdf",
              "notDeviceTest": True, "outputs": records}
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    verify(Path(sys.argv[1]), Path(sys.argv[2]))
