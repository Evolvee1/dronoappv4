package com.example.imtbf.domain.simulation;

import android.content.Context;

import com.example.imtbf.data.models.DeviceProfile;
import com.example.imtbf.data.models.SimulationSession;
import com.example.imtbf.data.models.UserAgentData;
import com.example.imtbf.data.network.HttpRequestManager;
import com.example.imtbf.data.network.WebViewRequestManager;
import com.example.imtbf.data.network.NetworkStateMonitor;
import com.example.imtbf.domain.system.AirplaneModeController;
import com.example.imtbf.utils.Logger;
import com.example.imtbf.InstagramTrafficSimulatorApp;

import android.content.Context;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Manages simulation sessions.
 * This class is responsible for orchestrating the various components involved in a simulation.
 */
public class SessionManager {

    private static final String TAG = "SessionManager";

    private final Context context;
    private final NetworkStateMonitor networkStateMonitor;
    private final HttpRequestManager httpRequestManager;
    private final WebViewRequestManager webViewRequestManager;
    private final AirplaneModeController airplaneModeController;
    private final BehaviorEngine behaviorEngine;
    private final TimingDistributor timingDistributor;
    private final Set<String> usedIpAddresses = new HashSet<>();

    private SimulationSession currentSession;
    private boolean isRunning = false;
    private ProgressListener progressListener;
    private long lastRequestTime = 0;

    // For scheduled requests
    private boolean scheduledRequestInProgress = false;
    private String scheduledTargetUrl = null;
    private DeviceProfile scheduledDeviceProfile = null;

    /**
     * Constructor that initializes the session manager with required dependencies.
     * @param context Application context
     * @param networkStateMonitor Network state monitor
     * @param httpRequestManager HTTP request manager
     * @param webViewRequestManager WebView request manager
     * @param airplaneModeController Airplane mode controller
     * @param behaviorEngine Behavior engine
     * @param timingDistributor Timing distributor
     */
    public SessionManager(
            Context context,
            NetworkStateMonitor networkStateMonitor,
            HttpRequestManager httpRequestManager,
            WebViewRequestManager webViewRequestManager,
            AirplaneModeController airplaneModeController,
            BehaviorEngine behaviorEngine,
            TimingDistributor timingDistributor) {
        this.context = context;
        this.networkStateMonitor = networkStateMonitor;
        this.httpRequestManager = httpRequestManager;
        this.webViewRequestManager = webViewRequestManager;
        this.airplaneModeController = airplaneModeController;
        this.behaviorEngine = behaviorEngine;
        this.timingDistributor = timingDistributor;
    }

    /**
     * Progress listener interface
     */
    public interface ProgressListener {
        void onProgressUpdated(int current, int total);
    }

    /**
     * Set a progress listener
     * @param listener Progress listener
     */
    public void setProgressListener(ProgressListener listener) {
        this.progressListener = listener;
    }

