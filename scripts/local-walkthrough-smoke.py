#!/usr/bin/env python3
import json
import time
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

BASE = "http://localhost:8080"
TOKEN = "local-owner"
SAMPLE = Path(__file__).resolve().parents[1] / "samples/contracts/northstar-bakery-electricity.pdf"

def request(method, url, data=None, token=TOKEN, content_type=None):
    headers = {}
    if token:
        headers["Authorization"] = "Bearer " + token
    if content_type:
        headers["Content-Type"] = content_type
    with urlopen(Request(url, data=data, headers=headers, method=method), timeout=20) as response:
        return response.status, response.headers, response.read()

def json_request(method, url, value=None, token=TOKEN):
    data = None if value is None else json.dumps(value).encode("utf-8")
    status, headers, body = request(
        method, url, data, token, "application/json" if data is not None else None
    )
    return status, headers, json.loads(body)

def wait_for(check, description, timeout_seconds=90):
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        try:
            value = check()
            if value:
                return value
        except HTTPError as error:
            if error.code not in (404, 409):
                raise
        time.sleep(2)
    raise RuntimeError("Timed out waiting for " + description)

def main():
    if not SAMPLE.is_file():
        raise RuntimeError("sample PDF is missing; run scripts/create-sample-contract.py")

    pdf = SAMPLE.read_bytes()
    assert pdf.startswith(b"%PDF-")
    status, _, started = json_request("POST", BASE + "/v1/uploads", {
        "filename": SAMPLE.name,
        "contentType": "application/pdf",
        "size": len(pdf),
        "contractType": "ELECTRICITY",
    })
    assert status == 200
    upload_id = started["uploadId"]
    status, _, _ = request("PUT", started["upload"]["url"], pdf, token=None, content_type="application/pdf")
    assert status == 200
    print("PASS upload: fictional PDF stored in local object storage")

    status, _, _ = json_request("POST", BASE + f"/v1/local/uploads/{upload_id}/complete")
    assert status == 200
    wait_for(
        lambda: json_request("GET", BASE + f"/v1/uploads/{upload_id}")[2]["status"] == "COMPLETED",
        "upload ingestion",
    )
    review = wait_for(
        lambda: (lambda response: response if response["status"] == "DRAFT" else None)(
            json_request("GET", BASE + f"/v1/contracts/{upload_id}")[2]
        ),
        "draft processing",
    )
    provider = next(party for party in review["parties"] if party["role"] == "PROVIDER")
    assert provider["name"] == "Example Energy"
    print("PASS processing: local worker created a reviewable draft")

    edited_parties = [
        {**party, "name": "Example Energy Cooperative"} if party["role"] == "PROVIDER" else party
        for party in review["parties"]
    ]
    status, _, edited = json_request("PUT", BASE + f"/v1/contracts/{upload_id}", {
        "expectedVersion": review["version"],
        "parties": edited_parties,
        "terms": review["terms"],
    })
    assert status == 200 and edited["status"] == "DRAFT"
    saved = json_request("GET", BASE + f"/v1/contracts/{upload_id}")[2]
    provider = next(party for party in saved["parties"] if party["role"] == "PROVIDER")
    assert provider["name"] == "Example Energy Cooperative"
    print("PASS review/edit: changed draft persisted before approval")

    status, _, approved = json_request("PATCH", BASE + f"/v1/contracts/{upload_id}", {
        "expectedVersion": edited["version"],
    })
    assert status == 200 and approved["status"] == "ACTIVE"
    print("PASS approve: explicit approval changed contract state")

    status, headers, original = json_request(
        "GET", BASE + f"/v1/documents/{upload_id}/original"
    )
    assert status == 200
    assert "no-store" in headers.get("Cache-Control", "").lower()
    status, _, downloaded = request("GET", original["url"], token=None)
    assert status == 200 and downloaded.startswith(b"%PDF-")
    print("PASS view original: authenticated access returned the fictional PDF with no-store")
    print("PASS walkthrough complete; run docker compose down -v to remove demo state")

if __name__ == "__main__":
    main()
