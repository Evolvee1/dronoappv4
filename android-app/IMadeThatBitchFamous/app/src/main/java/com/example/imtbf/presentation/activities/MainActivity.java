package com.example.imtbf.presentation.activities;

import android.os.Bundle;
import android.os.Handler;
import android.text.method.ScrollingMovementMethod;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebSettings;
import android.widget.Button;
import android.widget.CompoundButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import android.widget.Toast;
import android.widget.ImageButton;
import androidx.core.widget.NestedScrollView;
import android.content.Context;
import java.util.UUID;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.content.Intent;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import com.example.imtbf.InstagramTrafficSimulatorApp;
import com.example.imtbf.R;
import com.example.imtbf.data.local.PreferencesManager;
import com.example.imtbf.data.models.DeviceProfile;
import com.example.imtbf.data.models.UserAgentData;
import com.example.imtbf.data.network.HttpRequestManager;
import com.example.imtbf.data.network.NetworkStateMonitor;
import com.example.imtbf.data.network.WebViewRequestManager;
import com.example.imtbf.databinding.ActivityMainBinding;
import com.example.imtbf.domain.simulation.BehaviorEngine;
import com.example.imtbf.domain.simulation.SessionManager;
import com.example.imtbf.data.network.SessionClearingManager;
import com.example.imtbf.domain.simulation.TimingDistributor;
import com.example.imtbf.domain.system.AirplaneModeController;
import com.example.imtbf.domain.webview.WebViewController;
import com.example.imtbf.utils.Constants;
import com.example.imtbf.utils.Logger;
import com.example.imtbf.domain.simulation.DistributionPattern;
import com.example.imtbf.domain.simulation.TrafficDistributionManager;
import com.example.imtbf.presentation.fragments.TrafficDistributionFragment;
import com.example.imtbf.data.network.NetworkStatsTracker;
import com.example.imtbf.data.network.NetworkStatsInterceptor;
import com.example.imtbf.data.models.NetworkStats;
import com.example.imtbf.data.models.NetworkSession;
import com.example.imtbf.presentation.views.NetworkSpeedGaugeView;

/**
 * Main activity for the Instagram Traffic Simulator app.
 */
public class MainActivity extends AppCompatActivity implements TrafficDistributionFragment.TrafficDistributionListener {

    private static final String TAG = "MainActivity";

    // Constants for new preferences
    private static final String PREF_DELAY_MIN = "delay_min";
    private static final String PREF_DELAY_MAX = "delay_max";
    private static final int DEFAULT_DELAY_MIN = 1; // 1 second
    private static final int DEFAULT_DELAY_MAX = 5; // 5 seconds

    private WebView webView;
    private WebViewController webViewController;
    private boolean useWebViewMode = false;
    private boolean isConfigExpanded = true;

    private ActivityMainBinding binding;
    private PreferencesManager preferencesManager;
    private NetworkStateMonitor networkStateMonitor;
    private WebViewRequestManager webViewRequestManager;
    private HttpRequestManager httpRequestManager;
    private AirplaneModeController airplaneModeController;

    private SessionClearingManager sessionClearingManager;
    private BehaviorEngine behaviorEngine;
    private TimingDistributor timingDistributor;
    private SessionManager sessionManager;
    private long simulationStartTime = 0;

    private TrafficDistributionManager trafficDistributionManager;
    private TrafficDistributionFragment trafficDistributionFragment;

