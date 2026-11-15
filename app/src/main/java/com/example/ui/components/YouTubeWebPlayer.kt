package com.example.ui.components

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@Stable
class YouTubePlayerController {
    internal var webView: WebView? = null
    internal var exitFullscreenCallback: (() -> Unit)? = null

    fun play() {
        webView?.evaluateJavascript("if (window.playVideo) window.playVideo();", null)
    }

    fun pause() {
        webView?.evaluateJavascript("if (window.pauseVideo) window.pauseVideo();", null)
    }

    fun seekBy(deltaSeconds: Int) {
        webView?.evaluateJavascript("if (window.seekBy) window.seekBy($deltaSeconds);", null)
    }

    fun setPlaybackRate(rate: Float) {
        webView?.evaluateJavascript("if (window.setRate) window.setRate($rate);", null)
    }

    fun exitFullscreen() {
        exitFullscreenCallback?.invoke()
    }
}

@Composable
fun rememberYouTubePlayerController(): YouTubePlayerController = remember { YouTubePlayerController() }

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeWebPlayer(
    videoId: String,
    playbackSpeed: Float,
    controller: YouTubePlayerController,
    onProgressUpdate: (currentSeconds: Float, durationSeconds: Float, isPlaying: Boolean) -> Unit,
    onFullscreenChanged: (Boolean) -> Unit,
    onEmbedError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val currentProgressCb by rememberUpdatedState(onProgressUpdate)
    val currentFullscreenCb by rememberUpdatedState(onFullscreenChanged)
    val currentErrorCb by rememberUpdatedState(onEmbedError)

    var loadedVideoId by remember { mutableStateOf("") }
    var customFullscreenView by remember { mutableStateOf<View?>(null) }
    var customViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

    fun restorePortraitSystemBars() {
        if (activity != null) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            val window = activity.window
            WindowCompat.getInsetsController(window, window.decorView).apply {
                show(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            }
        }
    }

    fun enterFullscreenSystemBars() {
        if (activity != null) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            val window = activity.window
            WindowCompat.getInsetsController(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    val webView = remember {
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = true
                useWideViewPort = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                userAgentString =
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
            }

            addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun onPlayerState(state: Int, current: Float, duration: Float) {
                        mainHandler.post {
                            val isPlaying = state == 1 || state == 3
                            currentProgressCb(current, duration, isPlaying)
                        }
                    }

                    @JavascriptInterface
                    fun onPlayerError(code: Int) {
                        mainHandler.post {
                            if (code == 100 || code == 101 || code == 150) {
                                currentErrorCb()
                            }
                        }
                    }
                },
                "AndroidBridge",
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
            }

            webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        currentErrorCb()
                    }
                }

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: RenderProcessGoneDetail?,
                ): Boolean {
                    currentErrorCb()
                    return true
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (view == null || activity == null) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    if (customFullscreenView != null) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    customFullscreenView = view
                    customViewCallback = callback
                    val decor = activity.window.decorView as? FrameLayout
                    decor?.addView(
                        view,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                    enterFullscreenSystemBars()
                    currentFullscreenCb(true)
                }

                override fun onHideCustomView() {
                    val decor = activity?.window?.decorView as? FrameLayout
                    customFullscreenView?.let { decor?.removeView(it) }
                    customFullscreenView = null
                    customViewCallback?.onCustomViewHidden()
                    customViewCallback = null
                    restorePortraitSystemBars()
                    currentFullscreenCb(false)
                }
            }
        }
    }

    controller.webView = webView
    controller.exitFullscreenCallback = {
        webView.evaluateJavascript("if (document.fullscreenElement) document.exitFullscreen();", null)
        if (customFullscreenView != null) {
            val decor = activity?.window?.decorView as? FrameLayout
            customFullscreenView?.let { decor?.removeView(it) }
            customFullscreenView = null
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null
            restorePortraitSystemBars()
            currentFullscreenCb(false)
        }
    }

    LaunchedEffect(videoId) {
        if (loadedVideoId.isEmpty()) {
            loadedVideoId = videoId
            webView.loadDataWithBaseURL(
                "https://www.youtube.com",
                buildYouTubeHtml(videoId),
                "text/html",
                "utf-8",
                null,
            )
        } else if (loadedVideoId != videoId) {
            loadedVideoId = videoId
            webView.evaluateJavascript("if (window.loadNewVideo) window.loadNewVideo('$videoId');", null)
        }
    }

    LaunchedEffect(playbackSpeed) {
        controller.setPlaybackRate(playbackSpeed)
    }

    DisposableEffect(Unit) {
        onDispose {
            controller.exitFullscreen()
            controller.webView = null
            restorePortraitSystemBars()
            webView.stopLoading()
            webView.destroy()
        }
    }

    Box(modifier = modifier.background(Color.Black)) {
        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun buildYouTubeHtml(initialVideoId: String): String = """
    <!DOCTYPE html>
    <html>
    <head>
      <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
      <style>
        html, body {
          margin: 0;
          padding: 0;
          width: 100%;
          height: 100%;
          background-color: #000000;
          overflow: hidden;
        }
        #player {
          position: absolute;
          top: 0;
          left: 0;
          width: 100%;
          height: 100%;
        }
      </style>
    </head>
    <body>
      <div id="player"></div>
      <script src="https://www.youtube.com/iframe_api"></script>
      <script>
        var player;
        var pendingVideoId = '$initialVideoId';
        var currentState = -1;

        function onYouTubeIframeAPIReady() {
          player = new YT.Player('player', {
            videoId: pendingVideoId,
            playerVars: {
              autoplay: 1,
              playsinline: 1,
              rel: 0,
              modestbranding: 1,
              fs: 1,
              enablejsapi: 1,
              origin: 'https://www.youtube.com'
            },
            events: {
              onReady: function(e) {
                e.target.playVideo();
              },
              onStateChange: function(e) {
                currentState = e.data;
                reportProgress();
              },
              onError: function(e) {
                if (window.AndroidBridge) {
                  window.AndroidBridge.onPlayerError(e.data);
                }
              }
            }
          });
        }

        function reportProgress() {
          if (player && player.getCurrentTime && window.AndroidBridge) {
            var cur = player.getCurrentTime() || 0;
            var dur = player.getDuration() || 0;
            window.AndroidBridge.onPlayerState(currentState, cur, dur);
          }
        }

        setInterval(function() {
          if (currentState === 1) {
            reportProgress();
          }
        }, 1000);

        window.loadNewVideo = function(id) {
          if (player && player.loadVideoById) {
            player.loadVideoById(id);
          } else {
            pendingVideoId = id;
          }
        };

        window.playVideo = function() {
          if (player && player.playVideo) player.playVideo();
        };

        window.pauseVideo = function() {
          if (player && player.pauseVideo) player.pauseVideo();
        };

        window.seekBy = function(delta) {
          if (player && player.getCurrentTime && player.seekTo) {
            var target = Math.max(0, (player.getCurrentTime() || 0) + delta);
            player.seekTo(target, true);
          }
        };

        window.setRate = function(rate) {
          if (player && player.setPlaybackRate) {
            player.setPlaybackRate(rate);
          }
        };
      </script>
    </body>
    </html>
""".trimIndent()
