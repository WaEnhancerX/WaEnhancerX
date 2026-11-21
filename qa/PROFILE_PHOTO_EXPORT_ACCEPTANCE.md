# Profile photo export admission and lifetime

Run `python3 qa/test_profile_photo_save.py --out /tmp/waex-profile-export`.
The menu checks the live preference and owner Activity, then admits at most one
pending save before copying the displayed bitmap. Capture preserves the existing
40-megapixel ceiling for bitmap-backed images as well as rendered drawables.
Drawable bounds are restored in finally and failed captures release their bitmap.

The worker uses application storage context and a weak Activity reference. A
successful result requires encoding, flush, stream close and exactly one pending
MediaStore row update where scoped storage applies. Failure removes only its
owned row, releases the bitmap and allows the next request. Result presentation
checks the Activity both before scheduling and inside the queued UI callback.

The complete hook passes 23 local scenarios / 65 checks. The same fixture executes
all scenarios on V38 and detects 17 assertion failures. With the first worker
blocked and another 1,000 clicks, the candidate makes one bitmap copy and one row,
compared with 1,001 of each in V38. This measures admission/allocation behavior in
the controlled fixture, not phone RAM usage, FPS or end-to-end save latency.

Android/Xposed/DexKit services are explicit doubles; file IO and the executor are
real. Device acceptance must verify the actual viewer binding, storage permission,
gallery output and Activity lifecycle on the target WhatsApp/Android versions.
