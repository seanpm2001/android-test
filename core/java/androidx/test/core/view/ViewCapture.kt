/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:JvmName("ViewCapture")

package androidx.test.core.view

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import androidx.annotation.RequiresApi
import androidx.concurrent.futures.ResolvableFuture
import androidx.test.annotation.ExperimentalTestApi
import androidx.test.core.internal.os.HandlerExecutor
import androidx.test.platform.graphics.HardwareRendererCompat
import com.google.common.util.concurrent.ListenableFuture

/**
 * Asynchronously captures an image of the underlying view into a [Bitmap].
 *
 * For devices below [Build.VERSION_CODES#O] (or if the view's window cannot be determined), the
 * image is obtained using [View#draw]. Otherwise, [PixelCopy] is used.
 *
 * This method will also enable [HardwareRendererCompat#setDrawingEnabled(boolean)] if required.
 *
 * This API is primarily intended for use in lower layer libraries or frameworks. For test authors,
 * its recommended to use espresso or compose's captureToImage.
 *
 * This API currently does not work for View's hosted in Dialogs on APIs >= 26, as there is no way
 * to find a Dialog's Window. (see b/195673633).
 *
 * This API is currently experimental and subject to change or removal.
 */
@ExperimentalTestApi
@RequiresApi(Build.VERSION_CODES.JELLY_BEAN)
fun View.captureToBitmap(): ListenableFuture<Bitmap> {
  val bitmapFuture: ResolvableFuture<Bitmap> = ResolvableFuture.create()
  val mainExecutor = HandlerExecutor(Handler(Looper.getMainLooper()))

  // disable drawing again if necessary once work is complete
  if (!HardwareRendererCompat.isDrawingEnabled()) {
    HardwareRendererCompat.setDrawingEnabled(true)
    bitmapFuture.addListener({ HardwareRendererCompat.setDrawingEnabled(false) }, mainExecutor)
  }

  mainExecutor.execute {
    val forceRedrawFuture = forceRedraw()
    forceRedrawFuture.addListener({ generateBitmap(bitmapFuture) }, mainExecutor)
  }

  return bitmapFuture
}

/**
 * Trigger a redraw of the given view.
 *
 * Should only be called on UI thread.
 *
 * @return a [ListenableFuture] that will be complete once ui drawing is complete
 */
// NoClassDefFoundError occurs on API 15
@RequiresApi(Build.VERSION_CODES.JELLY_BEAN)
// @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
@ExperimentalTestApi
fun View.forceRedraw(): ListenableFuture<Void> {
  val future: ResolvableFuture<Void> = ResolvableFuture.create()

  if (Build.VERSION.SDK_INT >= 29 && isHardwareAccelerated) {
    viewTreeObserver.registerFrameCommitCallback() { future.set(null) }
  } else {
    viewTreeObserver.addOnDrawListener(
      object : ViewTreeObserver.OnDrawListener {
        var handled = false
        override fun onDraw() {
          if (!handled) {
            handled = true
            future.set(null)
            // cannot remove on draw listener inside of onDraw
            Handler(Looper.getMainLooper()).post { viewTreeObserver.removeOnDrawListener(this) }
          }
        }
      }
    )
  }
  invalidate()
  return future
}

private fun View.generateBitmap(bitmapFuture: ResolvableFuture<Bitmap>) {
  if (bitmapFuture.isCancelled) {
    return
  }
  val destBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
  when {
    Build.VERSION.SDK_INT < 26 -> generateBitmapFromDraw(destBitmap, bitmapFuture)
    this is SurfaceView -> generateBitmapFromSurfaceViewPixelCopy(destBitmap, bitmapFuture)
    else -> {
      val window = getActivity()?.window
      if (window != null) {
        generateBitmapFromPixelCopy(window, destBitmap, bitmapFuture)
      } else {
        bitmapFuture.setException(IllegalStateException("Could not find window for view."))
      }
    }
  }
}

@SuppressWarnings("NewApi")
private fun SurfaceView.generateBitmapFromSurfaceViewPixelCopy(
  destBitmap: Bitmap,
  bitmapFuture: ResolvableFuture<Bitmap>
) {
  val onCopyFinished =
    PixelCopy.OnPixelCopyFinishedListener { result ->
      if (result == PixelCopy.SUCCESS) {
        bitmapFuture.set(destBitmap)
      } else {
        bitmapFuture.setException(RuntimeException(String.format("PixelCopy failed: %d", result)))
      }
    }
  PixelCopy.request(this, null, destBitmap, onCopyFinished, handler)
}

internal fun View.generateBitmapFromDraw(
  destBitmap: Bitmap,
  bitmapFuture: ResolvableFuture<Bitmap>
) {
  destBitmap.density = resources.displayMetrics.densityDpi
  computeScroll()
  val canvas = Canvas(destBitmap)
  canvas.translate((-scrollX).toFloat(), (-scrollY).toFloat())
  draw(canvas)
  bitmapFuture.set(destBitmap)
}

private fun View.getActivity(): Activity? {
  fun Context.getActivity(): Activity? {
    return when (this) {
      is Activity -> this
      is ContextWrapper -> this.baseContext.getActivity()
      else -> null
    }
  }

  val activity = context.getActivity()
  if (activity != null) {
    return activity
  } else if (this is ViewGroup && this.childCount > 0) {
    // getActivity is known to fail if View is a DecorView such as specified via espresso's
    // isRoot().
    // Make another attempt to find the activity from its first child view
    return getChildAt(0).getActivity()
  } else {
    return null
  }
}

private fun View.generateBitmapFromPixelCopy(
  window: Window,
  destBitmap: Bitmap,
  bitmapFuture: ResolvableFuture<Bitmap>
) {
  val locationInWindow = intArrayOf(0, 0)
  getLocationInWindow(locationInWindow)
  val x = locationInWindow[0]
  val y = locationInWindow[1]
  val boundsInWindow = Rect(x, y, x + width, y + height)

  return window.generateBitmapFromPixelCopy(boundsInWindow, destBitmap, bitmapFuture)
}
