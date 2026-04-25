# Headspace Test Apps

Plug-and-play recorders for the Mercury Labs Headspace SPC2.

## What this is

Two test apps that turn on the moment the Headspace SPC2 is plugged in,
record until it's unplugged, and drop a self-contained session folder
on disk:

```
~/Headspace/recordings/spc2_2026-04-25_15-30-22/
  ├── video.mp4       Awign-spec H.264, 1080p30
  ├── imu.imu         CSV: boottime_ns,ax,ay,az,gx,gy,gz,mx,my,mz,mag_valid
  └── meta.json       sync stamps + session info
```

## mac/ — macOS plug-and-play recorder

Single Swift CLI binary. AVFoundation-driven. No GUI to click:

* Watches the system for a UVC device named `Headspace SPC2 H264`.
* On attach → starts an `AVCaptureMovieFileOutput` recording to `video.mp4`.
* On detach → finalizes the MP4, pulls the matching IMU window from the
  Pi over SSH (since Apple's UVC class driver decodes H.264 → 2vuy in
  the kernel and strips our SEI NALs before any app sees them), and
  writes `meta.json` with the boottime sync stamps.

Run:

```bash
cd mac
swift build -c release
./.build/release/headspace-recorder
# Then plug in the Headspace SPC2.
```

Env knobs: `HEADSPACE_DEVICE`, `HEADSPACE_OUT`, `HEADSPACE_PI`, `HEADSPACE_PI_PW`.

## android/ — Android USB-OTG plug-and-play recorder

Kotlin app using the [`UVCCamera`](https://github.com/saki4510t/UVCCamera)
library, which talks to UVC devices over libusb and **preserves the raw
H.264 bytes including SEI**. So on Android the app gets video AND IMU
straight from one stream — no SSH companion needed.

Status: **build-only**. No physical Android device available for testing
on this workstation; correctness verified via:
* Unit tests on the SEI parser against `fixtures/sei-dump.h264`.
* Emulator boot + UI rendering smoke test.

Production verification requires a real Android phone with USB-OTG. Side-
load the APK and the device will appear when you connect the SPC2 via an
OTG adapter.

## Time sync

Both video and IMU originate on the Pi using `CLOCK_BOOTTIME`:

* libcamera reports `SensorTimestamp` per frame (boottime).
* `imu_publisher` reads the GPIO IRQ rising-edge timestamp from the
  kernel and converts it to boottime.

Mac path: video MP4 PTS is host-side wall clock; we capture the Pi's
boottime once at recording start (one SSH RTT, ~5 ms) and store it in
`meta.json`. Aligning a video frame at PTS `t` to an IMU sample is then:

```
imu_boottime = start_pi_boottime_ns + t  (± 5 ms)
```

Android path: video PTS and IMU timestamps share the boottime clock
natively — sub-1 ms.
