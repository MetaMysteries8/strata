# Strata deployment audit — 0.2.6

## Conclusion

Confirmed a deployment configuration defect. The shell running modal deploy received the generated resource names, but neither container image preserved them. A fresh remote import could select the generic strata-jobs Dict instead of the installation-specific job table. This is a real namespace/isolation bug, but the screenshots and local reproduction do not establish that it caused the observed pending call.

## Evidence

- deploy_endpoint.py sets STRATA_MODAL_APP, STRATA_MODAL_SECRET, STRATA_MODAL_VOLUME, and STRATA_MODAL_DICT in the local deploy subprocess environment.
- modal_app.py originally read those names in module scope without Image.env or function env configuration.
- Two isolated local imports using the actual pinned Modal 1.6.4 SDK produced strata-audit-custom / strata-audit-jobs with the deployment variables, then strata-gpu / strata-jobs without them. These are synthetic names; no account credentials were used.
- Modal's SDK rehydrates explicitly declared function dependencies, including mounted Volumes. It does not list the module-level Dict among those dependencies. Therefore the Dict fallback is the strongest concrete finding; the Volume repr fallback alone is not proof that the mounted volume ID was incorrect.
- The CPU health endpoint only establishes API availability. It does not validate a running GPU engine.

## Fix

Both images now preserve an explicit four-name runtime_env mapping using Image.env. No account tokens, endpoint access keys, proxy tokens, or entire local environment are copied. Regression tests capture the actual SDK Image.env calls and simulate a fresh remote import with only the configured image variables, confirming matching App/Dict/Volume names.

Added startup_timeout=300 separately from the existing 7200-second reconstruction execution limit. The SDK otherwise uses the execution timeout for startup too. This bounds initialization, not resource scheduling time.

The L4 choice, one-worker cap, 8-physical-core CPU request, 32 GiB memory request, model presets and validation are unchanged. No GPU job, deployment, cancellation or account mutation was executed during this audit.

## Other possibilities, not diagnoses

- A single busy worker can queue another job because max_containers is 1.
- Placement needs the requested CPU and RAM as well as an L4. The relatively large minimum request may constrain placement; no live fleet data was available to confirm this.
- Image pulling/imports or container startup failures can delay readiness. The exact registry image was not executed here.
- Modal documents an intermittent L4 CUDA driver-initialization issue. That requires a matching CUDA error before attributing this incident to it. No speculative CUDA workaround or GPU fallback was added.

## Update and existing records

The corrected table namespace may differ from where old containers wrote job/call/cancellation records. There is no automatic migration or deletion of that default table, active calls, or volume files. Do not delete the deployment or its volumes as an update step.

If an old scan is still running, decide whether to let it finish or cancel that specific call in Modal before deploying the correction. Afterward, use a fresh reconstruction from the original phone capture. Resume/download by an old job ID may no longer find a record under the corrected table. Keep original datasets. Runtime code and saved deployment identity are updated in place; Ubuntu and credentials need no reinstall.

The existing Preview 0.2.5 app can use the corrected external runtime ZIP. The 0.2.6 APK bundles that same fixed ZIP for the Save runtime ZIP button; it uses the existing Preview package/certificate.

## Verification and limitations

Before the patch, two new valid regressions failed and all 47 existing Python tests passed. After the patch, all 49 Python tests pass. Final packaging/build results are in VERIFICATION.md.

No authenticated live deployment state, provider scheduler, GPU memory use, engine toolchain, or user scan was tested. This audit cannot prove why that particular call remained pending. The fix addresses the confirmed configuration defect and prevents initialization from silently inheriting a two-hour allowance.

## Primary references

- Modal: [Image.env and image configuration](https://modal.com/docs/guide/images).
- Modal: [module globals are loaded locally and remotely](https://modal.com/docs/guide/global-variables).
- Modal: [execution versus startup timeouts](https://modal.com/docs/guide/timeouts).
- Modal: [CPU and memory requests](https://modal.com/docs/guide/resources).
- Modal: [L4 CUDA initialization issue](https://modal.com/docs/guide/troubleshooting).
- Local pinned SDK source: modal/_functions.py (_deps and startup timeout construction), modal/_container_entrypoint.py (dependency hydration), modal/app.py (_init_container).
