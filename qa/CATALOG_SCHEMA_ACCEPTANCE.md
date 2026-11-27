# Catalog and schema contracts

This source audit targets the public catalog and preference metadata at
`nightly@9d1a3585f2b44b90e632cd3a2a7e46ef86ebf19d`. It does not establish
Android UI persistence or execution of the associated hooks.

The current catalog contains 97 unique keys. Its earlier 92-key version at
`17c16d0fea52aab90a45e9dbf6320f646adad1f1` has no removed keys; these five
entries account for the difference:

| Key | Introduction commit | Current schema type |
| --- | --- | --- |
| `disable_defemojis` | `7bcf74ef37e6dc25be8c1f2a2dbcbfa88007c444` | BOOLEAN |
| `custom_emojis` | `bb70f71cb983966a1b0f97b952ee3e3f57203282` | BOOLEAN |
| `native_floating_navigation` | `45fbed77465d05c62d11e3636522bea1a6262df9` | BOOLEAN |
| `remove_sticker_white_outline` | `7c06db57f5a112de9072c89ab7f49a7d652f738d` | BOOLEAN |
| `locked_chats_message_notifications` | `8521582c22859ad0cc787ff4b3c95fe67ecfd6f1` | BOOLEAN |

The stale 93-key assertion is replaced by the current 97-key inventory, with
explicit Boolean schema guards for the four newer feature controls listed in
the test. Unknown catalog growth still fails, as do duplicate
keys, an incorrect backup alias, a missing typed preference, or a writable
license/navigation key. Legacy backup destinations retain their original types.

`bubble_colors` is a Boolean UI alias for `bubble_color` in the current
`StyleBooleanAliasPolicy`; it is no longer an unchanged non-Boolean key.
The JVM alias fixture checks that mapping and canonical idempotence while
retaining isolation checks for unrelated keys.

The CSS editor writes `css_theme`. The current CSS hook reads `CSS_KEY` through
`BaseFeature.getStringPref`, choosing the legacy `custom_css` only when the
canonical preference is absent. An explicitly empty canonical value must keep
precedence. The source guard follows that current read path; the other typed
editor and failed-save controls remain intact.
