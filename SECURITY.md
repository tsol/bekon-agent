# Security

## Reporting a vulnerability

Please report vulnerabilities privately to **tsolspb@gmail.com**. Do not open public issues for security problems.

## Secrets

No signing keys, passwords or API tokens are stored in this repository. Release signing reads
`BEKON_UPLOAD_*` values from environment variables or a gitignored `local.properties`.

## Incident 2026-10-08

On 2026-10-08 an unauthorized commit added a GitHub Actions workflow to the private parent
repository this project was extracted from. The workflow searched the code for API-key patterns and sent
matches to an external server. The repository contained no secrets, so **no secrets were leaked**.
The workflow was removed, credentials were rotated, and this repository was published with
read-only CI permissions and CODEOWNERS review for `.github/`. That workflow is not part of this
repository's history.
