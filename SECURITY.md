# Security Policy

## Supported Versions

Security updates and patches are actively applied to the following versions of WAEX:

| Version | Supported          |
| ------- | ------------------ |
| 1.x.x   | :white_check_mark: |
| < 1.0   | :x:                |

---

## Reporting a Vulnerability

The WAEX team takes security seriously. If you discover a security vulnerability in this project, please report it responsibly so we can investigate and address it promptly.

### How to Report

1. **Do not create a public issue or discussion.**
2. Send an email to **[hello@mubashar.dev](mailto:hello@mubashar.dev)** with the subject line `[SECURITY] WAEX Vulnerability Report`.
3. In your report, please include:
   - A clear description of the vulnerability.
   - Step-by-step instructions or Proof of Concept (PoC) to reproduce the issue.
   - The affected component, class, or module.
   - Potential impact and severity assessment.
   - Any suggested mitigations or fixes, if known.

### Response Timeline

- **Initial Acknowledgment**: Within 48 hours of receiving your report.
- **Assessment & Validation**: We will investigate and provide confirmation within 5 business days.
- **Patch & Release**: A fix will be developed, tested, and released as quickly as feasible.
- **Public Disclosure**: Coordinated public advisory will be issued once the fix is released.

---

## Security Considerations

WAEX operates as an Android application with hook extensions (such as Xposed/LSPosed). As such:
- We prioritize user data integrity, privacy, and prevention of unintended privilege escalation.
- We do not store or transmit sensitive communication data externally without explicit user authorization.
- All dependencies are audited regularly for known CVEs.