    // Network statistics tracking
    private NetworkStatsTracker networkStatsTracker;
    private NetworkSpeedGaugeView networkSpeedView;
    private Handler networkUpdateHandler = new Handler();
    private Runnable networkUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            updateNetworkStats();
            networkUpdateHandler.postDelayed(this, 1000); // Update every second
        }
    };

    // Fields for time tracking
    private long startTimeMs = 0;
    private Handler timeUpdateHandler = new Handler();
    private Runnable timeUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            updateElapsedTime();
            timeUpdateHandler.postDelayed(this, 1000); // Update every second
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Initialize components
        initializeComponents();

        // Load initial IP address
        loadInitialIpAddress();

        // Set up UI
        setupUI();

        // Set up listeners
        setupListeners();

        // Observe network state
        observeNetworkState();

        // Load saved settings
        loadSettings();

        // Ensure clean state for airplane mode controller
        if (airplaneModeController != null) {
            airplaneModeController.resetState();
        }

        if (webView != null) {
            webViewController.configureWebViewForIncognito(webView);
        }

        // Handle auto_start flag with enhanced logging
        Intent intent = getIntent();
        if (intent != null) {
            boolean autoStart = intent.getBooleanExtra("auto_start", false);
            String customUrl = intent.getStringExtra("custom_url");
            int iterations = intent.getIntExtra("iterations", -1);
            
            Log.d("MainActivity", "onCreate intent received with autoStart=" + autoStart + 
                  ", customUrl=" + customUrl + ", iterations=" + iterations);
            
            if (autoStart) {
                Log.d("MainActivity", "Auto-starting simulation from onCreate");
                
                // If custom URL provided, set it
                if (customUrl != null && !customUrl.isEmpty()) {
                    binding.etTargetUrl.setText(customUrl);
                }
                
                // If iterations provided, set them
                if (iterations > 0) {
                    binding.etIterations.setText(String.valueOf(iterations));
                }
                
                // Start the simulation after a short delay to ensure UI is ready
                new Handler().postDelayed(() -> startSimulation(), 1000);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Start network monitoring
        if (networkStatsTracker != null) {
            networkStatsTracker.startTracking();
            networkUpdateHandler.post(networkUpdateRunnable);
        }

        // Register network state monitor
        networkStateMonitor.register();

        // Fetch current IP
        networkStateMonitor.fetchCurrentIpAddress();

        // Update UI based on session state
        updateUIBasedOnSessionState();
    }

    @Override
    protected void onPause() {
        super.onPause();

        // Pause network updates
        networkUpdateHandler.removeCallbacks(networkUpdateRunnable);

        // Save settings
        saveSettings();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Unregister network state monitor
        networkStateMonitor.unregister();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_settings) {
            // TODO: Open settings activity
            Toast.makeText(this, "Settings (Coming Soon)", Toast.LENGTH_SHORT).show();
            return true;
        } else if (id == R.id.action_clear_logs) {
            clearLogs();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onScheduledModeChanged(boolean enabled) {
        trafficDistributionManager.setScheduledModeEnabled(enabled);

        // Update UI based on scheduled mode
        if (enabled) {
            // Configure traffic distribution
            int iterations = Integer.parseInt(binding.etIterations.getText().toString());
            int durationHours = preferencesManager.getDistributionDurationHours();
            DistributionPattern pattern = DistributionPattern.fromString(
                    preferencesManager.getDistributionPattern());

            trafficDistributionManager.configureSchedule(iterations, durationHours, pattern);

            // Set up session manager for scheduled requests
            String targetUrl = binding.etTargetUrl.getText().toString().trim();

            // Create initial device profile (or null for random)
            DeviceProfile deviceProfile = binding.switchRandomDevices.isChecked() ?
                    null : new DeviceProfile.Builder()
                    .deviceType(DeviceProfile.TYPE_MOBILE)
                    .platform(DeviceProfile.PLATFORM_ANDROID)
                    .deviceTier(DeviceProfile.TIER_MID_RANGE)
                    .userAgent(UserAgentData.getRandomUserAgent())
                    .region("slovakia")
                    .build();

            sessionManager.configureScheduledRequests(targetUrl, deviceProfile);

            addLog("Configured scheduled traffic distribution: " +
                    iterations + " requests over " + durationHours + " hours");
        } else {
            addLog("Switched to immediate traffic distribution mode");
        }
    }

    @Override
    public void onDistributionSettingsChanged(DistributionPattern pattern, int durationHours,
                                              int peakHourStart, int peakHourEnd, float peakWeight) {
        if (trafficDistributionManager != null) {
            // Update traffic distribution settings
            int iterations = Integer.parseInt(binding.etIterations.getText().toString());
            trafficDistributionManager.configureSchedule(iterations, durationHours, pattern);

            addLog("Updated distribution settings: " + pattern.getDisplayName() +
                    ", " + durationHours + " hours");
        }
    }

    /**
     * Initialize all components needed for the app.
     */
    private void initializeComponents() {
        // Get preferences manager
        preferencesManager = ((InstagramTrafficSimulatorApp) getApplication()).getPreferencesManager();

        // Initialize network state monitor
        networkStateMonitor = new NetworkStateMonitor(this);

        // Initialize WebView request manager
        webViewRequestManager = new WebViewRequestManager(this, networkStateMonitor);

        if (webViewRequestManager != null) {
            webViewRequestManager.setUseNewWebViewPerRequest(
                    preferencesManager.isNewWebViewPerRequestEnabled()
            );
        }

        // Initialize WebView controller
        webViewController = new WebViewController(this);

        // Initialize HTTP request manager
        httpRequestManager = new HttpRequestManager(this, networkStateMonitor);

        // Initialize timing distributor
        timingDistributor = new TimingDistributor(
                preferencesManager.getMinInterval(),
                preferencesManager.getMaxInterval(),
                Constants.DEFAULT_READING_TIME_MEAN_MS,
                Constants.DEFAULT_READING_TIME_STDDEV_MS,
                Constants.SCROLL_PROBABILITY
        );

        // Initialize behavior engine
        behaviorEngine = new BehaviorEngine(timingDistributor);

        // Initialize airplane mode controller
        airplaneModeController = new AirplaneModeController(
                this,
                networkStateMonitor,
                preferencesManager.getAirplaneModeDelay()
        );

        // Initialize session clearing manager
        sessionClearingManager = new SessionClearingManager(this);

        // Initialize session manager
        sessionManager = new SessionManager(
                this,
                networkStateMonitor,
                httpRequestManager,
                webViewRequestManager,
                airplaneModeController,
                behaviorEngine,
                timingDistributor
        );

        trafficDistributionManager = new TrafficDistributionManager(this, sessionManager);

        // Initialize Metapic redirect handling
        if (webViewRequestManager != null) {
            webViewRequestManager.setHandleMetapicRedirects(
                    preferencesManager.isHandleMarketingRedirectsEnabled()
            );
        }

        // Initialize network statistics tracking
        initializeNetworkMonitoring();
    }

    /**
     * Initialize network monitoring components
     */
    private void initializeNetworkMonitoring() {
        // Create network stats tracker
        networkStatsTracker = new NetworkStatsTracker(this);

        // Add network stats interceptor to OkHttpClient
        if (httpRequestManager != null) {
            NetworkStatsInterceptor interceptor = new NetworkStatsInterceptor(networkStatsTracker);
            // Add the interceptor to your OkHttpClient
            // Note: This requires modifying HttpRequestManager to accept interceptors
            // or adding a method to add them later
        }

        // Observe network stats changes
        networkStatsTracker.getCurrentStats().observe(this, this::onNetworkStatsChanged);
        networkStatsTracker.getSessionData().observe(this, this::onSessionDataChanged);
    }

    /**
     * Test the WebView functionality
     */
    private void testWebView() {
        String testUrl = "https://detiyavanny.com/";
        DeviceProfile testProfile = new DeviceProfile.Builder()
                .deviceType(DeviceProfile.TYPE_MOBILE)
                .platform(DeviceProfile.PLATFORM_ANDROID)
                .deviceTier(DeviceProfile.TIER_MID_RANGE)
                .userAgent(UserAgentData.getRandomUserAgent())
                .region("slovakia")
                .build();

        webViewRequestManager.makeRequest(testUrl, testProfile, null, new WebViewRequestManager.RequestCallback() {
            @Override
            public void onSuccess(int statusCode, long responseTimeMs) {
                addLog("WebView test successful - Loaded in " + responseTimeMs + "ms");
            }

            @Override
            public void onError(String error) {
                addLog("WebView test failed: " + error);
            }
        });
    }

    /**
     * Load the current IP address when the app starts
     */
    private void loadInitialIpAddress() {
        // Show loading state in the UI
        binding.tvCurrentIp.setText("Current IP: Loading...");

        // Add a log entry
        addLog("Fetching initial IP address...");

        // Set a timeout for the IP fetch operation
        final Handler handler = new Handler();
        final Runnable timeoutRunnable = () -> {
            if (binding.tvCurrentIp.getText().toString().contains("Loading")) {
                binding.tvCurrentIp.setText("Current IP: Fetch timed out. Try again.");
                addLog("IP address fetch timed out");
            }
        };

        // Set 5-second timeout
        handler.postDelayed(timeoutRunnable, 5000);

        // Observe the IP address LiveData
        networkStateMonitor.getCurrentIpAddress().observe(this, ipAddress -> {
            // Remove the timeout handler since we got a response
            handler.removeCallbacks(timeoutRunnable);

            if (ipAddress != null && !ipAddress.isEmpty()) {
                binding.tvCurrentIp.setText("Current IP: " + ipAddress);
                addLog("Initial IP Address: " + ipAddress);
            } else {
                // If the IP is empty but we got a response, update UI
                if (!binding.tvCurrentIp.getText().toString().contains("timed out")) {
                    binding.tvCurrentIp.setText("Current IP: Could not determine");
                    addLog("Could not determine IP address");
                }
            }
        });

        // Force a refresh of the IP address
        networkStateMonitor.fetchCurrentIpAddress();
    }

    /**
     * Set up WebView controls
     */
    private void setupWebViewControls() {
        // Use SwitchMaterial instead of Switch
        SwitchMaterial switchUseWebView = findViewById(R.id.switchUseWebView);
        Button btnHideWebView = findViewById(R.id.btnHideWebView);
        View cardWebView = findViewById(R.id.cardWebView);

        if (switchUseWebView == null || btnHideWebView == null) {
            Logger.e(TAG, "WebView controls not found in layout");
            return;
        }

        // Set initial state
        useWebViewMode = preferencesManager.getUseWebViewMode();
        switchUseWebView.setChecked(useWebViewMode);

        if (cardWebView != null) {
            cardWebView.setVisibility(useWebViewMode ? View.VISIBLE : View.GONE);
        }

        // Set up listener for the switch
        switchUseWebView.setOnCheckedChangeListener((buttonView, isChecked) -> {
            useWebViewMode = isChecked;

            // Show WebView card if in WebView mode
            if (cardWebView != null) {
                cardWebView.setVisibility(useWebViewMode ? View.VISIBLE : View.GONE);
            }

            // Update preference
            preferencesManager.setUseWebViewMode(useWebViewMode);

            // Log the change
            addLog("Switched to " + (useWebViewMode ? "WebView" : "HTTP") + " mode");
        });

        // Set up listener for the hide button
        btnHideWebView.setOnClickListener(v -> {
            if (cardWebView != null) {
                cardWebView.setVisibility(View.GONE);
            }
        });
    }

    /**
     * Set up the UI components.
     */
    private void setupUI() {
        // Set up logs text view
        binding.tvLogs.setMovementMethod(new ScrollingMovementMethod());

        // Set up initial status
        binding.tvStatusLabel.setText("Status: Ready");
        binding.tvProgress.setText("Progress: 0/0");
        binding.tvCurrentIp.setText("Current IP: Checking...");

        // Disable stop button initially
        binding.btnStop.setEnabled(false);

        // Initialize WebView
        webView = findViewById(R.id.webView);
        if (webView != null) {
            webViewController.configureWebView(webView, null); // Initial configuration
        }

        SwitchMaterial switchAggressiveSessionClearing = findViewById(R.id.switchAggressiveSessionClearing);
        if (switchAggressiveSessionClearing != null) {
            // Set initial state from preferences
            switchAggressiveSessionClearing.setChecked(
                    preferencesManager.isAggressiveSessionClearingEnabled()
            );

            // Set listener for switch
            switchAggressiveSessionClearing.setOnCheckedChangeListener((buttonView, isChecked) -> {
                // Save preference
                preferencesManager.setAggressiveSessionClearingEnabled(isChecked);

                // Log the change
                addLog("Aggressive Session Clearing: " + (isChecked ? "Enabled" : "Disabled"));
            });
        }

        // Set up WebView controls
        setupWebViewControls();

        setupNewWebViewPerRequestSwitch();

        // Set up configuration toggle
        ImageButton btnToggleConfig = findViewById(R.id.btnToggleConfig);
        if (btnToggleConfig != null) {
            btnToggleConfig.setOnClickListener(v -> toggleConfigVisibility());
        }

        // Initialize config state
        isConfigExpanded = preferencesManager.getBoolean("config_expanded", true);
        View settingsSection = findViewById(R.id.settingsSection);
        if (settingsSection != null) {
            settingsSection.setVisibility(isConfigExpanded ? View.VISIBLE : View.GONE);
        }
        if (btnToggleConfig != null) {
            btnToggleConfig.setImageResource(isConfigExpanded ?
                    android.R.drawable.arrow_up_float : android.R.drawable.arrow_down_float);
        }

        // Set up traffic distribution fragment
        if (findViewById(R.id.fragmentTrafficDistribution) != null) {
            trafficDistributionFragment = (TrafficDistributionFragment) getSupportFragmentManager()
                    .findFragmentById(R.id.fragmentTrafficDistribution);

            if (trafficDistributionFragment == null) {
                trafficDistributionFragment = new TrafficDistributionFragment();
                getSupportFragmentManager().beginTransaction()
                        .add(R.id.fragmentTrafficDistribution, trafficDistributionFragment)
                        .commit();
            }
        }

        SwitchMaterial switchHandleRedirects = findViewById(R.id.switchHandleRedirects);
        if (switchHandleRedirects != null) {
            // Set initial state from preferences
            switchHandleRedirects.setChecked(
                    preferencesManager.isHandleMarketingRedirectsEnabled()
            );

            // Set listener for switch
            switchHandleRedirects.setOnCheckedChangeListener((buttonView, isChecked) -> {
                // Save preference
                preferencesManager.setHandleMarketingRedirectsEnabled(isChecked);

                // Update WebViewRequestManager if it exists
                if (webViewRequestManager != null) {
                    webViewRequestManager.setHandleMetapicRedirects(isChecked);
                }

                // Log the change
                addLog("Marketing Redirect Handling: " + (isChecked ? "Enabled" : "Disabled"));
            });
        }

        // Setup network statistics UI
        setupNetworkStatsUI();
    }

    /**
     * Set up network statistics UI components
     */
    private void setupNetworkStatsUI() {
        // Find network stats views
        TextView tvDownloadSpeed = findViewById(R.id.tvDownloadSpeed);
        TextView tvUploadSpeed = findViewById(R.id.tvUploadSpeed);
        TextView tvDownloadTotal = findViewById(R.id.tvDownloadTotal);
        TextView tvUploadTotal = findViewById(R.id.tvUploadTotal);
        TextView tvTotalData = findViewById(R.id.tvTotalData);
        TextView tvRequestCount = findViewById(R.id.tvRequestCount);
        TextView tvSessionDuration = findViewById(R.id.tvSessionDuration);
        TextView tvNetworkStatus = findViewById(R.id.tvNetworkStatus);
        Button btnResetStats = findViewById(R.id.btnResetStats);

        // Create network speed gauge
        FrameLayout networkGraphContainer = findViewById(R.id.networkGraphContainer);
        if (networkGraphContainer != null) {
            networkGraphContainer.removeAllViews();

            networkSpeedView = new NetworkSpeedGaugeView(this);
            networkGraphContainer.addView(networkSpeedView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        }

        // Set up reset button
        if (btnResetStats != null) {
            btnResetStats.setOnClickListener(v -> {
                if (networkStatsTracker != null) {
                    networkStatsTracker.stopTracking();
                    networkStatsTracker.startTracking();
                    if (networkSpeedView != null) {
                        networkSpeedView.reset();
                    }
                    addLog("Network statistics reset");
                }
            });
        }
    }

    /**
     * Set up the "New WebView Per Request" switch
     */
    private void setupNewWebViewPerRequestSwitch() {
        SwitchMaterial switchNewWebViewPerRequest = findViewById(R.id.switchNewWebViewPerRequest);
        if (switchNewWebViewPerRequest != null) {
            // Set initial state from preferences
            switchNewWebViewPerRequest.setChecked(
                    preferencesManager.isNewWebViewPerRequestEnabled()
            );

            // Set listener for switch
            switchNewWebViewPerRequest.setOnCheckedChangeListener((buttonView, isChecked) -> {
                // Save preference
                preferencesManager.setNewWebViewPerRequestEnabled(isChecked);

                // Update WebViewRequestManager if it exists
                if (webViewRequestManager != null) {
                    webViewRequestManager.setUseNewWebViewPerRequest(isChecked);
                }

                // Log the change
                addLog("New WebView Per Request: " + (isChecked ? "Enabled" : "Disabled"));
            });
        }
    }

    /**
     * Toggle the visibility of the configuration settings section
     */
    private void toggleConfigVisibility() {
        isConfigExpanded = !isConfigExpanded;
        View settingsSection = findViewById(R.id.settingsSection);
        ImageButton btnToggleConfig = findViewById(R.id.btnToggleConfig);

        if (settingsSection != null) {
            settingsSection.setVisibility(isConfigExpanded ? View.VISIBLE : View.GONE);
        }

        if (btnToggleConfig != null) {
            btnToggleConfig.setImageResource(isConfigExpanded ?
                    android.R.drawable.arrow_up_float : android.R.drawable.arrow_down_float);
        }

        // Store preference
        preferencesManager.setBoolean("config_expanded", isConfigExpanded);
    }

    /**
     * Prepare a WebView configured for incognito browsing
     */
    private WebView prepareIncognitoWebView(Context context, DeviceProfile deviceProfile) {
        try {
            // Create a fresh WebView for each request
            WebView freshWebView = new WebView(context);

            // Configure WebView with incognito settings and device profile
            webViewController.configureWebView(freshWebView, deviceProfile);

            // Additional optional configurations for incognito mode
            WebSettings settings = freshWebView.getSettings();

            // Ensure no tracking
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

            // Clear any potential existing data
            freshWebView.clearCache(true);
            freshWebView.clearHistory();
            freshWebView.clearFormData();

            // Add a unique identifier for the session
            freshWebView.setTag(UUID.randomUUID().toString());

            // Optional: Log the preparation
            Logger.d(TAG, "Prepared incognito WebView with unique ID: " + freshWebView.getTag());

            return freshWebView;
        } catch (Exception e) {
            // Fallback to standard WebView if something goes wrong
            Logger.e(TAG, "Error preparing incognito WebView", e);
            return new WebView(context);
        }
    }

    /**
     * Set up button click listeners.
     */
    private void setupListeners() {
        // Start button
        binding.btnStart.setOnClickListener(v -> {
            if (validateInputs()) {
                startSimulation();
            }
        });

        // Stop button
        binding.btnStop.setOnClickListener(v -> {
            stopSimulation();
        });
    }

    /**
     * Observe network state changes.
     */
    private void observeNetworkState() {
        // Observe connection state
        networkStateMonitor.getIsConnected().observe(this, isConnected -> {
            String status = isConnected ? "Connected" : "Disconnected";
            addLog("Network: " + status);
        });

        // Observe IP address
        networkStateMonitor.getCurrentIpAddress().observe(this, ipAddress -> {
            if (ipAddress != null && !ipAddress.isEmpty()) {
                binding.tvCurrentIp.setText("Current IP: " + ipAddress);
                addLog("IP Address: " + ipAddress);
            } else {
                binding.tvCurrentIp.setText("Current IP: Unknown");
            }
        });

        // Observe airplane mode
        networkStateMonitor.getIsAirplaneModeOn().observe(this, isOn -> {
            addLog("Airplane Mode: " + (isOn ? "On" : "Off"));
        });
    }

    /**
     * Load saved settings from preferences.
     */
    private void loadSettings() {
        binding.etTargetUrl.setText(preferencesManager.getTargetUrl());
        binding.etMinInterval.setText(String.valueOf(preferencesManager.getMinInterval()));
        binding.etMaxInterval.setText(String.valueOf(preferencesManager.getMaxInterval()));
        binding.etIterations.setText(String.valueOf(preferencesManager.getIterations()));

        // Load custom delay settings
        binding.etDelayMin.setText(String.valueOf(
                preferencesManager.getInt(PREF_DELAY_MIN, DEFAULT_DELAY_MIN)));
        binding.etDelayMax.setText(String.valueOf(
                preferencesManager.getInt(PREF_DELAY_MAX, DEFAULT_DELAY_MAX)));

        // Add this line to load airplane mode delay
        binding.etAirplaneModeDelay.setText(String.valueOf(
                preferencesManager.getAirplaneModeDelay()));

        // Load WebView mode setting
        useWebViewMode = preferencesManager.getUseWebViewMode();
        SwitchMaterial switchUseWebView = findViewById(R.id.switchUseWebView);
        if (switchUseWebView != null) {
            switchUseWebView.setChecked(useWebViewMode);
        }

        // Load configuration expansion state
        isConfigExpanded = preferencesManager.getBoolean("config_expanded", true);

        SwitchMaterial switchAggressiveSessionClearing =
                findViewById(R.id.switchAggressiveSessionClearing);
        if (switchAggressiveSessionClearing != null) {
            switchAggressiveSessionClearing.setChecked(
                    preferencesManager.isAggressiveSessionClearingEnabled()
            );
        }

        SwitchMaterial switchNewWebViewPerRequest = findViewById(R.id.switchNewWebViewPerRequest);
        if (switchNewWebViewPerRequest != null) {
            switchNewWebViewPerRequest.setChecked(
                    preferencesManager.isNewWebViewPerRequestEnabled()
            );
        }

        SwitchMaterial switchHandleRedirects = findViewById(R.id.switchHandleRedirects);
        if (switchHandleRedirects != null) {
            switchHandleRedirects.setChecked(
                    preferencesManager.isHandleMarketingRedirectsEnabled()
            );
        }
    }

    /**
     * Save settings to preferences.
     */
    private void saveSettings() {
        try {
            String targetUrl = binding.etTargetUrl.getText().toString().trim();
            if (!targetUrl.isEmpty()) {
                preferencesManager.setTargetUrl(targetUrl);
            }

            int minInterval = Integer.parseInt(binding.etMinInterval.getText().toString());
            preferencesManager.setMinInterval(minInterval);

            int maxInterval = Integer.parseInt(binding.etMaxInterval.getText().toString());
            preferencesManager.setMaxInterval(maxInterval);

            int iterations = Integer.parseInt(binding.etIterations.getText().toString());
            preferencesManager.setIterations(iterations);

            // Save custom delay settings
            int delayMin = Integer.parseInt(binding.etDelayMin.getText().toString());
            int delayMax = Integer.parseInt(binding.etDelayMax.getText().toString());
            preferencesManager.setInt(PREF_DELAY_MIN, delayMin);
            preferencesManager.setInt(PREF_DELAY_MAX, delayMax);

            // Add this block to save airplane mode delay
            int airplaneModeDelay = Integer.parseInt(binding.etAirplaneModeDelay.getText().toString());
            preferencesManager.setAirplaneModeDelay(airplaneModeDelay);

            // Save WebView mode setting
            preferencesManager.setUseWebViewMode(useWebViewMode);

            preferencesManager.setBoolean("config_expanded", isConfigExpanded);

        } catch (NumberFormatException e) {
            Logger.e(TAG, "Error parsing numbers", e);
        }
    }

    /**
     * Validate user inputs.
     * @return True if inputs are valid, false otherwise
     */
    private boolean validateInputs() {
        String targetUrl = binding.etTargetUrl.getText().toString().trim();
        if (targetUrl.isEmpty() || !(targetUrl.startsWith("http://") || targetUrl.startsWith("https://"))) {
            Toast.makeText(this, "Please enter a valid URL", Toast.LENGTH_SHORT).show();
            return false;
        }

        try {
            int minInterval = Integer.parseInt(binding.etMinInterval.getText().toString());
            int maxInterval = Integer.parseInt(binding.etMaxInterval.getText().toString());
            int iterations = Integer.parseInt(binding.etIterations.getText().toString());
            int delayMin = Integer.parseInt(binding.etDelayMin.getText().toString());
            int delayMax = Integer.parseInt(binding.etDelayMax.getText().toString());
            // Add this line to parse airplane mode delay
            int airplaneModeDelay = Integer.parseInt(binding.etAirplaneModeDelay.getText().toString());

            if (minInterval <= 0 || maxInterval <= 0 || iterations <= 0 || delayMin <= 0 || delayMax <= 0 || airplaneModeDelay <= 0) {
                Toast.makeText(this, "Values must be greater than 0", Toast.LENGTH_SHORT).show();
                return false;
            }

            // Add specific validation for airplane mode delay
            if (airplaneModeDelay < 1000) {
                Toast.makeText(this, "Airplane mode delay should be at least 1000ms", Toast.LENGTH_SHORT).show();
                return false;
            }

            if (minInterval > maxInterval) {
                Toast.makeText(this, "Min interval cannot be greater than max interval", Toast.LENGTH_SHORT).show();
                return false;
            }

            if (delayMin > delayMax) {
                Toast.makeText(this, "Min delay cannot be greater than max delay", Toast.LENGTH_SHORT).show();
                return false;
            }

        } catch (NumberFormatException e) {
            Toast.makeText(this, "Please enter valid numbers", Toast.LENGTH_SHORT).show();
            return false;
        }

        return true;
    }

    /**
     * Start the simulation.
     */
    private void startSimulation() {
        // Get input values
        String targetUrl = binding.etTargetUrl.getText().toString().trim();
        int iterations = Integer.parseInt(binding.etIterations.getText().toString());
        boolean useRandomDeviceProfile = binding.switchRandomDevices.isChecked();
        boolean rotateIp = binding.switchRotateIp.isChecked();

        // Get timing values
        int minInterval = Integer.parseInt(binding.etMinInterval.getText().toString());
        int maxInterval = Integer.parseInt(binding.etMaxInterval.getText().toString());
        int delayMin = Integer.parseInt(binding.etDelayMin.getText().toString());
        int delayMax = Integer.parseInt(binding.etDelayMax.getText().toString());
        //Airplane-mode settings
        int airplaneModeDelay = Integer.parseInt(binding.etAirplaneModeDelay.getText().toString());
        airplaneModeController.setAirplaneModeDelay(airplaneModeDelay);

        // Update timing distributor settings for legacy code
        timingDistributor.setMinIntervalSeconds(minInterval);
        timingDistributor.setMaxIntervalSeconds(maxInterval);

        if (trafficDistributionManager != null &&
                trafficDistributionManager.isScheduledModeEnabled()) {
            // Start in scheduled mode
            trafficDistributionManager.startDistribution();
        }

        // Update UI
        binding.btnStart.setEnabled(false);
        binding.btnStop.setEnabled(true);
        binding.tvStatusLabel.setText("Status: Running");
        binding.tvProgress.setText("Progress: 0/" + iterations);

        // Start tracking elapsed time
        startTimeMs = System.currentTimeMillis();
        updateElapsedTime();
        timeUpdateHandler.removeCallbacks(timeUpdateRunnable);
        timeUpdateHandler.postDelayed(timeUpdateRunnable, 1000);

        // Clear logs
        clearLogs();

        // Add start log
        addLog("Starting simulation: " + targetUrl + ", " +
                iterations + " iterations, " +
                "Random Devices: " + useRandomDeviceProfile + ", " +
                "Rotate IP: " + rotateIp + ", " +
                "Delays: " + delayMin + "-" + delayMax + "s, " +
                "Airplane Mode Delay: " + airplaneModeDelay + "ms, " +
                "Mode: " + (useWebViewMode ? "WebView" : "HTTP") + ", " +
                "New WebView Per Request: " + preferencesManager.isNewWebViewPerRequestEnabled());
        addLog("Target URL: " + targetUrl);

        // Set up airplane mode listener
        airplaneModeController.setOperationListener(this::showAirplaneModeOperation);

        // Reset airplane mode controller state
        airplaneModeController.resetState();

        // Set up progress observer
        sessionManager.setProgressListener((current, total) -> {
            runOnUiThread(() -> {
                binding.tvProgress.setText("Progress: " + current + "/" + total);

                // Update estimated time remaining
                if (current > 0) {
                    long elapsedMs = System.currentTimeMillis() - startTimeMs;
                    long avgTimePerIteration = elapsedMs / current;
                    long remainingMs = avgTimePerIteration * (total - current);

                    String remainingTime = formatTime(remainingMs);
                    binding.tvTimeRemaining.setText("Estimated time remaining: " + remainingTime);
                }
            });
        });

        // Check if aggressive session clearing is enabled
        boolean isAggressiveClearing =
                preferencesManager.isAggressiveSessionClearingEnabled();

        // Prepare WebView based on session clearing setting
        WebView simulationWebView = isAggressiveClearing
                ? sessionClearingManager.createCleanWebView()
                : new WebView(this);

        // Clear session data if aggressive clearing is on
        if (isAggressiveClearing) {
            sessionClearingManager.clearSessionData(simulationWebView);
        }

        // Start session with custom delay settings
        sessionManager.startSession(
                        targetUrl,
                        iterations,
                        useRandomDeviceProfile,
                        rotateIp,
                        delayMin,
                        delayMax,
                        useWebViewMode) // Pass the WebView mode flag
                .thenRun(() -> {
                    // Update UI when finished
                    runOnUiThread(() -> {
                        binding.btnStart.setEnabled(true);
                        binding.btnStop.setEnabled(false);
                        binding.tvStatusLabel.setText("Status: Completed");
                        addLog("Simulation completed");
                        timeUpdateHandler.removeCallbacks(timeUpdateRunnable);
                    });
                })
                .exceptionally(throwable -> {
                    // Handle errors
                    runOnUiThread(() -> {
                        binding.btnStart.setEnabled(true);
                        binding.btnStop.setEnabled(false);
                        binding.tvStatusLabel.setText("Status: Error");
                        addLog("Error: " + throwable.getMessage());
                        timeUpdateHandler.removeCallbacks(timeUpdateRunnable);
                    });
                    return null;
                });

        // Set up distribution listener
        trafficDistributionManager.addListener(new TrafficDistributionManager.DistributionListener() {
            @Override
            public void onDistributionStatusChanged(boolean running, int progress) {
                runOnUiThread(() -> {
                    if (!running && progress >= 100) {
                        // Completed
                        binding.btnStart.setEnabled(true);
                        binding.btnStop.setEnabled(false);
                        binding.tvStatusLabel.setText("Status: Completed");
                        addLog("Scheduled simulation completed");
                    } else if (!running) {
                        // Stopped
                        binding.btnStart.setEnabled(true);
                        binding.btnStop.setEnabled(false);
                        binding.tvStatusLabel.setText("Status: Stopped");
                        addLog("Scheduled simulation stopped");
                    }
                });
            }

            @Override
            public void onRequestScheduled(long scheduledTimeMs, int index, int totalRequests) {
                runOnUiThread(() -> {
                    binding.tvProgress.setText("Progress: " + index + "/" + totalRequests);

                    // Update fragment if available
                    if (trafficDistributionFragment != null) {
                        trafficDistributionFragment.updateDistributionStatus(
                                true,
                                (index * 100) / totalRequests,
                                index,
                                totalRequests,
                                trafficDistributionManager.getEstimatedRemainingTimeMs(),
                                trafficDistributionManager.getEstimatedCompletionTimeMs());
                    }
                });
            }
        });

        // Save settings
        preferencesManager.setSimulationRunning(true);
        saveSettings();
    }

    /**
     * Stop the simulation.
     */
    private void stopSimulation() {
        sessionManager.stopSession();

        // Update UI
        binding.btnStart.setEnabled(true);
        binding.btnStop.setEnabled(false);
        binding.tvStatusLabel.setText("Status: Stopped");
        addLog("Simulation stopped");
        timeUpdateHandler.removeCallbacks(timeUpdateRunnable);

        // Save settings
        preferencesManager.setSimulationRunning(false);

        // Reset controller state to ensure clean state for next run
        airplaneModeController.resetState();

        // Stop the scheduled distribution if it's running
        if (trafficDistributionManager != null &&
                trafficDistributionManager.isScheduledModeEnabled() &&
                trafficDistributionManager.isRunning()) {
            trafficDistributionManager.stopDistribution();
        }
    }

    /**
     * Update UI based on session state.
     */
    private void updateUIBasedOnSessionState() {
        if (preferencesManager.isSimulationRunning() && !sessionManager.isRunning()) {
            // Simulation was running but stopped (app restart)
            binding.tvStatusLabel.setText("Status: Stopped (App Restarted)");
            binding.btnStart.setEnabled(true);
            binding.btnStop.setEnabled(false);
            preferencesManager.setSimulationRunning(false);
            addLog("Detected interrupted simulation - ready to restart");

            // Reset controller state to ensure clean state
            airplaneModeController.resetState();
        } else if (sessionManager.isRunning()) {
            // Simulation is running
            binding.btnStart.setEnabled(false);
            binding.btnStop.setEnabled(true);
            binding.tvStatusLabel.setText("Status: Running");
        } else {
            // No simulation running
            binding.btnStart.setEnabled(true);
            binding.btnStop.setEnabled(false);
            binding.tvStatusLabel.setText("Status: Ready");
        }
    }

    /**
     * Update elapsed time in UI.
     */
    private void updateElapsedTime() {
        if (startTimeMs > 0) {
            long elapsedMs = System.currentTimeMillis() - startTimeMs;
            binding.tvTimeElapsed.setText("Time elapsed: " + formatTime(elapsedMs));
        }
    }

    /**
     * Update network statistics display
     */
    private void updateNetworkStats() {
        if (networkStatsTracker == null) return;

        NetworkSession session = networkStatsTracker.getCurrentSession();
        if (session != null) {
            onSessionDataChanged(session);
        }
    }

    /**
     * Handle changes to network statistics
     */
    private void onNetworkStatsChanged(NetworkStats stats) {
        if (stats == null) return;

        // Update speed gauge
        if (networkSpeedView != null) {
            networkSpeedView.addNetworkStats(stats);
        }

        // Update speed text views
        runOnUiThread(() -> {
            TextView tvDownloadSpeed = findViewById(R.id.tvDownloadSpeed);
            TextView tvUploadSpeed = findViewById(R.id.tvUploadSpeed);

            if (tvDownloadSpeed != null) {
                tvDownloadSpeed.setText(NetworkStatsTracker.formatSpeed(stats.getDownloadSpeed()));
            }

            if (tvUploadSpeed != null) {
                tvUploadSpeed.setText(NetworkStatsTracker.formatSpeed(stats.getUploadSpeed()));
            }
        });
    }

    /**
     * Handle changes to network session data
     */
    private void onSessionDataChanged(NetworkSession session) {
        if (session == null) return;

        runOnUiThread(() -> {
            // Update totals
            TextView tvDownloadTotal = findViewById(R.id.tvDownloadTotal);
            TextView tvUploadTotal = findViewById(R.id.tvUploadTotal);
            TextView tvTotalData = findViewById(R.id.tvTotalData);
            TextView tvRequestCount = findViewById(R.id.tvRequestCount);
            TextView tvSessionDuration = findViewById(R.id.tvSessionDuration);
            TextView tvNetworkStatus = findViewById(R.id.tvNetworkStatus);

            if (tvDownloadTotal != null) {
                tvDownloadTotal.setText(NetworkStatsTracker.formatBytes(session.getTotalBytesDownloaded()));
            }

            if (tvUploadTotal != null) {
                tvUploadTotal.setText(NetworkStatsTracker.formatBytes(session.getTotalBytesUploaded()));
            }

            if (tvTotalData != null) {
                tvTotalData.setText(NetworkStatsTracker.formatBytes(session.getTotalBytes()));
            }

            if (tvRequestCount != null) {
                tvRequestCount.setText("Requests: " + session.getRequestCount());
            }

            if (tvSessionDuration != null) {
                long durationSec = session.getDurationMs() / 1000;
                String durationText = String.format("%02d:%02d", durationSec / 60, durationSec % 60);
                tvSessionDuration.setText("Duration: " + durationText);
            }

            if (tvNetworkStatus != null) {
                tvNetworkStatus.setText("Monitoring: " + (session.isActive() ? "On" : "Off"));
                tvNetworkStatus.setTextColor(getResources().getColor(
                        session.isActive() ? R.color.status_success : R.color.medium_gray));
            }
        });
    }

    /**
     * Method to show airplane mode operation status.
     */
    private void showAirplaneModeOperation(boolean isOperating) {
        runOnUiThread(() -> {
            if (isOperating) {
                binding.tvStatusLabel.setText("Status: Rotating IP...");
                addLog("IP rotation in progress");
                // Could add a progress indicator here if desired
            } else {
                binding.tvStatusLabel.setText("Status: Running");
                addLog("IP rotation completed");
            }
        });
    }

    /**
     * Add a log message to the log view.
     * @param message Log message
     */
    private void addLog(String message) {
        String timestamp = Logger.formatTime(System.currentTimeMillis());
        String logMessage = timestamp + " | " + message + "\n";

        runOnUiThread(() -> {
            binding.tvLogs.append(logMessage);

            // Find the logs NestedScrollView by ID
            NestedScrollView logsScrollView = findViewById(R.id.logsScrollView);
            if (logsScrollView != null) {
                // Post to ensure it happens after layout is complete
                logsScrollView.post(() -> logsScrollView.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    /**
     * Format time in milliseconds to human-readable string.
     * @param timeMs Time in milliseconds
     * @return Formatted time string (HH:MM:SS)
     */
    private String formatTime(long timeMs) {
        long seconds = (timeMs / 1000) % 60;
        long minutes = (timeMs / (1000 * 60)) % 60;
        long hours = (timeMs / (1000 * 60 * 60));

        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    /**
     * Clear all logs.
     */
    private void clearLogs() {
        binding.tvLogs.setText("");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.d("MainActivity", "onNewIntent called with action: " + (intent != null ? intent.getAction() : "null"));
        
        if (intent != null) {
            boolean autoStart = intent.getBooleanExtra("auto_start", false);
            String customUrl = intent.getStringExtra("custom_url");
            int iterations = intent.getIntExtra("iterations", -1);
            
            Log.d("MainActivity", "onNewIntent received with autoStart=" + autoStart + 
                  ", customUrl=" + customUrl + ", iterations=" + iterations);
                  
            if (autoStart) {
                Log.d("MainActivity", "Auto-starting simulation from onNewIntent");
                
                // If custom URL provided, set it
                if (customUrl != null && !customUrl.isEmpty()) {
                    binding.etTargetUrl.setText(customUrl);
                }
                
                // If iterations provided, set them
                if (iterations > 0) {
                    binding.etIterations.setText(String.valueOf(iterations));
                }
                
                // Start the simulation
                startSimulation();
            }
            
            if (com.example.imtbf.remote.CommandExecutor.ACTION_REMOTE_COMMAND.equals(intent.getAction())) {
                String command = intent.getStringExtra(com.example.imtbf.remote.CommandExecutor.EXTRA_COMMAND);
                Log.d("MainActivity", "Remote command received: " + command);
                
                if (com.example.imtbf.remote.CommandExecutor.COMMAND_START.equals(command)) {
                    Log.d("MainActivity", "Starting simulation from remote command");
                    startSimulation();
                }
            }
        }
    }
}