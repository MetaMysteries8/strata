# Verification: Strata Preview 0.2.3

## Reproduced failure

The actual 0.2.2 PhoneModal.setupCommand extracted from its Kotlin string fails `/bin/bash -n` with status 2: unexpected EOF while looking for a matching double quote. This matches the user screenshots ending at setup.sh and the `>` prompt. The first setup command has not executed while Bash waits for this quote.

## Correction

PhoneModal.setupCommand now joins ordinary Kotlin strings with explicit escaped shell double quotes. The complete real generated value is checked by a JUnit regression using `bash -n`; it does not execute installers or touch runtime state. This host-specific test skips when /bin/bash is unavailable.

## Completed checks

- PASS: `:app:assembleDebug :app:testDebugUnitTest` completed offline with JDK 21 / SDK 35. All 28 Android tests passed, with zero failures/errors/skips, including the generated-command Bash syntax regression.
- PASS: APK v2 signature and ZIP alignment. Package dev.doxi.strata.preview, versionName 0.2.3, versionCode 6. The SHA-256 signing certificate matches Preview 0.2.1/0.2.2: a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc.
- PASS: bundled runtime is byte-for-byte unchanged from the released 0.2.2 runtime ZIP. The app footer reports 0.2.3.
- Server and Node suites were not rerun: their implementation is unchanged. Their previous 30 Python and 7 Node passes are recorded in VERIFICATION-0.2.2.md.

## Limits

Runtime remains version 0.2.2 and is unchanged; its previous verification is in VERIFICATION-0.2.2.md. This correction needs only an APK update, then cancellation/retry of the unfinished pasted command. Physical Android/Termux installation, authenticated Modal login/deployment, and GPU reconstruction were not exercised. The original S25 native NaN issue is not proven fixed.