    /**
     * Make a request using either HTTP or WebView and wait for the result.
     * @param url Target URL
     * @param deviceProfile Device profile
     * @param session Current session
     * @param useWebView Whether to use WebView for the request
     */
    private void makeRequestAndWait(
            String url,
            DeviceProfile deviceProfile,
            SimulationSession session,
            boolean useWebView) {

        CompletableFuture<Void> requestFuture = new CompletableFuture<>();
        long currentTime = System.currentTimeMillis();
        String currentIp = networkStateMonitor.getCurrentIpAddress().getValue();

        Logger.d(TAG, "Making " + (useWebView ? "WebView" : "HTTP") + " request to " + url);

        // Add logging for timing between requests
        if (lastRequestTime > 0) {
            long timeSinceLastRequest = currentTime - lastRequestTime;
            Logger.i(TAG, "Time since last request: " + timeSinceLastRequest + "ms");
        }
        lastRequestTime = currentTime;

        if (useWebView && webViewRequestManager != null) {
            // Create WebView-specific callback
            WebViewRequestManager.RequestCallback webViewCallback = new WebViewRequestManager.RequestCallback() {
                @Override
                public void onSuccess(int statusCode, long responseTimeMs) {
                    Logger.i(TAG, "WebView request successful: " + statusCode + " in " + responseTimeMs + "ms");
                    requestFuture.complete(null);
                }

                @Override
                public void onError(String error) {
                    Logger.e(TAG, "WebView request failed: " + error);
                    requestFuture.complete(null);
                }
            };

            webViewRequestManager.makeRequest(url, deviceProfile, session, webViewCallback);
        } else {
            // HTTP request handling (keep your existing code)
            HttpRequestManager.RequestCallback httpCallback = new HttpRequestManager.RequestCallback() {
                @Override
                public void onSuccess(int statusCode, long responseTimeMs) {
                    Logger.i(TAG, "HTTP request successful: " + statusCode + " in " + responseTimeMs + "ms");
                    requestFuture.complete(null);
                }

                @Override
                public void onError(String error) {
                    Logger.e(TAG, "HTTP request failed: " + error);
                    requestFuture.complete(null);
                }
            };

            httpRequestManager.makeRequest(url, deviceProfile, session, httpCallback);
        }

        try {
            // Wait for the request to complete with a timeout
            requestFuture.get(120, TimeUnit.SECONDS); // Longer timeout for behavior simulation
        } catch (Exception e) {
            Logger.e(TAG, "Error waiting for request", e);
        }
    }

    /**
     * Start a new simulation session with WebView support.
     * @param targetUrl Target URL
     * @param iterations Number of iterations
     * @param useRandomDeviceProfile Whether to use random device profiles
     * @param rotateIp Whether to rotate IP addresses
     * @param delayMin Minimum delay between iterations in seconds
     * @param delayMax Maximum delay between iterations in seconds
     * @param useWebView Whether to use WebView for requests
     * @return CompletableFuture that completes when the session is started
     */
    public CompletableFuture<Void> startSession(
            String targetUrl,
            int iterations,
            boolean useRandomDeviceProfile,
            boolean rotateIp,
            int delayMin,
            int delayMax,
            boolean useWebView) {

        if (isRunning) {
            Logger.w(TAG, "Session already running");
            return CompletableFuture.completedFuture(null);
        }

        isRunning = true;
        usedIpAddresses.clear();

        // Update timing distributor with custom delay values
        timingDistributor.setMinIntervalSeconds(delayMin);
        timingDistributor.setMaxIntervalSeconds(delayMax);

        Logger.i(TAG, "Using custom timing: min=" + delayMin + "s, max=" + delayMax + "s");
        Logger.i(TAG, "Using " + (useWebView ? "WebView" : "HTTP") + " mode");

        // Create initial device profile
        DeviceProfile initialDeviceProfile = useRandomDeviceProfile ?
                UserAgentData.getSlovakDemographicProfile() :
                new DeviceProfile.Builder()
                        .deviceType(DeviceProfile.TYPE_MOBILE)
                        .platform(DeviceProfile.PLATFORM_ANDROID)
                        .deviceTier(DeviceProfile.TIER_MID_RANGE)
                        .userAgent(UserAgentData.getRandomUserAgent())
                        .region("slovakia")
                        .build();

        // Create a new session
        currentSession = new SimulationSession(targetUrl, initialDeviceProfile);

        // Start the session loop
        return CompletableFuture.runAsync(() -> {
            try {
                // Initial IP check
                String initialIp = networkStateMonitor.getCurrentIpAddress().getValue();
                if (initialIp != null && !initialIp.isEmpty()) {
                    currentSession.recordIpChange(initialIp);
                    usedIpAddresses.add(initialIp);
                }

                Logger.i(TAG, "Starting simulation session: " +
                        iterations + " iterations, target: " + targetUrl);

                // Run iterations
                for (int i = 0; i < iterations && isRunning; i++) {
                    int currentIteration = i + 1;
                    Logger.i(TAG, "Starting iteration " + currentIteration + "/" + iterations);

                    // Update progress
                    if (progressListener != null) {
                        progressListener.onProgressUpdated(currentIteration, iterations);
                    }

                    // Get current IP
                    String currentIp = networkStateMonitor.getCurrentIpAddress().getValue();

                    // Rotate IP if requested and not the first iteration, or if IP already used
                    if (rotateIp && (i > 0 || usedIpAddresses.contains(currentIp))) {
                        if (usedIpAddresses.contains(currentIp)) {
                            Logger.i(TAG, "IP address already used, forcing rotation");
                        }
                        rotateIpAndWait();
                    }

                    // Final device profile for this iteration
                    final DeviceProfile deviceProfile = useRandomDeviceProfile ?
                            UserAgentData.getSlovakDemographicProfile() :
                            initialDeviceProfile;

                    Logger.d(TAG, "Using device profile: " +
                            deviceProfile.getPlatform() + ", " +
                            deviceProfile.getDeviceType() + ", " +
                            deviceProfile.getDeviceTier());

                    // Make the request - passing the WebView mode parameter
                    makeRequestAndWait(targetUrl, deviceProfile, currentSession, useWebView);

                    // Track IP used
                    String newIp = networkStateMonitor.getCurrentIpAddress().getValue();
                    if (newIp != null && !newIp.isEmpty() && !newIp.equals("Unknown")) {
                        usedIpAddresses.add(newIp);
                    }

                    // Simulate human behavior
                    behaviorEngine.simulateSession(deviceProfile, 50).get(); // 50 = medium content length

                    // Wait for the next iteration if not the last one
                    if (isRunning && i < iterations - 1) {
                        int intervalSeconds = timingDistributor.getHumanLikeIntervalSeconds();
                        Logger.d(TAG, "Waiting " + intervalSeconds +
                                " seconds before next iteration");

                        // Sleep with periodic checks to allow cancellation
                        for (int j = 0; j < intervalSeconds && isRunning; j++) {
                            Thread.sleep(1000); // 1 second
                        }
                    }
                }

                // Complete the session
                if (currentSession != null) {
                    currentSession.completeSession();

                    Logger.i(TAG, "Session completed: " +
                            currentSession.getTotalRequests() + " requests, " +
                            currentSession.getSuccessRate() + "% success rate, " +
                            currentSession.getIpRotationCount() + " IP rotations, " +
                            TimeUnit.MILLISECONDS.toSeconds(currentSession.getDurationMs()) +
                            " seconds duration");
                }

            } catch (Exception e) {
                Logger.e(TAG, "Error in simulation session", e);
            } finally {
                isRunning = false;
            }
        });
    }

