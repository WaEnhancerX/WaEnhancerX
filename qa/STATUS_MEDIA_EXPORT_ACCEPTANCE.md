# Status export publication and rollback

Run `python3 qa/test_status_media_export.py --out /tmp/waex-status-export`.
The new exporter inspects and copies one open source descriptor, applies the
existing 512 MiB media limit, and keeps the existing format-to-collection mapping.
On Android 10 and later, success requires a completed write and close plus exactly
one updated pending MediaStore row. Failure rolls back only the row created by
that invocation. Older Android versions use a private temporary file, a unique
destination name and a rename after complete output; indexing is asynchronous.

The local fixture exercises 30 cases and 125 checks, including write/flush/close
failures, missing streams, failed publication, partial-file cleanup, concurrent
legacy folder creation, format routing and repeated exports. The original export
body executes the same cases and fails 28 assertions. The adapter preserves that
body and translates its existing failure toast into the fixture's failure result;
it does not count missing APIs or compilation errors as regression detection.

This is complete exporter compilation with explicit Android service doubles and
real local file IO. It does not exercise a WhatsApp selector, Android storage
permissions, the actual MediaProvider, SELinux, LSPosed or a physical device.
Verify visible gallery output, collision-free repeated saves and rollback on the
intended Android version with WhatsApp and WhatsApp Business before device acceptance.
