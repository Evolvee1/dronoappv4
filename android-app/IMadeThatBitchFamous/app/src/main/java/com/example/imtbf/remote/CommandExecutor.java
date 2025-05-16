package com.example.imtbf.remote;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.imtbf.presentation.activities.MainActivity;
import com.example.imtbf.utils.Logger;

/**
 * Executes commands received via ADB broadcasts by forwarding them to MainActivity.
 * Uses a singleton pattern to maintain state and provide access from multiple components.
 */
public class CommandExecutor {
    private static final String TAG = "CommandExecutor";
    
    // Command action constants for intent communication with MainActivity
    public static final String ACTION_REMOTE_COMMAND = "com.example.imtbf.REMOTE_COMMAND";
    public static final String EXTRA_COMMAND = "command";
    public static final String COMMAND_START = "start";
    public static final String COMMAND_PAUSE = "pause";
    public static final String COMMAND_RESUME = "resume";
    public static final String COMMAND_STOP = "stop";
    
    // Handler for main thread operations
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    /**
     * Execute a command to start the simulation
     * @param context Application context
     */
    public static void executeStart(Context context) {
        Log.d(TAG, "Executing start command");
        Intent intent = new Intent(context, com.example.imtbf.presentation.activities.MainActivity.class);
        intent.setAction(ACTION_REMOTE_COMMAND);
        intent.putExtra(EXTRA_COMMAND, COMMAND_START);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(intent);
    }
    
    /**
     * Execute a command to pause the simulation
     * @param context Application context
     */
    public static void executePause(Context context) {
        Log.d(TAG, "Executing pause command");
        // Implementation depends on app's pause mechanism
        // This could broadcast to a service, send intent to activity, etc.
    }
    
    /**
     * Execute a command to resume the simulation
     * @param context Application context
     */
    public static void executeResume(Context context) {
        Log.d(TAG, "Executing resume command");
        // Implementation depends on app's resume mechanism
    }
    
    /**
     * Execute a command to stop the simulation
     * @param context Application context
     */
    public static void executeStop(Context context) {
        Log.d(TAG, "Executing stop command");
        // Implementation depends on app's stop mechanism
    }
    
    /**
     * Send a command to MainActivity via an intent
     * 
     * @param context Application context
     * @param command Command to send
     */
    private static void sendCommandToActivity(Context context, String command) {
        try {
            // Create an explicit intent for MainActivity
            Intent commandIntent = new Intent(context, MainActivity.class);
            commandIntent.setAction(ACTION_REMOTE_COMMAND);
            commandIntent.putExtra(EXTRA_COMMAND, command);
            
            // Add flags to make sure we handle the intent properly whether 
            // MainActivity is already running or not
            commandIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            commandIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            
            // Start MainActivity with the command
            context.startActivity(commandIntent);
            
            Logger.i(TAG, "Command intent sent to MainActivity: " + command);
        } catch (Exception e) {
            Logger.e(TAG, "Error sending command to MainActivity", e);
        }
    }
    
    /**
     * Alternative method that sends a broadcast directly to the package
     * This avoids background execution restrictions on some Android versions
     * 
     * @param context Application context
     * @param action The intent action
     * @param command The command name
     * @param extras Additional extras to include
     */
    public static void sendDirectBroadcast(Context context, String action, String command, Bundle extras) {
        try {
            Intent intent = new Intent(action);
            intent.setPackage(context.getPackageName());
            intent.putExtra("command", command);
            
            if (extras != null) {
                intent.putExtras(extras);
            }
            
            // Set flag to make it explicit this is from the same app
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            
            // Send the broadcast
            context.sendBroadcast(intent);
            Logger.i(TAG, "Direct broadcast sent: " + action + ", command: " + command);
        } catch (Exception e) {
            Logger.e(TAG, "Error sending direct broadcast", e);
        }
    }
} 