    /**
     * Start a new simulation session with custom delay settings (backward compatibility).
     * @param targetUrl Target URL
     * @param iterations Number of iterations
     * @param useRandomDeviceProfile Whether to use random device profiles
     * @param rotateIp Whether to rotate IP addresses
     * @param delayMin Minimum delay between iterations in seconds
     * @param delayMax Maximum delay between iterations in seconds
     * @return CompletableFuture that completes when the session is started
     */
    public CompletableFuture<Void> startSession(
            String targetUrl,
            int iterations,
            boolean useRandomDeviceProfile,
            boolean rotateIp,
            int delayMin,
            int delayMax) {

        // Default to HTTP mode
        return startSession(targetUrl, iterations, useRandomDeviceProfile, rotateIp, delayMin, delayMax, false);
    }

    /**
     * Stop the current session.
     */
    public void stopSession() {
        if (!isRunning) {
            return;
        }

        Logger.i(TAG, "Stopping simulation session");
        isRunning = false;

        if (currentSession != null) {
            currentSession.completeSession();
        }
    }

    /**
     * Rotate the IP address and wait for the change to complete.
     */
    private void rotateIpAndWait() {
        try {
            Logger.d(TAG, "Rotating IP address");

            // Rotate IP and wait for completion
            AirplaneModeController.IpRotationResult result =
                    airplaneModeController.rotateIp().get();

            if (result.isSuccess()) {
                Logger.i(TAG, "IP rotation successful: " +
                        result.getPreviousIp() + " -> " + result.getNewIp());

                // Record the IP change in the session
                if (currentSession != null) {
                    currentSession.recordIpChange(result.getNewIp());
                }

                // Add to used IPs
                if (result.getNewIp() != null && !result.getNewIp().isEmpty()) {
                    usedIpAddresses.add(result.getNewIp());
                }

            } else {
                Logger.w(TAG, "IP rotation failed: " + result.getMessage());
            }

        } catch (Exception e) {
            Logger.e(TAG, "Error rotating IP", e);
        }
    }

