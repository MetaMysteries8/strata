# Preview development signing

This preview uses the publicly published AOSP development test key, not a private production credential. The original Strata 0.2.0 signing key was unavailable after a temporary workspace reset, so this preview uses application ID `dev.doxi.strata.preview` and installs alongside it. Keep the original app installed and move scans with Original dataset ZIP export/import.

The public material is from https://android.googlesource.com/platform/build/+/refs/heads/main/target/product/security/ . Its password is the public development value `android`. It is included so subsequent preview builds can retain the same development identity. Use your own retained release keystore for a production distribution.
