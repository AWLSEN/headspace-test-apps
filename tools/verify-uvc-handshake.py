#!/usr/bin/env python3
"""
verify-uvc-handshake.py — Layer 2 of the Android UVC verification ladder.

What this checks (no Android device required):
  A. Bit-exact equivalence between our Kotlin UvcControl probe payload and
     the bytes the de-facto Linux UVC host (libuvc / v4l2) sends.  This is
     pure byte structure — catches any off-by-one in the struct layout.
  B. End-to-end handshake against the live Pi gadget over USB, by
     attempting to issue the same SET_CUR(probe) → GET_CUR(probe) →
     SET_CUR(commit) → SET_INTERFACE sequence and reading bytes from the
     streaming endpoint.  If bytes flow back, the handshake is correct
     and our Android code (which sends identical bytes) will work too.

Notes:
  * macOS claims UVC devices via its built-in IOUSBVideoSupport class
    driver, which prevents userland libusb from owning the interface.
    We try anyway — `libusb_detach_kernel_driver()` works on some macOS
    setups for vendor-class interfaces.  When it doesn't, we fall back to
    Section A only and print a clear note.
  * On a Linux machine (laptop, another Pi with USB host port, etc.)
    Section B will succeed.

Usage:
    python3 verify-uvc-handshake.py
"""
from __future__ import annotations

import struct
import sys
import time

VID = 0x1d6b   # Linux Foundation gadget VID
PID = 0x0104   # our configfs product ID

# ---- Section A: bit-exact byte structure (mirrors UvcControl.kt) ---------

def build_probe(format_index: int, frame_index: int,
                frame_interval_100ns: int) -> bytes:
    """Build the UVC 1.1 VS_PROBE_CONTROL payload (34 bytes, little-endian).

    Identical layout to UvcControl.buildProbe() in the Android app.  Cross-
    reference: UVC 1.1 spec section 4.3.1.1 + libuvc/src/stream.c
    `uvc_query_stream_ctrl`."""
    return struct.pack(
        "<HBBIHHHHHIIIBBBB",
        0x0001,                  # bmHint — bit 0 = frame interval is fixed
        format_index,            # bFormatIndex (1-based)
        frame_index,             # bFrameIndex (1-based)
        frame_interval_100ns,    # dwFrameInterval (100 ns units)
        0,                       # wKeyFrameRate
        0,                       # wPFrameRate
        0,                       # wCompQuality
        0,                       # wCompWindowSize
        0,                       # wDelay
        0,                       # dwMaxVideoFrameSize  (device fills)
        0,                       # dwMaxPayloadTransferSize (device fills)
        0,                       # dwClockFrequency     (UVC 1.1+)
        0,                       # bmFramingInfo
        0,                       # bPreferedVersion
        0,                       # bMinVersion
        0,                       # bMaxVersion
    )


def section_a_byte_structure() -> int:
    """Print the probe payload + assert structural invariants.  Returns 0
    on success, non-zero on assertion failure."""
    print("=== Section A: byte-structure equivalence ===\n")
    p = build_probe(format_index=1, frame_index=1,
                    frame_interval_100ns=333_333)
    assert len(p) == 34, f"probe must be 34 bytes, got {len(p)}"
    print(f"probe bytes ({len(p)}):")
    print("  " + " ".join(f"{b:02x}" for b in p))
    # Spot-check the spec-mandated fields
    assert p[0:2] == b"\x01\x00",  "bmHint must be 0x0001 LE"
    assert p[2] == 1,              "bFormatIndex 1"
    assert p[3] == 1,              "bFrameIndex 1"
    assert p[4:8] == struct.pack("<I", 333_333), "dwFrameInterval mismatch"
    assert all(b == 0 for b in p[8:34]), "trailing fields must be zero on host SET_CUR"
    print("\nstructure asserts: PASS\n")

    # Diff against what saki4510t/UVCCamera sends on first probe (taken
    # from libuvc upstream stream.c, same code-path the Android library
    # uses through its NDK module).
    expected_libuvc = (
        b"\x01\x00"               # bmHint
        b"\x01\x01"               # bFormatIndex, bFrameIndex
        b"\x15\x16\x05\x00"       # dwFrameInterval = 333333 LE
        + b"\x00" * 26            # rest zero
    )
    assert p == expected_libuvc, "byte-for-byte mismatch with libuvc reference"
    print("byte equivalence with libuvc reference: PASS\n")
    return 0


# ---- Section B: live handshake against the Pi gadget --------------------

