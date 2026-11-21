#!/usr/bin/env python3
"""Ensure restart prompts resolve the same resource titles as the settings UI."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LABELS = ROOT / "app/src/main/java/com/waenhancer/utils/PreferenceLabels.java"
PROMPT = ROOT / "app/src/main/java/com/waenhancer/xposed/core/RestartPromptController.java"
CATALOG = ROOT / "app/src/main/java/com/waenhancer/config/FeatureCatalog.kt"

labels = LABELS.read_text(encoding="utf-8")
prompt = PROMPT.read_text(encoding="utf-8")
catalog = CATALOG.read_text(encoding="utf-8")

contracts = {
    "labels use feature title resource convention":
        'TITLE_PREFIX = "feat_"' in labels and 'TITLE_SUFFIX = "_title"' in labels,
    "labels dynamically resolve generated resources":
        "R.string.class.getField(fieldName)" in labels,
    "sub-settings walk back to their parent feature":
        "candidate.lastIndexOf('_')" in labels and "candidate.substring(0, separator)" in labels,
    "legacy keys share catalog aliases":
        "PreferenceKeyAliases.canonical(normalized)" in labels
        and "PreferenceKeyAliases.canonical(it)" in catalog,
    "raw keys never use the old Setting prefix": '"Setting: %1$s"' not in labels,
    "duplicate parent feature labels are collapsed":
        "Set<String> resolved = new LinkedHashSet<>()" in prompt,
}

missing = [name for name, present in contracts.items() if not present]
if missing:
    raise SystemExit("Restart feature-label contract violations:\n- " + "\n- ".join(missing))

print(f"PASS: {len(contracts)} dynamic restart feature-label contracts are present")
