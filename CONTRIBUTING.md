# Contributing to WAEX

Thank you for your interest in contributing to **WAEX**! We welcome contributions from developers, designers, translators, and testers to help make this project even better.

Please take a moment to review this document before submitting your contribution.

---

## Table of Contents
- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [Development Setup](#development-setup)
- [How to Contribute](#how-to-contribute)
  - [Reporting Issues](#reporting-issues)
  - [Suggesting Features](#suggesting-features)
  - [Pull Requests](#pull-requests)
- [Architecture & Code Guidelines](#architecture--code-guidelines)
- [Commit Message Conventions](#commit-message-conventions)
- [Security Disclosures](#security-disclosures)

---

## Code of Conduct

All contributors are expected to adhere to our [Code of Conduct](CODE_OF_CONDUCT.md). Please treat all members of the community with respect and empathy.

---

## Getting Started

1. **Fork the repository** on GitHub.
2. **Clone your fork** locally:
   ```bash
   git clone https://github.com/<your-username>/WAEX.git
   cd WAEX
   ```
3. **Create a new topic branch**:
   ```bash
   git checkout -b feat/my-new-feature
   ```

---

## Development Setup

- **IDE**: Android Studio Ladybug | 2024.2.1 or newer (recommended).
- **JDK**: Java 17 or higher.
- **Android SDK**: API 34+ (compileSdk 34 / targetSdk 34).
- **Kotlin**: 2.0+ with Jetpack Compose support.

### Building the Project
Run the following Gradle command to verify your setup:
```bash
./gradlew assembleDebug
```

### Running Tests
Execute unit and instrumentation tests using:
```bash
./gradlew test
```

---

## How to Contribute

### Reporting Issues
- Use the GitHub Issues tracker.
- Check existing issues before opening a new one to avoid duplicates.
- Include detailed reproduction steps, target Android version, WhatsApp version, root/hook framework details (LSPosed, KernelSU, APatch), and relevant logcat output.

### Suggesting Features
- Open an issue describing the proposed feature, the problem it solves, and why it fits within WAEX.
- Wait for feedback from maintainers before starting implementation on major features.

### Pull Requests
1. Ensure your branch is rebased on the latest `main` branch.
2. Keep PRs focused on a single change, fix, or feature.
3. Write clean, readable code with descriptive comments where necessary.
4. Ensure all unit tests pass and code compiles without warnings.
5. Submit your PR with a clear summary of changes and reference any related issue numbers.

---

## Architecture & Code Guidelines

WAEX follows a modular Clean Architecture pattern:
- **`app`**: Application entry point, navigation graphs, and DI binding.
- **`core` / `core-ui` / `core-network` / `core-database`**: Shared domain primitives, base classes, dispatchers, design tokens, and components.
- **`feature-*`**: Independent UI and presentation feature modules (`feature-home`, `feature-settings`, `feature-tools`, `feature-auth`, etc.).
- **`plugin-api` / `plugin-host`**: Extensible plugin hooks and runtime manager.

### Key Rules
- Maintain unidirectional data flow (UDF) using ViewModels and Kotlin StateFlow.
- UI components must be built using Jetpack Compose with Material 3 theming.
- Follow Kotlin standard coding style and naming conventions.

---

## Commit Message Conventions

We follow [Conventional Commits](https://www.conventionalcommits.org/):

- `feat:` A new feature or enhancement.
- `fix:` A bug fix.
- `refactor:` Code restructuring without changing external behavior.
- `chore:` Maintenance tasks, dependency bumps, or build changes.
- `docs:` Documentation improvements.
- `test:` Adding or updating tests.

**Example**:
```
feat: add call recording export and sharing support
```

---

## Security Disclosures

If you discover a security vulnerability, please do **NOT** open a public issue. Instead, refer to our [Security Policy](SECURITY.md) and report it confidentially.
