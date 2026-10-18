#!/usr/bin/env python3
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SAFE_LOCAL_ACCOUNT = "000000000000"
GENERATED_PARTS = {".git", ".terraform", "target", "node_modules", "dist", "coverage"}
FORBIDDEN_PATH_PARTS = (
    "docs/history/",
    "docs/book/",
    "evidence/",
    "screenshots/",
    "chat-exports/",
    "terraform/environments/dev/",
    "terraform/environments/prod/",
)
FORBIDDEN_TEXT = {
    "private-tracking-link": re.compile(r"github\.com/[^/\s)]+/[^/\s)]+/(?:issues|pull)/\d+", re.I),
    "cloud-account-id": re.compile(r"(?<![0-9a-fA-F-])(?!000000000000)(\d{12})(?![0-9a-fA-F-])"),
    "signed-object-url": re.compile(r"X-Amz-Signature\s*=", re.I),
    "aws-access-key": re.compile(r"AKIA[A-Z0-9]{16}"),
    "private-key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    "private-tracker-number": re.compile(r"(?<![A-Za-z0-9])#\s*\d+\b"),
    "private-tracker-reference": re.compile(
        r"\b(?:issue|PR|pull request|decision)\s*#\s*\d+|"
        r"\]\([^)]*\)\s*#\s*\d+(?:\s*/\s*#?\s*\d+)*|"
        r"(?<![A-Za-z0-9])#\s*\d+\s*/\s*#?\s*\d+",
        re.I,
    ),
    "owner-verification-history": re.compile(
        r"owner[- ](?:verified|approved|accepted)|(?:verified|approved|accepted) by (?:the )?owner|owner review record",
        re.I,
    ),
    "excluded-deployment-path": re.compile(r"(?:deploy-development|promote-production)\.yml|infrastructure/terraform/environments/(?:dev|prod)/", re.I),
    "private-history-placeholder": re.compile(r"project tracking reference omitted|related private project record|project record|implementation addendum|decision register", re.I),
    "excluded-deployment-script": re.compile(r"deploy-ecs-release|production-promotion-policy|deploy-development|promote-production", re.I),
    "dangling-sanitization-substitution": re.compile(r"(?:\b(?:for|under)\s*\.|,\s*,|,\s*\.|\band\s*\.)" , re.I),
    "local-guide-claimed-as-cloud-recovery": re.compile(r"(?:restore|recovery|reconciliation|cloud|AWS|CloudWatch|operator|alarm|runbook).{0,100}LOCAL-OPERATIONS\.md|LOCAL-OPERATIONS\.md.{0,100}(?:restore|recovery|cloud|AWS|CloudWatch|operator|alarm|runbook)", re.I),
}

def main():
    tracker_pattern = FORBIDDEN_TEXT["private-tracker-reference"]
    tracker_examples = (
        "decision #123",
        "[reader note](docs/DECISIONS.md) #123",
        "#123/#456",
    )
    standalone_tracker_pattern = FORBIDDEN_TEXT["private-tracker-number"]
    if not all(tracker_pattern.search(sample) for sample in tracker_examples) or not standalone_tracker_pattern.search("owner verification #123"):

        print("FAIL private-tracker-pattern-self-check")
        return 1

    findings = []
    for markdown in ROOT.rglob("*.md"):
        if markdown == ROOT / "docs/EXTRACTION-MANIFEST.md" or any(part in GENERATED_PARTS for part in markdown.parts):
            continue
        for token in re.findall(r"`([^`]+)`", markdown.read_text(encoding="utf-8")):
            if token.startswith(".github/") and not (ROOT / token).exists():
                findings.append((markdown.relative_to(ROOT).as_posix(), "missing-github-evidence-path"))
    for path in sorted(ROOT.rglob("*")):
        if not path.is_file() or any(part in GENERATED_PARTS for part in path.parts) or path == Path(__file__):
            continue
        relative = path.relative_to(ROOT).as_posix()
        if any(part in relative for part in FORBIDDEN_PATH_PARTS):
            findings.append((relative, "excluded-content-path"))
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        for category, pattern in FORBIDDEN_TEXT.items():
            if category == "private-tracker-number":
                if relative == "docs/EXTRACTION-MANIFEST.md" or path.suffix != ".md":
                    continue
                if any(
                    pattern.search(line) and not re.match(r"^\s*#{1,6}\s+\d+\b", line)
                    for line in text.splitlines()
                ):
                    findings.append((relative, category))
                continue
            if category in {"owner-verification-history", "dangling-sanitization-substitution"} and path.suffix != ".md":
                continue
            if relative == "docs/EXTRACTION-MANIFEST.md" and category in {
                "excluded-deployment-path",
                "private-history-placeholder",
                "excluded-deployment-script",
            }:
                continue  # The private manifest records excluded paths and categories by design.
            if pattern.search(text):
                findings.append((relative, category))
        for email in re.findall(r"[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}", text, re.I):
            domain = email.rsplit("@", 1)[1].lower()
            if not (domain.endswith(".test") or domain.endswith(".example") or domain.endswith(".invalid")):
                findings.append((relative, "non-fictional-email"))
                break

    terraform_variables = (ROOT / "infrastructure/terraform/modules/platform/variables.tf").read_text(encoding="utf-8")
    identity_block = re.search(r'variable "github_repository_identity" \{(.*?)(?=\nvariable \"|\Z)', terraform_variables, re.S)
    if not identity_block or re.search(r"(?m)^\s*default\s*=", identity_block.group(1)):
        findings.append(("infrastructure/terraform/modules/platform/variables.tf", "author-specific-or-default-github-identity"))

    fixture = (ROOT / "frontend/src/App.test.tsx").read_text(encoding="utf-8")
    fixture_lines = "\n".join(fixture.splitlines()[422:427])
    fixture_names = re.findall(r"name:\s*['\"]([^'\"]+)['\"]", fixture_lines)
    if sorted(fixture_names) != ["Example Energy", "Northstar Bakery"]:
        findings.append(("frontend/src/App.test.tsx", "nonfictional-or-unexpected-contract-party-fixture"))

    workflow = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
    if re.search(r"terraform\s+(?:plan|apply|destroy)", workflow, re.I):
        findings.append((".github/workflows/ci.yml", "cloud-mutation-command"))
    if re.search(r"id-token:\s*write|configure-aws-credentials|aws-actions/", workflow, re.I):
        findings.append((".github/workflows/ci.yml", "cloud-credential-workflow"))

    if findings:
        for path, category in sorted(set(findings)):
            print(f"FAIL {category}: {path}")
        return 1
    print("PASS sanitization and local-only distribution checks")
    return 0

if __name__ == "__main__":
    sys.exit(main())
