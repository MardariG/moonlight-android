package com.limelight;


import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.hardware.input.InputManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.view.Display;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.View.OnSystemUiVisibilityChangeListener;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Toast;

import com.limelight.binding.PlatformBinding;
import com.limelight.binding.input.ControllerHandler;
import com.limelight.binding.input.TouchContext;
import com.limelight.binding.input.driver.UsbDriverService;
import com.limelight.binding.input.virtual_controller.VirtualController;
import com.limelight.binding.video.CrashListener;
import com.limelight.binding.video.MediaCodecDecoderRenderer;
import com.limelight.binding.video.MediaCodecHelper;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.NvConnectionListener;
import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.GlPreferences;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.GameGestures;
import com.limelight.utils.Dialog;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.SpinnerDialog;
import com.limelight.utils.TextureSurfaceRenderer;
import com.limelight.utils.UiHelper;
import com.limelight.utils.VideoTextureRenderer;


public class GameVr extends Activity implements TextureView.SurfaceTextureListener,
        NvConnectionListener, TextureSurfaceRenderer.OnGlReadyListener,
        OnSystemUiVisibilityChangeListener, GameGestures {

    private PreferenceConfiguration prefConfig;
    private SharedPreferences tombstonePrefs;

    private NvConnection conn;
    private SpinnerDialog spinner;
    private boolean displayedFailureDialog = false;
    private boolean connecting = false;
    private boolean connected = false;
    //    private InputCaptureProvider inputCaptureProvider;
    private int modifierFlags = 0;
//    private StreamView streamView;

    private ShortcutHelper shortcutHelper;

    private MediaCodecDecoderRenderer decoderRenderer;
    private boolean reportedCrash;

    private WifiManager.WifiLock wifiLock;

    public static final String EXTRA_HOST = "Host";
    public static final String EXTRA_APP_NAME = "AppName";
    public static final String EXTRA_APP_ID = "AppId";
    public static final String EXTRA_UNIQUEID = "UniqueId";
    public static final String EXTRA_STREAMING_REMOTE = "Remote";
    public static final String EXTRA_PC_UUID = "UUID";
    public static final String EXTRA_PC_NAME = "PcName";
    public static final String EXTRA_APP_HDR = "HDR";

    //----------------------------------------------------------------------------------------
    private SharedPreferences sharedpreferences;
    private boolean settingsVisible = false;
    private VideoTextureRenderer renderer;
    private TextureView textureView;
    private int surfaceWidth;
    private int surfaceHeight;
    //----------------------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        UiHelper.setLocale(this);

        // We don't want a title bar
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        // Full-screen and don't let the display go off
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN |
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // If we're going to use immersive mode, we want to have
        // the entire screen
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);

            getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        }

        // We specified userLandscape in the manifest which isn't supported until 4.3,
        // so we must fall back at runtime to sensorLandscape.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }

        // Listen for UI visibility events
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(this);

        // Change volume button behavior
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        // Inflate the content
        setContentView(R.layout.activity_gamevr);

        // Start the spinner
        spinner = SpinnerDialog.displayDialog(this, getResources().getString(R.string.conn_establishing_title),
                getResources().getString(R.string.conn_establishing_msg), true);

        // Read the stream preferences
        prefConfig = PreferenceConfiguration.readPreferences(this);
        tombstonePrefs = GameVr.this.getSharedPreferences("DecoderTombstone", 0);

        // Warn the user if they're on a metered connection
        ConnectivityManager connMgr = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connMgr.isActiveNetworkMetered()) {
            displayTransientMessage(getResources().getString(R.string.conn_metered));
        }

        // Make sure Wi-Fi is fully powered up
        WifiManager wifiMgr = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wifiLock = wifiMgr.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Limelight");
        wifiLock.setReferenceCounted(false);
        wifiLock.acquire();

        String host = GameVr.this.getIntent().getStringExtra(EXTRA_HOST);
        String appName = GameVr.this.getIntent().getStringExtra(EXTRA_APP_NAME);
        int appId = GameVr.this.getIntent().getIntExtra(EXTRA_APP_ID, StreamConfiguration.INVALID_APP_ID);
        String uniqueId = GameVr.this.getIntent().getStringExtra(EXTRA_UNIQUEID);
        boolean remote = GameVr.this.getIntent().getBooleanExtra(EXTRA_STREAMING_REMOTE, false);
        String uuid = GameVr.this.getIntent().getStringExtra(EXTRA_PC_UUID);
        String pcName = GameVr.this.getIntent().getStringExtra(EXTRA_PC_NAME);
        boolean willStreamHdr = GameVr.this.getIntent().getBooleanExtra(EXTRA_APP_HDR, false);

        if (appId == StreamConfiguration.INVALID_APP_ID) {
            finish();
            return;
        }

        // Add a launcher shortcut for this PC (forced, since this is user interaction)
        shortcutHelper = new ShortcutHelper(this);
        shortcutHelper.createAppViewShortcut(uuid, pcName, uuid, true);
        shortcutHelper.reportShortcutUsed(uuid);

        // Initialize the MediaCodec helper before creating the decoder
        GlPreferences glPrefs = GlPreferences.readPreferences(this);
        MediaCodecHelper.initialize(this, glPrefs.glRenderer);

        // Check if the user has enabled HDR
        if (prefConfig.enableHdr) {
            // Check if the app supports it
            if (!willStreamHdr) {
                Toast.makeText(this, "This game does not support HDR10", Toast.LENGTH_SHORT).show();
            }
            // It does, so start our HDR checklist
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // We already know the app supports HDR if willStreamHdr is set.
                Display display = getWindowManager().getDefaultDisplay();
                Display.HdrCapabilities hdrCaps = display.getHdrCapabilities();

                // We must now ensure our display is compatible with HDR10
                boolean foundHdr10 = false;
                for (int hdrType : hdrCaps.getSupportedHdrTypes()) {
                    if (hdrType == Display.HdrCapabilities.HDR_TYPE_HDR10) {
                        LimeLog.info("Display supports HDR10");
                        foundHdr10 = true;
                    }
                }

                if (!foundHdr10) {
                    // Nope, no HDR for us :(
                    willStreamHdr = false;
                    Toast.makeText(this, "Display does not support HDR10", Toast.LENGTH_LONG).show();
                }
            } else {
                Toast.makeText(this, "HDR requires Android 7.0 or later", Toast.LENGTH_LONG).show();
                willStreamHdr = false;
            }
        } else {
            willStreamHdr = false;
        }

        decoderRenderer = new MediaCodecDecoderRenderer(prefConfig,
                new CrashListener() {
                    @Override
                    public void notifyCrash(Exception e) {
                        // The MediaCodec instance is going down due to a crash
                        // let's tell the user something when they open the app again

                        // We must use commit because the app will crash when we return from this function
                        tombstonePrefs.edit().putInt("CrashCount", tombstonePrefs.getInt("CrashCount", 0) + 1).commit();
                        reportedCrash = true;
                    }
                },
                tombstonePrefs.getInt("CrashCount", 0),
                connMgr.isActiveNetworkMetered(),
                willStreamHdr,
                glPrefs.glRenderer
        );

        // Don't stream HDR if the decoder can't support it
        if (willStreamHdr && !decoderRenderer.isHevcMain10Hdr10Supported()) {
            willStreamHdr = false;
            Toast.makeText(this, "Decoder does not support HEVC Main10HDR10", Toast.LENGTH_LONG).show();
        }

        // Display a message to the user if H.265 was forced on but we still didn't find a decoder
        if (prefConfig.videoFormat == PreferenceConfiguration.FORCE_H265_ON && !decoderRenderer.isHevcSupported()) {
            Toast.makeText(this, "No H.265 decoder found.\nFalling back to H.264.", Toast.LENGTH_LONG).show();
        }

        int gamepadMask = ControllerHandler.getAttachedControllerMask(this);
        if (!prefConfig.multiController && gamepadMask != 0) {
            // If any gamepads are present in non-MC mode, set only gamepad 1.
            gamepadMask = 1;
        }
        if (prefConfig.onscreenController) {
            // If we're using OSC, always set at least gamepad 1.
            gamepadMask |= 1;
        }

        // Set to the optimal mode for streaming
        float displayRefreshRate = prepareDisplayForRendering();
        LimeLog.info("Display refresh rate: " + displayRefreshRate);

        // HACK: Despite many efforts to ensure low latency consistent frame
        // delivery, the best non-lossy mechanism is to buffer 1 extra frame
        // in the output pipeline. Android does some buffering on its end
        // in SurfaceFlinger and it's difficult (impossible?) to inspect
        // the precise state of the buffer queue to the screen after we
        // release a frame for rendering.
        //
        // Since buffering a frame adds latency and we are primarily a
        // latency-optimized client, rather than one designed for picture-perfect
        // accuracy, we will synthetically induce a negative pressure on the display
        // output pipeline by driving the decoder input pipeline under the speed
        // that the display can refresh. This ensures a constant negative pressure
        // to keep latency down but does induce a periodic frame loss. However, this
        // periodic frame loss is *way* less than what we'd already get in Marshmallow's
        // display pipeline where frames are dropped outside of our control if they land
        // on the same V-sync.
        //
        // Hopefully, we can get rid of this once someone comes up with a better way
        // to track the state of the pipeline and time frames.
        int roundedRefreshRate = Math.round(displayRefreshRate);
        if (!prefConfig.disableFrameDrop && prefConfig.fps >= roundedRefreshRate) {
            if (roundedRefreshRate <= 49) {
                // Let's avoid clearly bogus refresh rates and fall back to legacy rendering
                decoderRenderer.enableLegacyFrameDropRendering();
                LimeLog.info("Bogus refresh rate: " + roundedRefreshRate);
            }
            // HACK: Avoid crashing on some MTK devices
            else if (roundedRefreshRate == 50 && decoderRenderer.is49FpsBlacklisted()) {
                // Use the old rendering strategy on these broken devices
                decoderRenderer.enableLegacyFrameDropRendering();
            } else {
                prefConfig.fps = roundedRefreshRate - 1;
                LimeLog.info("Adjusting FPS target for screen to " + prefConfig.fps);
            }
        }

        StreamConfiguration config = new StreamConfiguration.Builder()
                .setResolution(prefConfig.width, prefConfig.height)
                .setRefreshRate(prefConfig.fps)
                .setApp(new NvApp(appName, appId, willStreamHdr))
                .setBitrate(prefConfig.bitrate)
                .setEnableSops(prefConfig.enableSops)
                .enableLocalAudioPlayback(prefConfig.playHostAudio)
                .setMaxPacketSize((remote || prefConfig.width <= 1920) ? 1024 : 1292)
                .setRemote(remote)
                .setHevcBitratePercentageMultiplier(75)
                .setHevcSupported(decoderRenderer.isHevcSupported())
                .setEnableHdr(willStreamHdr)
                .setAttachedGamepadMask(gamepadMask)
                .setClientRefreshRateX100((int) (displayRefreshRate * 100))
                .build();

        // Initialize the connection
        conn = new NvConnection(host, uniqueId, config, PlatformBinding.getCryptoProvider(this));
        // Use sustained performance mode on N+ to ensure consistent
        // CPU availability
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            getWindow().setSustainedPerformanceMode(true);
        }

        if (!decoderRenderer.isAvcSupported()) {
            if (spinner != null) {
                spinner.dismiss();
                spinner = null;
            }

            // If we can't find an AVC decoder, we can't proceed
            Dialog.displayDialog(this, getResources().getString(R.string.conn_error_title),
                    "This device or ROM doesn't support hardware accelerated H.264 playback.", true);
            return;
        }

        final Button settingsButton = findViewById(R.id.settingsButton);
        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                settingsVisible = !settingsVisible;
                LinearLayout settingsLayout = findViewById(R.id.settingsPanel);
                settingsLayout.setVisibility(settingsVisible ? View.VISIBLE : View.GONE);
            }
        });

        final SeekBar zoomBar = findViewById(R.id.sizeSeek);
        zoomBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                if (GameVr.this.renderer != null) {
                    renderer.setZoomFactor((float) (100 - i));
                    SharedPreferences.Editor editor = sharedpreferences.edit();
                    editor.putFloat("ZOOM_FACTOR", (float) (100 - i));
                    editor.apply();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        final SeekBar distBar = findViewById(R.id.distSeek);
        distBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                if (GameVr.this.renderer != null) {
                    renderer.setDistortionFactor((float) i);
                    SharedPreferences.Editor editor = sharedpreferences.edit();
                    editor.putFloat("DISTORTION_FACTOR", (float) i);
                    editor.apply();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        final CheckBox wrapCheckBox = findViewById(R.id.wrapCheckbox);
        wrapCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                if (GameVr.this.renderer != null) {
                    renderer.setWrapEnabled(b);
                    SharedPreferences.Editor editor = sharedpreferences.edit();
                    editor.putBoolean("WRAP_ENABLED", b);
                    editor.apply();
                }
            }
        });

        final CheckBox singleView = findViewById(R.id.singleView);
        singleView.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                if (GameVr.this.renderer != null) {
                    renderer.setSingleView(b);
                    SharedPreferences.Editor editor = sharedpreferences.edit();
                    editor.putBoolean("SINGLE_VIEW", b);
                    editor.apply();
                }
            }
        });

        sharedpreferences = getSharedPreferences("VR_PREFERENCES", Context.MODE_PRIVATE);

        // Listen for events on the game surface
        textureView = findViewById(R.id.surface);
        textureView.setSurfaceTextureListener(this);
        textureView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (GameVr.this.renderer != null) {
                    renderer.setZoomedIn(!renderer.isZoomedIn());
                }
            }
        });
    }

    private float prepareDisplayForRendering() {
        Display display = getWindowManager().getDefaultDisplay();
        WindowManager.LayoutParams windowLayoutParams = getWindow().getAttributes();
        float displayRefreshRate;

        // On M, we can explicitly set the optimal display mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Display.Mode bestMode = display.getMode();
            for (Display.Mode candidate : display.getSupportedModes()) {
                boolean refreshRateOk = candidate.getRefreshRate() >= bestMode.getRefreshRate() &&
                        candidate.getRefreshRate() < 63;
                boolean resolutionOk = candidate.getPhysicalWidth() >= bestMode.getPhysicalWidth() &&
                        candidate.getPhysicalHeight() >= bestMode.getPhysicalHeight() &&
                        candidate.getPhysicalWidth() <= 4096;

                LimeLog.info("Examining display mode: " + candidate.getPhysicalWidth() + "x" +
                        candidate.getPhysicalHeight() + "x" + candidate.getRefreshRate());

                // On non-4K streams, we force the resolution to never change
                if (prefConfig.width < 3840) {
                    if (display.getMode().getPhysicalWidth() != candidate.getPhysicalWidth() ||
                            display.getMode().getPhysicalHeight() != candidate.getPhysicalHeight()) {
                        continue;
                    }
                }

                // Make sure the refresh rate doesn't regress
                if (!refreshRateOk) {
                    continue;
                }

                // Make sure the resolution doesn't regress
                if (!resolutionOk) {
                    continue;
                }

                bestMode = candidate;
            }
            LimeLog.info("Selected display mode: " + bestMode.getPhysicalWidth() + "x" +
                    bestMode.getPhysicalHeight() + "x" + bestMode.getRefreshRate());
            windowLayoutParams.preferredDisplayModeId = bestMode.getModeId();
            displayRefreshRate = bestMode.getRefreshRate();
        }
        // On L, we can at least tell the OS that we want 60 Hz
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            float bestRefreshRate = display.getRefreshRate();
            for (float candidate : display.getSupportedRefreshRates()) {
                if (candidate > bestRefreshRate && candidate < 63) {
                    LimeLog.info("Examining refresh rate: " + candidate);
                    bestRefreshRate = candidate;
                }
            }
            LimeLog.info("Selected refresh rate: " + bestRefreshRate);
            windowLayoutParams.preferredRefreshRate = bestRefreshRate;
            displayRefreshRate = bestRefreshRate;
        } else {
            // Otherwise, the active display refresh rate is just
            // whatever is currently in use.
            displayRefreshRate = display.getRefreshRate();
        }

        // Apply the display mode change
        getWindow().setAttributes(windowLayoutParams);

        // From 4.4 to 5.1 we can't ask for a 4K display mode, so we'll
        // need to hint the OS to provide one.
        boolean aspectRatioMatch = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT &&
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP_MR1) {
            // On KitKat and later (where we can use the whole screen via immersive mode), we'll
            // calculate whether we need to scale by aspect ratio or not. If not, we'll use
            // setFixedSize so we can handle 4K properly. The only known devices that have
            // >= 4K screens have exactly 4K screens, so we'll be able to hit this good path
            // on these devices. On Marshmallow, we can start changing to 4K manually but no
            // 4K devices run 6.0 at the moment.
            Point screenSize = new Point(0, 0);
            display.getSize(screenSize);

            double screenAspectRatio = ((double) screenSize.y) / screenSize.x;
            double streamAspectRatio = ((double) prefConfig.height) / prefConfig.width;
            if (Math.abs(screenAspectRatio - streamAspectRatio) < 0.001) {
                LimeLog.info("Stream has compatible aspect ratio with output display");
                aspectRatioMatch = true;
            }
        }

