@/Users/mubasharhussain/.codex/RTK.md

## Release creation

When asked to create a release:

1. Treat `VERSION_NAME` and `VERSION_CODE` in `gradle.properties` as the version source of truth.
2. Use the complete contents of `changelog.txt` as the release notes without inventing or omitting entries.
3. Require a clean, committed release source and push that commit to the upstream repository before publishing releases.
4. Build and verify both `assembleDebug` and `assembleRelease`. Attach the generated debug and release APKs to both releases.
5. For `WaEnhancerX/WaEnhancerX`, use `VERSION_NAME` as the tag and `WaEnhancerX v<VERSION_NAME>` as the release title.
6. For `xposed-Modules-Repo/com.waenhancer`, preserve the repository tag convention `<VERSION_CODE>-<VERSION_NAME>` and use the same release title and notes.
7. Do not overwrite an existing tag or release silently. Stop and report the conflict unless the user explicitly requests replacement.
8. After publishing, verify both releases, their tags, and both APK assets; report direct release links and APK SHA-256 checksums.