    /**
     * Check if a session is currently running.
     * @return True if a session is running, false otherwise
     */
    public boolean isRunning() {
        return isRunning;
    }

    /**
     * Get the current session.
     * @return Current session or null if no session is running
     */
    public SimulationSession getCurrentSession() {
        return currentSession;
    }

    /**
     * Execute a single scheduled request.
     * This is called by the TrafficDistributionManager when using scheduled distribution.
     * @return True if request was initiated successfully
     */
    /**
     * Execute a single scheduled request.
     * This is called by the TrafficDistributionManager when using scheduled distribution.
     * @return True if request was initiated successfully
     */
    public boolean executeScheduledRequest() {
        if (scheduledRequestInProgress || !isRunning()) {
            Logger.w(TAG, "Cannot execute scheduled request: " +
                    (scheduledRequestInProgress ? "Another request in progress" : "Session not running"));
            return false;
        }

        scheduledRequestInProgress = true;

        try {
            // Use the saved target URL and device profile
            String targetUrl = scheduledTargetUrl != null ?
                    scheduledTargetUrl : getCurrentSession().getTargetUrl();

            DeviceProfile deviceProfile = scheduledDeviceProfile != null ?
                    scheduledDeviceProfile : getCurrentSession().getDeviceProfile();

            // Make a request using the same parameters as the session
            boolean useRandomDeviceProfile = deviceProfile == null;

            if (useRandomDeviceProfile) {
                deviceProfile = UserAgentData.getSlovakDemographicProfile();
            }

            // Get current IP
            String currentIp = networkStateMonitor.getCurrentIpAddress().getValue();

            // Make the actual request
            boolean useWebView = webViewRequestManager != null &&
                    (context instanceof Context &&
                            ((InstagramTrafficSimulatorApp)((Context)context).getApplicationContext())
                                    .getPreferencesManager().getUseWebViewMode());

            if (useWebView && webViewRequestManager != null) {
                webViewRequestManager.makeRequest(targetUrl, deviceProfile, currentSession,
                        new WebViewRequestManager.RequestCallback() {
                            @Override
                            public void onSuccess(int statusCode, long responseTimeMs) {
                                Logger.i(TAG, "Scheduled WebView request successful: " +
                                        statusCode + " in " + responseTimeMs + "ms");
                                scheduledRequestInProgress = false;
                            }

                            @Override
                            public void onError(String error) {
                                Logger.e(TAG, "Scheduled WebView request failed: " + error);
                                scheduledRequestInProgress = false;
                            }
                        });
            } else {
                // Fallback to HTTP request
                httpRequestManager.makeRequest(targetUrl, deviceProfile, currentSession,
                        new HttpRequestManager.RequestCallback() {
                            @Override
                            public void onSuccess(int statusCode, long responseTimeMs) {
                                Logger.i(TAG, "Scheduled HTTP request successful: " +
                                        statusCode + " in " + responseTimeMs + "ms");
                                scheduledRequestInProgress = false;
                            }

                            @Override
                            public void onError(String error) {
                                Logger.e(TAG, "Scheduled HTTP request failed: " + error);
                                scheduledRequestInProgress = false;
                            }
                        });
            }

            return true;
        } catch (Exception e) {
            Logger.e(TAG, "Error executing scheduled request: " + e.getMessage());
            scheduledRequestInProgress = false;
            return false;
        }
    }

    /**
     * Configure parameters for scheduled requests.
     * @param targetUrl Target URL for scheduled requests
     * @param deviceProfile Device profile for scheduled requests
     */
    public void configureScheduledRequests(String targetUrl, DeviceProfile deviceProfile) {
        this.scheduledTargetUrl = targetUrl;
        this.scheduledDeviceProfile = deviceProfile;
        Logger.d(TAG, "Configured scheduled requests: URL=" + targetUrl);
    }
}