//        if (prefConfig.stretchVideo || aspectRatioMatch) {
        // Set the surface to the size of the video
//            streamView.getHolder().setFixedSize(prefConfig.width, prefConfig.height);
//        } else {
        // Set the surface to scale based on the aspect ratio of the stream
//            streamView.setDesiredAspectRatio((double) prefConfig.width / (double) prefConfig.height);
//        }
//
        return displayRefreshRate;
    }

    @SuppressLint("InlinedApi")
    private final Runnable hideSystemUi = new Runnable() {
        @Override
        public void run() {
            // In multi-window mode on N+, we need to drop our layout flags or we'll
            // be drawing underneath the system UI.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode()) {
                GameVr.this.getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }
            // Use immersive mode on 4.4+ or standard low profile on previous builds
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                GameVr.this.getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                                View.SYSTEM_UI_FLAG_FULLSCREEN |
                                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            } else {
                GameVr.this.getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN |
                                View.SYSTEM_UI_FLAG_LOW_PROFILE);
            }
        }
    };

    private void hideSystemUi(int delay) {
        Handler h = getWindow().getDecorView().getHandler();
        if (h != null) {
            h.removeCallbacks(hideSystemUi);
            h.postDelayed(hideSystemUi, delay);
        }
    }

    @Override
    @TargetApi(Build.VERSION_CODES.N)
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode) {
        super.onMultiWindowModeChanged(isInMultiWindowMode);

        // In multi-window, we don't want to use the full-screen layout
        // flag. It will cause us to collide with the system UI.
        // This function will also be called for PiP so we can cover
        // that case here too.
        if (isInMultiWindowMode) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

            // Disable performance optimizations for foreground
            getWindow().setSustainedPerformanceMode(false);
            decoderRenderer.notifyVideoBackground();
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

            // Enable performance optimizations for foreground
            getWindow().setSustainedPerformanceMode(true);
            decoderRenderer.notifyVideoForeground();
        }

        // Correct the system UI visibility flags
        hideSystemUi(50);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        wifiLock.release();
    }

    @Override
    protected void onStop() {
        super.onStop();

        SpinnerDialog.closeDialogs(this);
        Dialog.closeDialogs();

        if (conn != null) {
            int videoFormat = decoderRenderer.getActiveVideoFormat();

            displayedFailureDialog = true;
            stopConnection();

            int averageEndToEndLat = decoderRenderer.getAverageEndToEndLatency();
            int averageDecoderLat = decoderRenderer.getAverageDecoderLatency();
            String message = null;
            if (averageEndToEndLat > 0) {
                message = getResources().getString(R.string.conn_client_latency) + " " + averageEndToEndLat + " ms";
                if (averageDecoderLat > 0) {
                    message += " (" + getResources().getString(R.string.conn_client_latency_hw) + " " + averageDecoderLat + " ms)";
                }
            } else if (averageDecoderLat > 0) {
                message = getResources().getString(R.string.conn_hardware_latency) + " " + averageDecoderLat + " ms";
            }

            // Add the video codec to the post-stream toast
            if (message != null) {
                if (videoFormat == MoonBridge.VIDEO_FORMAT_H265_MAIN10) {
                    message += " [H.265 HDR]";
                } else if (videoFormat == MoonBridge.VIDEO_FORMAT_H265) {
                    message += " [H.265]";
                } else if (videoFormat == MoonBridge.VIDEO_FORMAT_H264) {
                    message += " [H.264]";
                }
            }

            if (message != null) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            }

            // Clear the tombstone count if we terminated normally
            if (!reportedCrash && tombstonePrefs.getInt("CrashCount", 0) != 0) {
                tombstonePrefs.edit()
                        .putInt("CrashCount", 0)
                        .putInt("LastNotifiedCrashCount", 0)
                        .apply();
            }
        }

        finish();
    }

    private static byte getModifierState(KeyEvent event) {
        byte modifier = 0;
        if (event.isShiftPressed()) {
            modifier |= KeyboardPacket.MODIFIER_SHIFT;
        }
        if (event.isCtrlPressed()) {
            modifier |= KeyboardPacket.MODIFIER_CTRL;
        }
        if (event.isAltPressed()) {
            modifier |= KeyboardPacket.MODIFIER_ALT;
        }
        return modifier;
    }

    private byte getModifierState() {
        return (byte) modifierFlags;
    }


    @Override
    public void showKeyboard() {
        LimeLog.info("Showing keyboard overlay");
        InputMethodManager inputManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        inputManager.toggleSoftInput(InputMethodManager.SHOW_FORCED, InputMethodManager.HIDE_IMPLICIT_ONLY);
    }

    @Override
    public void stageStarting(String stage) {
        if (spinner != null) {
            spinner.setMessage(getResources().getString(R.string.conn_starting) + " " + stage);
        }
    }

    @Override
    public void stageComplete(String stage) {
    }

    private void stopConnection() {
        if (connecting || connected) {
            connecting = connected = false;

            // Stop may take a few hundred ms to do some network I/O to tell
            // the server we're going away and clean up. Let it run in a separate
            // thread to keep things smooth for the UI. Inside moonlight-common,
            // we prevent another thread from starting a connection before and
            // during the process of stopping this one.
            new Thread() {
                public void run() {
                    conn.stop();
                }
            }.start();
        }
    }

    @Override
    public void stageFailed(String stage, long errorCode) {
        if (spinner != null) {
            spinner.dismiss();
            spinner = null;
        }

        // Enable cursor visibility again
//        inputCaptureProvider.disableCapture();

        if (!displayedFailureDialog) {
            displayedFailureDialog = true;
            LimeLog.severe(stage + " failed: " + errorCode);

            // If video initialization failed and the surface is still valid, display extra information for the user
//            if (stage.contains("video") && streamView.getHolder().getSurface().isValid()) {
//                runOnUiThread(new Runnable() {
//                    @Override
//                    public void run() {
//                        Toast.makeText(GameVr.this, "Video decoder failed to initialize. Your device may not support the selected resolution.", Toast.LENGTH_LONG).show();
//                    }
//                });
//            }

            Dialog.displayDialog(this, getResources().getString(R.string.conn_error_title),
                    getResources().getString(R.string.conn_error_msg) + " " + stage, true);
        }
    }

    @Override
    public void connectionTerminated(long errorCode) {
        // Enable cursor visibility again
//        inputCaptureProvider.disableCapture();

        if (!displayedFailureDialog) {
            displayedFailureDialog = true;
            LimeLog.severe("Connection terminated: " + errorCode);
            stopConnection();

            Dialog.displayDialog(this, getResources().getString(R.string.conn_terminated_title),
                    getResources().getString(R.string.conn_terminated_msg), true);
        }
    }

    @Override
    public void connectionStarted() {
        if (spinner != null) {
            spinner.dismiss();
            spinner = null;
        }

        connected = true;
        connecting = false;

//        runOnUiThread(new Runnable() {
//            @Override
//            public void run() {
//                // Hide the mouse cursor now. Doing it before
//                // dismissing the spinner seems to be undone
//                // when the spinner gets displayed.
//                inputCaptureProvider.enableCapture();
//            }
//        });

        hideSystemUi(1000);
    }

    @Override
    public void displayMessage(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(GameVr.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void displayTransientMessage(final String message) {
        if (!prefConfig.disableWarnings) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(GameVr.this, message, Toast.LENGTH_LONG).show();
                }
            });
        }
    }

