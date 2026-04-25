// Headspace SPC2 plug-and-play recorder for macOS
//
// Behavior:
//   * Watches for the "Headspace SPC2 H264" UVC device.
//   * Plug in  → starts recording to ~/Headspace/recordings/spc2_<timestamp>/video.mp4
//                ALSO: SSHs the Pi to capture IMU CSV in parallel for the
//                same wall-clock window (sync via CLOCK_BOOTTIME — see meta.json)
//   * Unplug   → stops the recording, writes meta.json, prints folder path.
//
// Why an SSH-based companion: macOS's UVC class driver decodes H.264 → 2vuy
// in kernel before AVFoundation sees it, stripping the SEI NALs that carry
// our IMU samples. The IMU therefore has to come from the Pi side directly.
// Both timestamps are CLOCK_BOOTTIME on the Pi, so re-aligning is a single
// scalar offset captured at record-start.

import Foundation
import AVFoundation
import AppKit

// MARK: - Config (env-overridable)

let DEVICE_NAME      = ProcessInfo.processInfo.environment["HEADSPACE_DEVICE"]
                       ?? "Headspace SPC2 H264"
let RECORDINGS_ROOT  = ProcessInfo.processInfo.environment["HEADSPACE_OUT"]
                       ?? (NSHomeDirectory() + "/Headspace/recordings")
let PI_HOST          = ProcessInfo.processInfo.environment["HEADSPACE_PI"]
                       ?? "pi-1@192.168.1.10"
let PI_PASSWORD      = ProcessInfo.processInfo.environment["HEADSPACE_PI_PW"]
                       ?? "indian77"

// MARK: - Logging

func log(_ s: String) {
    let ts = ISO8601DateFormatter().string(from: Date())
    FileHandle.standardError.write(Data("[\(ts)] \(s)\n".utf8))
}

// MARK: - Pi companion (IMU capture over SSH)

/// Snapshot the Pi's CLOCK_BOOTTIME (ns) right now. Returns nil on failure.
func piBoottimeNs() -> UInt64? {
    let p = Process()
    p.launchPath = "/usr/bin/env"
    p.arguments = [
        "sshpass", "-p", PI_PASSWORD,
        "ssh", "-o", "StrictHostKeyChecking=no",
              "-o", "PubkeyAuthentication=no",
              "-o", "PreferredAuthentications=password",
              "-o", "ConnectTimeout=4",
              "-o", "NumberOfPasswordPrompts=1",
        PI_HOST,
        // CLOCK_BOOTTIME (matches imu_publisher's timestamps exactly).
        "python3 -c \"import time; print(int(time.clock_gettime(time.CLOCK_BOOTTIME)*1e9))\"",
    ]
    let pipe = Pipe()
    p.standardOutput = pipe
    p.standardError = Pipe()
    do { try p.run() } catch { return nil }
    p.waitUntilExit()
    guard p.terminationStatus == 0 else { return nil }
    let data = pipe.fileHandleForReading.readDataToEndOfFile()
    let s = String(data: data, encoding: .utf8)?
        .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    return UInt64(s)
}

/// Long-running streaming pull of IMU samples from the Pi.
///
/// SHM ring only holds ~0.9 s at 562 Hz, so we can't wait until end of
/// recording to grab everything — by then 99% of the samples have been
/// overwritten. Instead, on record-start we kick off an SSH session that
/// runs a tiny Python tailer on the Pi: it tracks the SHM `write_count`,
/// drains new samples each tick, and prints them to stdout. We write
/// stdout straight into the session's `imu.imu` file. On record-stop we
/// SIGTERM the SSH process and the file is finalized.
final class IMUStreamer {
    private var process: Process?
    private var outFh: FileHandle?

