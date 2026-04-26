package com.mercurylabs.headspace

import android.content.Context
import android.hardware.usb.UsbDevice
import android.util.Log
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.serenegiant.usb.USBMonitor

/**
 * Wrapper around AndroidUSBCamera (jiangdongguo, MIT-style license, bundles
 * libusb + libuvc as native code).
 *
 * Why we use it: Mediatek-based phones (e.g. moto g45) reject UVC class-
 * control transfers (probe/commit) via the AOSP USB API — the host stack
 * returns -1 and the camera can't be negotiated. libuvc inside this lib
 * goes through libusb directly, bypassing the broken Android path.
 *
 * Format: MJPEG @ 1080p30. The Pi gadget exposes MJPEG; the lib decodes
 * to NV21 internally and passes us the byte buffer per frame.
 */
class CameraHelper(
    private val ctx: Context,
    private val onFrame: (nv21: ByteArray, w: Int, h: Int) -> Unit,
    private val onState: (opened: Boolean, msg: String) -> Unit,
) : IDeviceConnectCallBack {
    private val client = MultiCameraClient(ctx, this)
    private var camera: MultiCameraClient.Camera? = null
    @Volatile private var lastFrameW = 1920
    @Volatile private var lastFrameH = 1080

    fun start() {
        client.register()
        // The library only fires onAttachDev for *new* attaches after register().
        // If the device was already plugged in (which IS our typical case —
        // user opens the app while the Pi is connected), we need to discover
        // it ourselves and trigger the attach path.
        val list = client.getDeviceList(null) ?: emptyList()
        Log.i(TAG, "register: ${list.size} pre-attached USB device(s)")
        for (d in list) {
            Log.i(TAG, "  pre-attached vid=0x${"%04x".format(d.vendorId)} pid=0x${"%04x".format(d.productId)} '${d.productName}'")
            onAttachDev(d)
        }
    }

    fun stop() {
        camera?.closeCamera()
        camera = null
        client.unRegister()
        client.destroy()
    }

    override fun onAttachDev(device: UsbDevice?) {
        device ?: return
        if (camera != null) return
        val granted = client.hasPermission(device) == true
        Log.i(TAG, "device attached: ${device.productName} hasPermission=$granted")
        if (granted) {
            // Library short-circuits requestPermission when permission is
            // already granted, but ALSO doesn't fire onConnectDev. Jump
            // straight to opening the device via the library's USBMonitor
            // (private field — reflection). This is the proven workaround
            // documented in the lib's GitHub issues.
            try {
                val f = client.javaClass.getDeclaredField("mUsbMonitor")
                f.isAccessible = true
                val mon = f.get(client) as USBMonitor
                val ctrl = mon.openDevice(device)
                onConnectDev(device, ctrl)
            } catch (e: Throwable) {
                Log.e(TAG, "openDevice via reflection failed", e)
                CrashLog.writeException(ctx, "openDevice", e)
            }
        } else {
            client.requestPermission(device)
        }
    }

    override fun onDetachDec(device: UsbDevice?) {
        Log.i(TAG, "device detached: ${device?.productName}")
        camera?.closeCamera(); camera = null
        onState(false, "device detached")
    }

    override fun onConnectDev(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
        device ?: return; ctrlBlock ?: return
        val cam = MultiCameraClient.Camera(ctx, device)
        cam.setUsbControlBlock(ctrlBlock)
        camera = cam

        cam.setCameraStateCallBack(object : ICameraStateCallBack {
            override fun onCameraState(self: MultiCameraClient.Camera, code: ICameraStateCallBack.State, msg: String?) {
                Log.i(TAG, "camera state: $code msg=$msg")
                when (code) {
                    ICameraStateCallBack.State.OPENED -> onState(true, "opened")
                    ICameraStateCallBack.State.CLOSED -> onState(false, "closed")
                    ICameraStateCallBack.State.ERROR  -> onState(false, msg ?: "error")
                }
            }
        })
        cam.addPreviewDataCallBack(object : IPreviewDataCallBack {
            override fun onPreviewData(data: ByteArray?, format: IPreviewDataCallBack.DataFormat) {
                if (data == null) return
                if (format == IPreviewDataCallBack.DataFormat.NV21) {
                    onFrame(data, lastFrameW, lastFrameH)
                }
            }
        })

        val req = CameraRequest.Builder()
            .setPreviewWidth(1920)
            .setPreviewHeight(1080)
            .create()
        cam.openCamera(null, req)
    }

    override fun onDisConnectDec(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
        camera?.closeCamera(); camera = null
    }

    override fun onCancelDev(device: UsbDevice?) {
        Log.w(TAG, "USB permission denied")
        onState(false, "permission denied")
    }

    companion object { private const val TAG = "CameraHelper" }
}
