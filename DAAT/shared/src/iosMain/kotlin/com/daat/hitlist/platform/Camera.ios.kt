@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.daat.hitlist.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSSelectorFromString
import platform.UIKit.UIApplication
import platform.UIKit.UIButton
import platform.UIKit.UIButtonTypeSystem
import platform.UIKit.UIColor
import platform.UIKit.UIControlEventTouchUpInside
import platform.UIKit.UIControlStateNormal
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerOriginalImage
import platform.UIKit.UIImagePickerControllerSourceType
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UIKit.UIScreen
import platform.UIKit.UIView
import platform.UIKit.UIViewController
import platform.darwin.NSObject

private const val MAX_EDGE = 1080.0
private const val JPEG_QUALITY = 0.8

/** Redraws the photo upright at no more than 1080 px on the long edge and encodes a JPEG. */
private fun UIImage.toUploadJpeg(): ByteArray? {
    val (w, h) = size.useContents { width to height }
    if (w <= 0.0 || h <= 0.0) return null
    val scale = minOf(1.0, MAX_EDGE / maxOf(w, h))
    UIGraphicsBeginImageContextWithOptions(CGSizeMake(w * scale, h * scale), true, 1.0)
    drawInRect(CGRectMake(0.0, 0.0, w * scale, h * scale))
    val resized = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()
    return resized?.let { UIImageJPEGRepresentation(it, JPEG_QUALITY) }?.toByteArray()
}

/** Target for the overlay buttons; UIKit calls back through Objective-C selectors. */
private class ButtonTarget(private val onShutter: () -> Unit, private val onClose: () -> Unit) : NSObject() {
    @ObjCAction
    fun shutter() = onShutter()

    @ObjCAction
    fun close() = onClose()
}

private class PickerDelegate(
    private val onImage: (UIImage?) -> Unit,
    private val onCancel: () -> Unit,
) : NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {
    override fun imagePickerController(picker: UIImagePickerController, didFinishPickingMediaWithInfo: Map<Any?, *>) {
        onImage(didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage)
    }

    override fun imagePickerControllerDidCancel(picker: UIImagePickerController) = onCancel()
}

private fun topViewController(): UIViewController? {
    var top = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (top?.presentedViewController != null) top = top.presentedViewController
    return top
}

/**
 * The system camera with our own controls on top. Using our own shutter button
 * (instead of the built-in one with its "Use Photo" step) means the compass
 * heading and time are read at the instant the picture is taken.
 */
@Composable
actual fun SnipeCamera(
    heading: Double,
    onCaptured: (CapturedPhoto) -> Unit,
    onError: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val currentHeading by rememberUpdatedState(heading)
    val currentOnCaptured by rememberUpdatedState(onCaptured)
    val currentOnError by rememberUpdatedState(onError)
    val currentOnClose by rememberUpdatedState(onClose)
    // Delegates and targets are held weakly by UIKit, so keep them alive here.
    val holder = remember { arrayOfNulls<Any>(2) }

    DisposableEffect(Unit) {
        val camera = UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera
        if (!UIImagePickerController.isSourceTypeAvailable(camera)) {
            currentOnError("NO_PERMISSION")
            return@DisposableEffect onDispose { }
        }
        val picker = UIImagePickerController()
        var headingAtShutter = 0.0
        var shutterAt = 0L
        var busy = false

        val delegate = PickerDelegate(
            onImage = { image ->
                val jpeg = image?.toUploadJpeg()
                if (jpeg == null) currentOnError("PHOTO_MISSING")
                else currentOnCaptured(CapturedPhoto(jpeg, shutterAt, headingAtShutter))
            },
            onCancel = { currentOnClose() },
        )
        val target = ButtonTarget(
            onShutter = {
                if (!busy) {
                    busy = true
                    headingAtShutter = currentHeading
                    shutterAt = nowMillis()
                    picker.takePicture()
                }
            },
            onClose = { currentOnClose() },
        )
        holder[0] = delegate
        holder[1] = target

        val (screenW, screenH) = UIScreen.mainScreen.bounds.useContents { size.width to size.height }
        val overlay = UIView(frame = CGRectMake(0.0, 0.0, screenW, screenH))
        overlay.backgroundColor = UIColor.clearColor

        val shutter = UIButton.buttonWithType(UIButtonTypeSystem)
        shutter.setFrame(CGRectMake((screenW - 84.0) / 2, screenH - 150.0, 84.0, 84.0))
        shutter.backgroundColor = UIColor(red = 0.898, green = 0.224, blue = 0.208, alpha = 1.0)
        shutter.layer.cornerRadius = 42.0
        shutter.layer.borderWidth = 4.0
        shutter.layer.borderColor = UIColor.whiteColor.CGColor
        shutter.accessibilityLabel = "Take the shot"
        shutter.addTarget(target, NSSelectorFromString("shutter"), UIControlEventTouchUpInside)
        overlay.addSubview(shutter)

        val close = UIButton.buttonWithType(UIButtonTypeSystem)
        close.setFrame(CGRectMake(16.0, 56.0, 72.0, 44.0))
        close.setTitle("Close", forState = UIControlStateNormal)
        close.setTitleColor(UIColor.whiteColor, forState = UIControlStateNormal)
        close.addTarget(target, NSSelectorFromString("close"), UIControlEventTouchUpInside)
        overlay.addSubview(close)

        picker.sourceType = camera
        picker.showsCameraControls = false
        picker.cameraOverlayView = overlay
        picker.delegate = delegate
        topViewController()?.presentViewController(picker, animated = true, completion = null)

        onDispose {
            picker.dismissViewControllerAnimated(true, completion = null)
            holder[0] = null
            holder[1] = null
        }
    }

    Box(modifier.background(Color.Black))
}