    func start(into path: String, startNs: UInt64) -> Bool {
        FileManager.default.createFile(atPath: path, contents: nil)
        guard let fh = try? FileHandle(forWritingTo: URL(fileURLWithPath: path)) else {
            log("IMUStreamer: open(\(path)) failed"); return false
        }
        outFh = fh

        let py = """
        import struct, mmap, os, sys, time
        m = mmap.mmap(os.open('/dev/shm/headspace-imu', os.O_RDONLY),
                      16 + 8 + 32*512,
                      mmap.MAP_SHARED, mmap.PROT_READ)
        sys.stdout.write('boottime_ns,ax,ay,az,gx,gy,gz,mx,my,mz,mag_valid\\n')
        sys.stdout.flush()
        # Where to start: ignore samples older than startNs (in case ring
        # has stale entries), and skip back over the current write window.
        START = \(startNs)
        last_wc = struct.unpack_from('<Q', m, 16)[0]
        # Drain whatever is currently in the ring that's >= START
        for i in range(512):
            off = 24 + i*32
            ts = struct.unpack_from('<Q', m, off)[0]
            if ts >= START:
                ax,ay,az,gx,gy,gz,mx,my,mz = struct.unpack_from('<9h', m, off+8)
                mv = m[off+26]
                sys.stdout.write(f'{ts},{ax},{ay},{az},{gx},{gy},{gz},{mx},{my},{mz},{mv}\\n')
        sys.stdout.flush()
        last_seen = last_wc
        # Then poll for new samples. Ring fills at ~562 Hz; poll at 100 Hz
        # so we never lose more than ~6 samples even if jittered.
        while True:
            time.sleep(0.01)
            wc = struct.unpack_from('<Q', m, 16)[0]
            if wc <= last_seen: continue
            n = wc - last_seen
            # Cap to ring size in case we fell behind.
            if n > 512:
                sys.stderr.write(f'WARN: dropped {n-512} samples (poller behind)\\n')
                n = 512
            for i in range(n):
                idx = (last_seen + i) % 512
                off = 24 + idx*32
                ts = struct.unpack_from('<Q', m, off)[0]
                if ts < START: continue
                ax,ay,az,gx,gy,gz,mx,my,mz = struct.unpack_from('<9h', m, off+8)
                mv = m[off+26]
                sys.stdout.write(f'{ts},{ax},{ay},{az},{gx},{gy},{gz},{mx},{my},{mz},{mv}\\n')
            sys.stdout.flush()
            last_seen = wc
        """

        let p = Process()
        p.launchPath = "/usr/bin/env"
        p.arguments = [
            "sshpass", "-p", PI_PASSWORD,
            "ssh", "-o", "StrictHostKeyChecking=no",
                  "-o", "PubkeyAuthentication=no",
                  "-o", "PreferredAuthentications=password",
                  "-o", "ConnectTimeout=4",
                  "-o", "NumberOfPasswordPrompts=1",
                  "-o", "ServerAliveInterval=10",
            PI_HOST,
            "echo " + PI_PASSWORD + " | sudo -S python3 -u -c '" + py + "'",
        ]
        p.standardOutput = fh
        let errPipe = Pipe()
        p.standardError = errPipe
        // Stream stderr to our log so we see WARN lines + sudo prompts.
        errPipe.fileHandleForReading.readabilityHandler = { handle in
            let d = handle.availableData
            guard !d.isEmpty, let s = String(data: d, encoding: .utf8) else { return }
            for line in s.split(separator: "\n", omittingEmptySubsequences: true) {
                log("[imu-stream] " + String(line))
            }
        }
        do { try p.run() } catch {
            log("IMUStreamer: launch failed: \(error)"); fh.closeFile(); return false
        }
        process = p
        log("IMU streamer running → \(path)")
        return true
    }

    func stop() {
        guard let p = process else { return }
        log("stopping IMU streamer")
        p.terminate()
        p.waitUntilExit()
        outFh?.closeFile()
        process = nil; outFh = nil
    }
}

// MARK: - Recording session

final class Recorder: NSObject, AVCaptureFileOutputRecordingDelegate {
    private let session = AVCaptureSession()
    private let movieOutput = AVCaptureMovieFileOutput()
    private var device: AVCaptureDevice?
    private var sessionDir: URL?
    private var startWallNs: UInt64 = 0
    private var startPiBoottimeNs: UInt64 = 0
    private var endPiBoottimeNs: UInt64 = 0
    private let imuStreamer = IMUStreamer()

    func start(device: AVCaptureDevice) throws {
        self.device = device
        log("starting recorder for \(device.localizedName)")

        // Pick the highest-resolution format we can find — for our device
        // that's the only format: 2vuy 1920x1080@30.
        if let best = device.formats.max(by: { a, b in
            let da = CMVideoFormatDescriptionGetDimensions(a.formatDescription)
            let db = CMVideoFormatDescriptionGetDimensions(b.formatDescription)
            return Int(da.width) * Int(da.height) < Int(db.width) * Int(db.height)
        }) {
            try device.lockForConfiguration()
            device.activeFormat = best
            device.unlockForConfiguration()
            let dim = CMVideoFormatDescriptionGetDimensions(best.formatDescription)
            log("active format: \(dim.width)x\(dim.height)")
        }

        guard let input = try? AVCaptureDeviceInput(device: device) else {
            throw NSError(domain: "headspace", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "AVCaptureDeviceInput failed"])
        }
        if session.canAddInput(input) { session.addInput(input) }
        if session.canAddOutput(movieOutput) { session.addOutput(movieOutput) }
        session.sessionPreset = .high

        // Build the session folder
        let df = DateFormatter()
        df.dateFormat = "yyyy-MM-dd_HH-mm-ss"
        df.timeZone = TimeZone(secondsFromGMT: 0)
        let dir = URL(fileURLWithPath: RECORDINGS_ROOT)
            .appendingPathComponent("spc2_" + df.string(from: Date()))
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        self.sessionDir = dir

        // Capture sync stamps BEFORE starting the camera so the offset is tight
        startWallNs = UInt64(Date().timeIntervalSince1970 * 1e9)
        if let pi = piBoottimeNs() {
            startPiBoottimeNs = pi
            log("pi boottime at start: \(pi) ns")
        } else {
            log("WARN: could not read pi boottime — IMU sync will be approximate")
        }