//    @Override
//    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
//        if (!surfaceCreated) {
//            throw new IllegalStateException("Surface changed before creation!");
//        }
//
//        if (!attemptedConnection) {
//            attemptedConnection = true;
//
//            decoderRenderer.setRenderTarget(holder);
//            conn.start(PlatformBinding.getAudioRenderer(), decoderRenderer, GameVr.this);
//        }
//    }

//    @Override
//    public void surfaceCreated(SurfaceHolder holder) {
//        surfaceCreated = true;
//    }
//
//    @Override
//    public void surfaceDestroyed(SurfaceHolder holder) {
//        if (!surfaceCreated) {
//            throw new IllegalStateException("Surface destroyed before creation!");
//        }
//
//        if (attemptedConnection) {
//            // Let the decoder know immediately that the surface is gone
//            decoderRenderer.prepareForStop();
//
//            if (connected) {
//                stopConnection();
//            }
//        }
//    }

    @Override
    public void onSystemUiVisibilityChange(int visibility) {
        // Don't do anything if we're not connected
        if (!connected) {
            return;
        }

        // This flag is set for all devices
        if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
            hideSystemUi(2000);
        }
        // This flag is only set on 4.4+
        else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT &&
                (visibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) {
            hideSystemUi(2000);
        }
        // This flag is only set before 4.4+
        else if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.KITKAT &&
                (visibility & View.SYSTEM_UI_FLAG_LOW_PROFILE) == 0) {
            hideSystemUi(2000);
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        surfaceWidth = width;
        surfaceHeight = height;

        if (!connected && !connecting) {
            connecting = true;

            renderer = new VideoTextureRenderer(this, textureView.getSurfaceTexture(), surfaceWidth, surfaceHeight, this);
            renderer.setVideoSize(prefConfig.width, prefConfig.height);

            renderer.setZoomFactor(sharedpreferences.getFloat("ZOOM_FACTOR", 50));
            renderer.setDistortionFactor(sharedpreferences.getFloat("DISTORTION_FACTOR", 81));
            renderer.setWrapEnabled(sharedpreferences.getBoolean("WRAP_ENABLED", true));
            renderer.setSingleView(sharedpreferences.getBoolean("SINGLE_VIEW", false));
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        if (connected) {
            decoderRenderer.stop();
            stopConnection();
        }

        return false;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void onGlReady() {
        System.out.println("------------------");
        System.out.println("onGlReady");
        System.out.println("------------------");
//        conn.start(PlatformBinding.getDeviceName(), new Surface(renderer.getVideoTexture()), drFlags, PlatformBinding.getAudioRenderer(), decoderRenderer);
        decoderRenderer.setRenderTarget(new Surface(renderer.getVideoTexture()));
        conn.start(decoderRenderer, GameVr.this);
    }
}