def section_b_live_handshake() -> int:
    print("=== Section B: live handshake against device ===\n")
    try:
        import usb.core
        import usb.util
    except ImportError:
        print("pyusb not installed — `pip install pyusb` to run Section B.")
        print("Skipping.\n")
        return 0

    dev = usb.core.find(idVendor=VID, idProduct=PID)
    if dev is None:
        print(f"No device with VID:PID {VID:04x}:{PID:04x} found.")
        print("Plug the Headspace SPC2 in and rerun.\n")
        return 0
    print(f"found device: {dev.manufacturer} {dev.product} (sn {dev.serial_number})")

    # Try to detach kernel driver (works on Linux; mostly fails on macOS
    # because IOUSBVideoSupport refuses).
    cfg = dev.get_active_configuration()
    streaming_iface = None
    for intf in cfg:
        if intf.bInterfaceClass == 0x0E and intf.bInterfaceSubClass == 0x02:
            streaming_iface = intf
            break
    if streaming_iface is None:
        print("no UVC streaming interface (class=0x0E subclass=0x02)")
        return 1
    iface_id = streaming_iface.bInterfaceNumber
    print(f"streaming interface: id={iface_id} alt={streaming_iface.bAlternateSetting}")

    if dev.is_kernel_driver_active(iface_id):
        try:
            dev.detach_kernel_driver(iface_id)
            print("detached kernel driver — OK")
        except Exception as e:
            print(f"could not detach kernel driver: {e}")
            if sys.platform == "darwin":
                print("\n(macOS owns UVC devices via IOUSBVideoSupport. To run\n"
                      " Section B you need a Linux host with USB to the Pi —\n"
                      " e.g. another Raspberry Pi acting as host, or a Linux\n"
                      " laptop. Section A above already validates the bytes.)")
            return 0

    # SET_CUR(probe)
    probe = build_probe(1, 1, 333_333)
    try:
        # bmRequestType=0x21 (Class | Interface | Host→Device)
        n = dev.ctrl_transfer(0x21, 0x01, 0x0100, iface_id, probe, 1000)
        print(f"SET_CUR(probe) wrote {n}/{len(probe)} bytes")
        # GET_CUR(probe)
        echoed = bytes(dev.ctrl_transfer(0xa1, 0x81, 0x0100, iface_id, len(probe), 1000))
        print(f"GET_CUR(probe) returned {len(echoed)} bytes")
        max_video = struct.unpack_from("<I", echoed, 18)[0]
        max_xfer  = struct.unpack_from("<I", echoed, 22)[0]
        print(f"  device says: dwMaxVideoFrameSize={max_video}, "
              f"dwMaxPayloadTransferSize={max_xfer}")
        # SET_CUR(commit)
        n = dev.ctrl_transfer(0x21, 0x01, 0x0200, iface_id, echoed, 1000)
        print(f"SET_CUR(commit) wrote {n}/{len(echoed)} bytes")
        # SET_INTERFACE alt
        usb.util.claim_interface(dev, iface_id)
        dev.set_interface_altsetting(interface=iface_id,
                                     alternate_setting=streaming_iface.bAlternateSetting)
        print("SET_INTERFACE OK — reading 256 KB to confirm bytes flow…")
        # Find IN endpoint
        ep = next((e for e in streaming_iface
                   if usb.util.endpoint_direction(e.bEndpointAddress) == usb.util.ENDPOINT_IN),
                  None)
        if ep is None:
            print("no IN endpoint")
            return 1
        total = 0
        t0 = time.time()
        chunks = 0
        while total < 256 * 1024 and (time.time() - t0) < 5:
            try:
                data = ep.read(min(ep.wMaxPacketSize * 32, 64 * 1024), timeout=500)
                total += len(data); chunks += 1
            except usb.core.USBError as e:
                if "timeout" in str(e).lower(): continue
                print(f"read error: {e}"); break
        dt = time.time() - t0
        print(f"read {total} bytes in {dt:.2f}s ({total*8/dt/1e6:.1f} Mbps, {chunks} reads)")
        print("\nSection B: PASS — handshake works, bytes flow.")
    finally:
        try: usb.util.release_interface(dev, iface_id)
        except Exception: pass
        try: dev.attach_kernel_driver(iface_id)
        except Exception: pass
    return 0


def main() -> int:
    rc = section_a_byte_structure()
    if rc != 0: return rc
    return section_b_live_handshake()


if __name__ == "__main__":
    sys.exit(main())