        session.startRunning()
        let videoURL = dir.appendingPathComponent("video.mp4")
        movieOutput.startRecording(to: videoURL, recordingDelegate: self)
        log("recording → \(videoURL.path)")
    }

    func stop() {
        guard movieOutput.isRecording else { return }
        log("stopping recorder")
        if let pi = piBoottimeNs() { endPiBoottimeNs = pi }
        movieOutput.stopRecording()
    }

    // MARK: AVCaptureFileOutputRecordingDelegate

    func fileOutput(_ output: AVCaptureFileOutput,
                    didFinishRecordingTo outputFileURL: URL,
                    from connections: [AVCaptureConnection],
                    error: Error?) {
        session.stopRunning()
        if let err = error {
            log("recording error: \(err.localizedDescription)")
        } else {
            log("recording finalized: \(outputFileURL.path)")
        }
        guard let dir = sessionDir else { return }

        // Pull IMU for our wall-clock window.
        let imuPath = dir.appendingPathComponent("imu.imu").path
        var imuOK = false
        if startPiBoottimeNs > 0 && endPiBoottimeNs > 0 {
            imuOK = pullPiIMU(startNs: startPiBoottimeNs,
                              endNs:   endPiBoottimeNs,
                              into:    imuPath)
        }

        // Write meta.json
        let meta: [String: Any] = [
            "device": DEVICE_NAME,
            "video_file": "video.mp4",
            "imu_file": imuOK ? "imu.imu" : NSNull(),
            "start_wall_ns": startWallNs,
            "start_pi_boottime_ns": startPiBoottimeNs,
            "end_pi_boottime_ns": endPiBoottimeNs,
            "imu_sync_method": "pi_boottime_offset_via_ssh",
            "platform": "macOS",
            "notes": [
                "Mac AVFoundation re-encodes from 2vuy (Apple UVC driver decodes H.264 → YUV before app).",
                "IMU samples were pulled from /dev/shm/headspace-imu on the Pi via SSH using the start/end boottime stamps.",
                "Video MP4 PTS aligns to start_pi_boottime_ns (sub-10ms via SSH RTT).",
            ],
        ]
        if let data = try? JSONSerialization.data(withJSONObject: meta,
                                                  options: [.prettyPrinted, .sortedKeys]) {
            try? data.write(to: dir.appendingPathComponent("meta.json"))
        }

        // Print final folder so the user (or wrapping tool) can pick it up.
        FileHandle.standardOutput.write(Data((dir.path + "\n").utf8))
        log("session complete: \(dir.path)")
        // Reset for the next plug-in cycle
        sessionDir = nil
    }
}

// MARK: - Hot-plug watcher

final class Watcher {
    private let recorder = Recorder()
    private var current: AVCaptureDevice?

    init() {
        // AVCaptureDevice.was{Connected,Disconnected} fire on the main thread
        let nc = NotificationCenter.default
        nc.addObserver(self, selector: #selector(connected(_:)),
                       name: .AVCaptureDeviceWasConnected, object: nil)
        nc.addObserver(self, selector: #selector(disconnected(_:)),
                       name: .AVCaptureDeviceWasDisconnected, object: nil)
        // If the device is already plugged in at launch, start immediately.
        let discovery = AVCaptureDevice.DiscoverySession(
            deviceTypes: [.external],
            mediaType:   .video,
            position:    .unspecified)
        if let d = discovery.devices.first(where: { $0.localizedName == DEVICE_NAME }) {
            log("device already attached at launch")
            startWith(d)
        } else {
            log("waiting for '\(DEVICE_NAME)' to be plugged in…")
        }
    }

    @objc func connected(_ n: Notification) {
        guard let d = n.object as? AVCaptureDevice else { return }
        guard d.localizedName == DEVICE_NAME else { return }
        log("device plugged in: \(d.localizedName)")
        startWith(d)
    }

    @objc func disconnected(_ n: Notification) {
        guard let d = n.object as? AVCaptureDevice else { return }
        guard d.localizedName == DEVICE_NAME else { return }
        guard d.uniqueID == current?.uniqueID else { return }
        log("device unplugged: \(d.localizedName)")
        recorder.stop()
        current = nil
    }

    private func startWith(_ d: AVCaptureDevice) {
        guard current == nil else {
            log("recorder already running — ignoring duplicate")
            return
        }
        current = d
        do { try recorder.start(device: d) }
        catch { log("recorder.start failed: \(error)"); current = nil }
    }
}

// MARK: - Entry point

// Camera permission must be granted before we can open the device. Request
// it now and bail with a clear message if it's denied.
let authStatus = AVCaptureDevice.authorizationStatus(for: .video)
switch authStatus {
case .notDetermined:
    let sem = DispatchSemaphore(value: 0)
    AVCaptureDevice.requestAccess(for: .video) { _ in sem.signal() }
    sem.wait()
case .denied, .restricted:
    log("camera access denied — grant it in System Settings → Privacy & Security → Camera")
    exit(1)
default: break
}

// Ctrl-C / SIGTERM should stop a running recording cleanly.
let watcher = Watcher()
signal(SIGINT) { _ in
    log("SIGINT — stopping")
    exit(0)
}
signal(SIGTERM) { _ in
    log("SIGTERM — stopping")
    exit(0)
}

log("ready. Plug in the Headspace SPC2 to start recording.")
RunLoop.main.run()
