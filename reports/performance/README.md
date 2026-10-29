# Phone performance measurements (#32)

No physical-phone results have been recorded here yet. Emulator timings are diagnostic only.

## Capture a baseline

Use the 4 GB target phone with synthetic benchmark images. Keep the dataset, model, thresholds,
power mode, charging state, and initial temperature consistent between baseline and candidate runs.
Record the source commit (`git rev-parse HEAD`), phone model, Android version, reported RAM,
and whether the installed app used CPU or GPU if observed. Do not infer accelerator use from
device capabilities alone; the batch export currently does not record the active delegate.

With the phone selected in adb (set `ANDROID_SERIAL` if multiple devices are connected):

```bash
adb shell getprop ro.product.manufacturer
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell cat /proc/meminfo

python -m ml.generate dataset --config configs/dataset.yaml
python -m validation.batch_subset --count 50 --out data/batch_input
scripts/batch_export.sh data/batch_input/images data/batch_output
python -m validation.performance data/batch_output/results.json \
  --device-kind phone --out reports/performance/baseline
```

Optionally pass `--ram-mib` using `MemTotal` from `/proc/meminfo` divided by 1024 (the reported
`kB` uses KiB). This is available system RAM, which can be below the advertised 4 GB.
It is distinct from per-process heap memory. Record advertised RAM separately in the run notes.
Use `--device-kind emulator` for an emulator; such runs never mark phone latency targets met.

Outputs:

- `summary.json`: device metadata, source-export SHA256, state counts, unclassified/unfinished
  images, latency comparisons, and the maximum reported stage-boundary heap sample.
- `stages.csv`: median, p90, maximum, valid sample count, and missing/invalid count per stage
  for all images, predicted non-documents, and flagged documents.

The percentile is linearly interpolated at rank `(n - 1) * 0.9`; missing stages are not zero.
The 150 ms and 1500 ms comparisons use total per-image time, including hashing and database
work, so the non-document comparison is more conservative than timing detector inference alone.
Both `REDACTED` and `NEEDS_CHECK` are flagged outcomes; fast processing does not establish
redaction safety. Evaluate accuracy and leaks using `validation.report pipeline` separately.
Incomplete/unclassified images prevent a measured cohort from being marked within target.
No image IDs, field values or OCR text are copied into the summary.

The existing exporter samples heap usage only at stage boundaries; these samples are not
true peak RSS. `hash_redact_save` is a residual, and nested stage timings can overlap. Do not
sum stages to infer wall-clock speed, or claim a bottleneck solely from that residual.

Run repeated trials and retain every trial separately, including the initial cold run.
Compare medians, p90s, failures, and memory on the same test inputs; do not select the fastest
trial. Report the number of images actually selected (the generated test split may have fewer
than the requested count).

## Checks that still require the phone

- Measure the elapsed first scan of 1,000 mixed photos in the actual app, starting from a fresh
  index. Record count, time, completion/failures, power conditions and peak process memory.
  The target is under five minutes. A sum of batch per-image times is not this measurement.
- On a clean install in airplane mode, record pass/fail and notes for setup, scan, redaction,
  vault, system delete Allow/Deny, eye toggle, restore, and export. Use synthetic photos only.
- Record memory with a device profiler or OS tooling during processing; retain the measurement
  method and distinguish it from the stage-boundary heap samples in the batch export.

## Before adding scan concurrency

`LiteRtDetector` shares interpreter buffers and is not thread-safe. Its CPU fallback already
uses four inference threads, and the current design holds one full-resolution bitmap at a time.
Benchmark decoding/preprocessing overlap with a bounded queue only after capturing this baseline.
Keep inference, OCR/recheck use of shared resources, and full-resolution redaction serialized
unless those components receive separate ownership and memory limits. Compare throughput,
p90 latency, memory, heat, cancellation behavior, and unchanged redaction/accuracy results on
the target phone before adopting concurrency. The report itself changes no scan scheduling.
