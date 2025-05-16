import logging
import subprocess
import asyncio
import json
import tempfile
import os
import time
import platform
import re
from typing import List, Dict, Optional, Tuple, Any
from datetime import datetime

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s"
)
logger = logging.getLogger(__name__)

class AdbController:
    """Controller for interacting with Android devices via ADB"""
    
    def __init__(self):
        """Initialize the ADB controller and check if ADB is available"""
        self._check_adb()
        self.package = "com.example.imtbf.debug"
        self.activity = "com.example.imtbf.presentation.activities.MainActivity"
        self.broadcast_action = "com.example.imtbf.debug.COMMAND"
        self.prefs_file = f"/data/data/{self.package}/shared_prefs/instagram_traffic_simulator_prefs.xml"
        self.device_status_cache = {}  # Cache for device status
        self.last_status_update = {}   # Track when status was last updated
        self.last_full_check = {}      # Track when last full check was performed
        self.last_broadcasted_status = {}  # Cache previous broadcasts to detect changes
        self.CACHE_TTL = 5.0           # Cache TTL increased to 5 seconds (from 1 second)
        self.FULL_CHECK_TTL = 15.0     # Full check every 15 seconds
        logger.info("ADB Controller initialized in ROOT MODE")
        
    def _check_adb(self):
        """Check if ADB is available"""
        try:
            result = subprocess.run(['adb', 'version'], capture_output=True, text=True, check=True)
            logger.info(f"ADB version: {result.stdout.strip()}")
        except Exception as e:
            logger.error(f"Failed to check ADB: {e}")
            raise RuntimeError("ADB is not installed or not in PATH")
    
    def _escape_shell_string(self, text: str) -> str:
        """Escape a string for shell command usage"""
        if not text:
            return ""
        return text.replace("&", "\\&").replace("?", "\\?").replace(" ", "\\ ").replace("'", "\\'").replace('"', '\\"')
    
    def _escape_xml_string(self, text: str) -> str:
        """Escape a string for XML content"""
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;").replace("'", "&apos;")
            
    def get_devices(self) -> List[Dict]:
        """Get list of connected devices using ADB"""
        try:
            output = subprocess.run(
                ['adb', 'devices', '-l'], 
                capture_output=True, 
                text=True, 
                check=True
            ).stdout
            
            devices = []
            
            # Parse the output
            for line in output.splitlines()[1:]:  # Skip the first line (header)
                if not line.strip():
                    continue
                    
                parts = line.split()
                if len(parts) >= 2:
                    device_id = parts[0]
                    status = parts[1]
                    
                    # Skip devices not in device state
                    if status != "device":
                        continue
                    
                    # Get device model
                    try:
                        model = subprocess.run(
                            ['adb', '-s', device_id, 'shell', 'getprop', 'ro.product.model'],
                            capture_output=True, 
                            text=True, 
                            check=True
                        ).stdout.strip()
                    except Exception:
                        model = "Unknown"
                        
                    # Get battery level
                    try:
                        battery_output = subprocess.run(
                            ['adb', '-s', device_id, 'shell', 'dumpsys', 'battery', '|', 'grep', 'level'],
                            capture_output=True, 
                            text=True, 
                            check=True
                        ).stdout.strip()
                        
                        battery = battery_output.split(':')[1].strip() + '%'
                    except Exception:
                        battery = "Unknown"
                    
                    # We're using root mode for all devices
                    device_info = {
                        'id': device_id,
                        'model': model,
                        'status': 'online',
                        'battery': battery,
                        'has_write_access': True,  # Always true in root mode
                        'has_root_access': True    # Always true in root mode
                    }
                    
                    devices.append(device_info)
            
            return devices
        except Exception as e:
            logger.error(f"Failed to get devices: {e}")
            return []

    def _create_prefs_xml(self, url: str, iterations: int, min_interval: int, max_interval: int, 
                         use_webview: bool = True, rotate_ip: bool = True,
                         delay_min: int = 0, delay_max: int = 0) -> Tuple[str, str]:
        """Create the XML content for preferences files"""
        timestamp = int(datetime.now().timestamp() * 1000)
        xml_escaped_url = self._escape_xml_string(url)
        
        # Create main preferences XML
        prefs_xml = f"""<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="use_webview_mode" value="{str(use_webview).lower()}" />
    <string name="current_session_id">session-{timestamp}</string>
    <string name="target_url">{xml_escaped_url}</string>
    <int name="iterations" value="{iterations}" />
    <int name="min_interval" value="{min_interval}" />
    <int name="max_interval" value="{max_interval}" />
    <int name="delay_min" value="{delay_min}" />
    <int name="delay_max" value="{delay_max}" />
    <boolean name="rotate_ip" value="{str(rotate_ip).lower()}" />
    <boolean name="use_random_device_profile" value="true" />
    <boolean name="new_webview_per_request" value="true" />
    <long name="last_run_timestamp" value="{timestamp}" />
    <boolean name="is_first_run" value="false" />
</map>"""

        # Create URL config XML
        url_config_xml = f"""<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="saved_url">{xml_escaped_url}</string>
    <long name="last_saved_timestamp" value="{timestamp}" />
</map>"""

        return prefs_xml, url_config_xml
    
    def _apply_settings_root_method(self, device_id: str, url: str, iterations: int, 
                                   min_interval: int, max_interval: int, 
                                   use_webview: bool = True, rotate_ip: bool = True,
                                   delay_min: int = 0, delay_max: int = 0) -> bool:
        """Apply settings using root access method"""
        logger.info(f"Applying settings to device {device_id} using ROOT MODE")
        
        try:
            # Generate XML content
            prefs_xml, url_config_xml = self._create_prefs_xml(
                url, iterations, min_interval, max_interval, 
                use_webview, rotate_ip, delay_min, delay_max
            )
            
            # Create temporary files
            with tempfile.NamedTemporaryFile(suffix="_prefs.xml", delete=False) as prefs_file, \
                 tempfile.NamedTemporaryFile(suffix="_url_config.xml", delete=False) as url_file:
                 
                prefs_filename = prefs_file.name
                url_filename = url_file.name
                
                prefs_file.write(prefs_xml.encode('utf-8'))
                url_file.write(url_config_xml.encode('utf-8'))
            
            # Get base filenames
            prefs_base = os.path.basename(prefs_filename)
            url_base = os.path.basename(url_filename)
            
            # Push to device
            subprocess.run(['adb', '-s', device_id, 'push', prefs_filename, f"/sdcard/{prefs_base}"], check=True)
            subprocess.run(['adb', '-s', device_id, 'push', url_filename, f"/sdcard/{url_base}"], check=True)
            
            # Copy with root 
            # Ensure the app's shared_prefs directory exists
            subprocess.run(['adb', '-s', device_id, 'shell', 
                           f"su -c 'mkdir -p /data/data/{self.package}/shared_prefs/'"], check=True)
            
            # Copy the preferences files
            subprocess.run(['adb', '-s', device_id, 'shell', 
                           f"su -c 'cp /sdcard/{prefs_base} {self.prefs_file}'"], check=True)
            
            # Set appropriate permissions - handle failures gracefully
            subprocess.run(['adb', '-s', device_id, 'shell', 
                           f"su -c 'chmod 660 {self.prefs_file}'"], check=True)
            
            # Try to set ownership, but don't fail if it doesn't work
            try:
                subprocess.run(['adb', '-s', device_id, 'shell', 
                               f"su -c 'chown {self.package}:{self.package} {self.prefs_file}'"], check=True)
            except Exception as e:
                logger.warning(f"Could not set ownership on preferences file: {e}")
                # Continue anyway, as the app might still be able to read the file
            
            # Same for URL config
            subprocess.run(['adb', '-s', device_id, 'shell', 
                           f"su -c 'cp /sdcard/{url_base} /data/data/{self.package}/shared_prefs/url_config.xml'"], check=True)
            subprocess.run(['adb', '-s', device_id, 'shell', 
                           f"su -c 'chmod 660 /data/data/{self.package}/shared_prefs/url_config.xml'"], check=True)
            
            # Try to set ownership, but don't fail if it doesn't work
            try:
                subprocess.run(['adb', '-s', device_id, 'shell', 
                               f"su -c 'chown {self.package}:{self.package} /data/data/{self.package}/shared_prefs/url_config.xml'"], check=True)
            except Exception as e:
                logger.warning(f"Could not set ownership on URL config file: {e}")
                # Continue anyway, as the app might still be able to read the file
            
            # Clean up
            os.unlink(prefs_filename)
            os.unlink(url_filename)
            subprocess.run(['adb', '-s', device_id, 'shell', f"rm /sdcard/{prefs_base}"], capture_output=True)
            subprocess.run(['adb', '-s', device_id, 'shell', f"rm /sdcard/{url_base}"], capture_output=True)
            
            # Verify settings
            prefs_check = subprocess.run(['adb', '-s', device_id, 'shell', 
                                         f"su -c 'cat {self.prefs_file}'"], 
                                        capture_output=True, text=True).stdout
            
            success = 'target_url' in prefs_check and (url in prefs_check or self._escape_xml_string(url) in prefs_check)
            if success:
                logger.info(f"Successfully verified settings for device {device_id}")
            else:
                logger.warning(f"Could not verify settings for device {device_id}")
            
            return success
        except Exception as e:
            logger.error(f"Error applying settings with root method: {e}")
            return False

    def distribute_url(self, device_ids: List[str], url: str, iterations: int = 100,
                       min_interval: int = 1, max_interval: int = 2,
                       use_webview: bool = True, rotate_ip: bool = True,
                       delay_min: int = 0, delay_max: int = 0) -> Dict[str, Dict]:
        """
        Distribute a URL to multiple devices
        
        Args:
            device_ids: List of device IDs 
            url: URL to distribute
            iterations: Number of iterations
            min_interval: Minimum interval (seconds)
            max_interval: Maximum interval (seconds)
            use_webview: Whether to use WebView mode
            rotate_ip: Whether to rotate IP
            delay_min: Minimum delay before starting (seconds)
            delay_max: Maximum delay before starting (seconds)
            
        Returns:
            Dictionary with results for each device
        """
        if not device_ids:
            available_devices = self.get_devices()
            device_ids = [d['id'] for d in available_devices]
            
        if not device_ids:
            return {"error": "No devices available"}
        
        results = {}
        
        for device_id in device_ids:
            logger.info(f"Distributing URL {url} to device {device_id}")
            success = False
            
            # First, kill the app to ensure a clean start
            logger.info(f"Force stopping app on device {device_id}")
            try:
                subprocess.run(['adb', '-s', device_id, 'shell', f"am force-stop {self.package}"], capture_output=True)
                # Wait a moment for the app to fully stop
                time.sleep(1)
            except Exception as e:
                logger.error(f"Error force-stopping app: {e}")
            
            # Try the direct auto_start method first, which worked in manual testing
            logger.info(f"Trying direct auto_start method for device {device_id}")
            
            # Escape URL for command line safety
            escaped_url = self._escape_shell_string(url)
            
            auto_start_cmd = [
                'adb', '-s', device_id, 'shell',
                f"am start -n {self.package}/{self.activity}" +
                f" --es custom_url {escaped_url}" +
                f" --ei iterations {iterations}" +
                f" --ei min_interval {min_interval}" +
                f" --ei max_interval {max_interval}" +
                f" --ei delay_min {delay_min}" +
                f" --ei delay_max {delay_max}" +
                f" --ez auto_start true"
            ]
            
            try:
                result = subprocess.run(auto_start_cmd, capture_output=True, text=True)
                logger.info(f"Auto start result: {result.stdout}")
                if "Starting" in result.stdout:
                    logger.info(f"Auto start command sent successfully to device {device_id}")
                    success = True
                    results[device_id] = {
                        "success": True,
                        "settings_method": "auto_start",
                        "message": "Started with auto_start flag",
                        "url": url,
                        "iterations": iterations,
                        "min_interval": min_interval,
                        "max_interval": max_interval,
                        "delay_min": delay_min,
                        "delay_max": delay_max
                    }
                    continue
            except Exception as e:
                logger.error(f"Error sending auto start command: {e}")
            
            # If auto_start didn't work, continue with the regular approach
            try:
                escaped_url = url.replace("&", "\\&").replace("?", "\\?")
                
                # Step 1: Force stop the app
                logger.info(f"Force stopping app on device {device_id}")
                subprocess.run(['adb', '-s', device_id, 'shell', f"am force-stop {self.package}"], capture_output=True)
                
                # Step 2: Apply settings using ROOT MODE
                settings_applied = self._apply_settings_root_method(
                    device_id, url, iterations, min_interval, max_interval, use_webview, rotate_ip, delay_min, delay_max
                )
                
                if not settings_applied:
                    logger.warning(f"Failed to apply settings using ROOT method for device {device_id}")
                    
                # Step 3: Start the app with ACTION_START_SIMULATION intent including all parameters
                logger.info(f"Starting app on device {device_id} with ACTION_START_SIMULATION intent")
                start_cmd = [
                    'adb', '-s', device_id, 'shell',
                    f"am start -a com.example.imtbf.START_SIMULATION -n {self.package}/{self.activity} --es custom_url {escaped_url} --ei iterations {iterations} --ei min_interval {min_interval} --ei max_interval {max_interval} --ei delay_min {delay_min} --ei delay_max {delay_max} --ez load_images {str(use_webview).lower()} --ez javascript true --ez cookies true --ez dom_storage true"
                ]
                
                subprocess.run(start_cmd, capture_output=True, text=True)
                # Wait for app to fully start by polling for the process
                logger.info(f"Waiting for app process to start on device {device_id}")
                process_started = False
                process_id = ""
                for _ in range(20):  # 20 * 0.5s = 10 seconds max
                    process_id = subprocess.run(
                        ['adb', '-s', device_id, 'shell', f"pidof {self.package}"],
                        capture_output=True,
                        text=True
                    ).stdout.strip()
                    
                    if process_id:
                        process_started = True
                        logger.info(f"App process started on device {device_id} with PID {process_id}")
                        # Add a longer delay here to ensure the app is fully initialized
                        logger.info(f"Waiting 5 seconds for app to fully initialize on device {device_id}")
                        time.sleep(5)
                        break
                    time.sleep(0.5)
                
                if not process_started:
                    logger.warning(f"App process did not start on device {device_id} within timeout")
                    results[device_id] = {
                        "success": False,
                        "message": "App process did not start within timeout",
                        "url": url
                    }
                    continue
                
                # Add additional delay before sending start command
                time.sleep(1)
                
                # First, try using the direct broadcast command that worked in manual testing
                logger.info(f"Sending direct start broadcast command to device {device_id}")
                direct_start_cmd = [
                    'adb', '-s', device_id, 'shell',
                    f"am broadcast -a com.example.imtbf.debug.COMMAND --es command start -p {self.package}"
                ]
                
                try:
                    broadcast_result = subprocess.run(direct_start_cmd, capture_output=True, text=True)
                    logger.info(f"Direct start broadcast result: {broadcast_result.stdout}")
                    if "Broadcast completed" in broadcast_result.stdout:
                        logger.info(f"Direct start broadcast command sent successfully to device {device_id}")
                        success = True
                    else:
                        # Continue with other methods if direct broadcast didn't work
                        logger.info(f"Sending broadcast intents to device {device_id}")
                        
                        # Method 2: Send additional broadcasts to ensure settings are applied
                        self._send_broadcast_commands(device_id, url, iterations, min_interval, max_interval, delay_min, delay_max)
                        
                        # Method 3: Try to start the activity directly with custom URL
                        self._start_activity_with_url(device_id, url, iterations, min_interval, max_interval, delay_min, delay_max)
                        
                        # Get current URL if possible
                        current_url = self._get_current_url(device_id)
                except Exception as e:
                    logger.error(f"Error sending direct start broadcast: {e}")
                
                results[device_id] = {
                    "success": process_started,
                    "settings_method": "root",
                    "message": f"App is running with PID: {process_id}" if process_id else "Failed to start app",
                    "url": url,
                    "current_url": current_url if 'current_url' in locals() else "",
                    "iterations": iterations,
                    "min_interval": min_interval,
                    "max_interval": max_interval,
                    "delay_min": delay_min,
                    "delay_max": delay_max
                }
                
            except Exception as e:
                logger.error(f"Failed to distribute URL to device {device_id}: {e}")
                results[device_id] = {
                    "success": False,
                    "error": str(e)
                }
        
        return results

    async def execute_command(self, device_id: str, command: str, params: Dict = None) -> Dict:
        """
        Execute a command on a device
        
        Args:
            device_id: Device ID
            command: Command to execute (start, stop, pause, resume)
            params: Command parameters
            
        Returns:
            Dictionary with command result
        """
        params = params or {}
        
        if command == "start":
            # For start command, call the distribute_url with single device ID
            url = params.get("url", "https://example.com")
            iterations = params.get("iterations", 100)
            min_interval = params.get("min_interval", 1)
            max_interval = params.get("max_interval", 2)
            delay_min = params.get("delay_min", 0)
            delay_max = params.get("delay_max", 0)
            use_webview = params.get("use_webview", True)
            rotate_ip = params.get("rotate_ip", True)
            
            # Use the distribute_url method to ensure best compatibility
            result = self.distribute_url(
                [device_id], url, iterations, min_interval, max_interval, 
                use_webview, rotate_ip, delay_min, delay_max
            )
            
            return result
                
        elif command == "stop":
            # Stop app
            try:
                stop_cmd = [
                    'adb', '-s', device_id, 'shell',
                    f"am force-stop {self.package}"
                ]
                
                process = await asyncio.create_subprocess_exec(
                    *stop_cmd,
                    stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.PIPE
                )
                
                stdout, stderr = await process.communicate()
                
                return {
                    "success": process.returncode == 0,
                    "command": command,
                    "device_id": device_id,
                    "stdout": stdout.decode('utf-8', errors='replace'),
                    "stderr": stderr.decode('utf-8', errors='replace')
                }
                
            except Exception as e:
                logger.error(f"Failed to execute command {command} on device {device_id}: {e}")
                return {
                    "success": False,
                    "error": str(e),
                    "command": command,
                    "device_id": device_id
                }
                
        else:
            # Other commands send via broadcast
            action_map = {
                "pause": "pause",
                "resume": "resume",
                "reload": "reload_url"
            }
            
            action = action_map.get(command, command)
            
            try:
                # Build command with any parameters
                broadcast_params = ""
                for key, value in params.items():
                    if isinstance(value, bool):
                        broadcast_params += f" --ez {key} {str(value).lower()}"
                    elif isinstance(value, int):
                        broadcast_params += f" --ei {key} {value}"
                    else:
                        # Escape string values
                        escaped_value = self._escape_shell_string(str(value))
                        broadcast_params += f" --es {key} {escaped_value}"
                
                broadcast_cmd = [
                    'adb', '-s', device_id, 'shell',
                    f"am broadcast -a {self.package}.COMMAND --es command {action}{broadcast_params} -p {self.package}"
                ]
                
                process = await asyncio.create_subprocess_exec(
                    *broadcast_cmd,
                    stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.PIPE
                )
                
                stdout, stderr = await process.communicate()
                
                return {
                    "success": process.returncode == 0,
                    "command": command,
                    "device_id": device_id,
                    "stdout": stdout.decode('utf-8', errors='replace'),
                    "stderr": stderr.decode('utf-8', errors='replace')
                }
                
            except Exception as e:
                logger.error(f"Failed to execute command {command} on device {device_id}: {e}")
                return {
                    "success": False,
                    "error": str(e),
                    "command": command,
                    "device_id": device_id
                }

    def get_device_status(self, device_id: str, full_check: bool = False) -> Dict:
        """
        Get detailed status information from a device
        
        Args:
            device_id: Device ID
            full_check: Whether to perform a full check of all attributes
            
        Returns:
            Dictionary with device status information
        """
        # Check if we should use cached data
        current_time = time.time()
        last_full_check_time = self.last_full_check.get(device_id, 0)
        do_full_check = full_check or current_time - last_full_check_time >= self.FULL_CHECK_TTL
        
        # If we don't need a full check and we have recent cache data, return it
        if (not do_full_check and 
            device_id in self.last_status_update and 
            current_time - self.last_status_update.get(device_id, 0) < self.CACHE_TTL and
            device_id in self.device_status_cache):
            return self.device_status_cache[device_id]
            
        # For quick checks, just log at debug level to reduce log spam
        if not do_full_check:
            logger.debug(f"Quick status check for device {device_id}")
        else:
            logger.info(f"Fetching full status for device {device_id}")
        
        # Use existing cache as starting point if available, or create new status info
        status_info = self.device_status_cache.get(device_id, {
            "device_id": device_id,
            "is_running": False,
            "current_iteration": 0,
            "total_iterations": 0,
            "percentage": 0,
            "url": "",
            "min_interval": 0,
            "max_interval": 0,
            "delay_min": 1,
            "delay_max": 2,
            "elapsed_time": 0,
            "estimated_remaining": 0,
            "status": "idle",
            "last_update": datetime.now().isoformat()
        })
        
        try:
            # Quick check - just verify if process is running (always do this)
            process_id = subprocess.run(
                ['adb', '-s', device_id, 'shell', f"pidof {self.package}"],
                capture_output=True,
                text=True,
                timeout=2  # Shorter timeout for quick check
            ).stdout.strip()
            
            if not process_id:
                status_info["status"] = "stopped"
                status_info["is_running"] = False
                self.device_status_cache[device_id] = status_info
                self.last_status_update[device_id] = current_time
                return status_info
            
            # If process is running but we don't need full check, just return cached data with updated is_running
            if not do_full_check:
                status_info["is_running"] = True
                if status_info["status"] != "paused":  # Preserve paused state
                    status_info["status"] = "running"
                self.device_status_cache[device_id] = status_info
                self.last_status_update[device_id] = current_time
                return status_info
            
            # Full check - similar to original implementation but only when needed
            
            # Method 1: Get from shared preferences file using root
            prefs_data = self._get_prefs_from_device(device_id)
            if prefs_data:
                # Extract values from prefs XML
                is_running_match = re.search(r'<boolean name="is_running" value="([^"]+)"', prefs_data)
                iterations_match = re.search(r'<int name="iterations" value="([^"]+)"', prefs_data)
                current_iter_match = re.search(r'<int name="current_iteration" value="([^"]+)"', prefs_data)
                url_match = re.search(r'<string name="target_url">([^<]+)</string>', prefs_data)
                min_interval_match = re.search(r'<int name="min_interval" value="([^"]+)"', prefs_data)
                max_interval_match = re.search(r'<int name="max_interval" value="([^"]+)"', prefs_data)
                delay_min_match = re.search(r'<int name="delay_min" value="([^"]+)"', prefs_data)
                delay_max_match = re.search(r'<int name="delay_max" value="([^"]+)"', prefs_data)
                start_time_match = re.search(r'<long name="simulation_start_time" value="([^"]+)"', prefs_data)
                simulation_paused_match = re.search(r'<boolean name="simulation_paused" value="([^"]+)"', prefs_data)
                
                # Update status with extracted values
                if is_running_match:
                    status_info["is_running"] = is_running_match.group(1).lower() == "true"
                
                if simulation_paused_match:
                    is_paused = simulation_paused_match.group(1).lower() == "true"
                    if is_paused:
                        status_info["status"] = "paused"
                    elif status_info["is_running"]:
                        status_info["status"] = "running"
                elif status_info["is_running"]:
                    status_info["status"] = "running"
                
                if iterations_match:
                    status_info["total_iterations"] = int(iterations_match.group(1))
                
                if current_iter_match:
                    status_info["current_iteration"] = int(current_iter_match.group(1))
                
                if url_match:
                    status_info["url"] = url_match.group(1)
                
                if min_interval_match:
                    status_info["min_interval"] = int(min_interval_match.group(1))
                
                if max_interval_match:
                    status_info["max_interval"] = int(max_interval_match.group(1))
                
                if delay_min_match:
                    status_info["delay_min"] = int(delay_min_match.group(1))
                
                if delay_max_match:
                    status_info["delay_max"] = int(delay_max_match.group(1))
                
                # Calculate progress percentage
                if status_info["total_iterations"] > 0 and status_info["current_iteration"] > 0:
                    status_info["percentage"] = round(
                        (status_info["current_iteration"] / status_info["total_iterations"]) * 100, 1
                    )
                
                # Calculate elapsed time
                if start_time_match:
                    start_time_ms = int(start_time_match.group(1))
                    elapsed_seconds = (int(time.time() * 1000) - start_time_ms) / 1000
                    status_info["elapsed_time"] = round(elapsed_seconds)
                    
                    # Calculate estimated remaining time
                    if status_info["current_iteration"] > 0 and status_info["is_running"]:
                        time_per_iteration = elapsed_seconds / status_info["current_iteration"]
                        remaining_iterations = status_info["total_iterations"] - status_info["current_iteration"]
                        status_info["estimated_remaining"] = round(time_per_iteration * remaining_iterations)
            
            # Method 2: Try to get status from app internal storage files
            if not status_info["current_iteration"] > 0:
                status_file_data = self._get_status_file_from_device(device_id)
                if status_file_data:
                    try:
                        status_data = json.loads(status_file_data)
                        status_info["current_iteration"] = status_data.get("currentIteration", 0)
                        status_info["total_iterations"] = status_data.get("totalIterations", 0)
                        status_info["is_running"] = status_data.get("isRunning", False)
                        status_info["status"] = "running" if status_data.get("isRunning", False) else "paused"
                        
                        # Update percentage
                        if status_info["total_iterations"] > 0 and status_info["current_iteration"] > 0:
                            status_info["percentage"] = round(
                                (status_info["current_iteration"] / status_info["total_iterations"]) * 100, 1
                            )
                        
                        # Get elapsed time
                        if "startTimeMs" in status_data:
                            start_time = int(status_data["startTimeMs"]) / 1000
                            current_time_ms = int(time.time())
                            status_info["elapsed_time"] = current_time_ms - start_time
                            
                            # Estimate remaining time
                            if status_info["current_iteration"] > 0 and status_info["is_running"]:
                                time_per_iteration = status_info["elapsed_time"] / status_info["current_iteration"]
                                remaining_iterations = status_info["total_iterations"] - status_info["current_iteration"]
                                status_info["estimated_remaining"] = round(time_per_iteration * remaining_iterations)
                    except Exception as e:
                        logger.debug(f"Error parsing status file: {e}")
            
            # Check logcat for progress information (only if we still don't have progress info)
            if not status_info["current_iteration"] > 0 and status_info["is_running"]:
                try:
                    # Use logcat to find progress information
                    logcat_output = subprocess.run(
                        ['adb', '-s', device_id, 'logcat', '-d', '-t', '20', '-v', 'brief'],
                        capture_output=True,
                        text=True,
                        timeout=3
                    ).stdout
                    
                    # Look for progress information in logcat
                    for line in logcat_output.splitlines():
                        if self.package in line:
                            # Try to find iteration info
                            iter_match = re.search(r'Iteration:\s*(\d+)/(\d+)', line)
                            if iter_match:
                                current_iter = int(iter_match.group(1))
                                total_iter = int(iter_match.group(2))
                                
                                if current_iter > 0 and total_iter > 0:
                                    status_info["current_iteration"] = current_iter
                                    status_info["total_iterations"] = total_iter
                                    status_info["percentage"] = round((current_iter / total_iter) * 100, 1)
                                    break
                except Exception as e:
                    logger.debug(f"Error getting progress from logcat: {e}")
            
            # Update cache and tracking timestamps
            self.device_status_cache[device_id] = status_info
            self.last_status_update[device_id] = current_time
            self.last_full_check[device_id] = current_time
            return status_info
            
        except Exception as e:
            logger.error(f"Failed to get device status: {e}")
            return status_info
    
    def _get_prefs_from_device(self, device_id: str) -> str:
        """Get XML preferences data from device"""
        try:
            prefs_data = subprocess.run(
                ['adb', '-s', device_id, 'shell', f"su -c 'cat {self.prefs_file}'"],
                capture_output=True,
                text=True,
                timeout=5
            ).stdout
            return prefs_data
        except Exception as e:
            logger.debug(f"Error reading preferences file: {e}")
            return ""
    
    def _get_status_file_from_device(self, device_id: str) -> str:
        """Get status file data from device"""
        try:
            # Try to read the status.json file from internal storage
            status_file = f"/data/data/{self.package}/files/status.json"
            status_data = subprocess.run(
                ['adb', '-s', device_id, 'shell', f"su -c 'cat {status_file}'"],
                capture_output=True,
                text=True,
                timeout=5
            ).stdout
            return status_data
        except Exception as e:
            logger.debug(f"Error reading status file: {e}")
            return ""
            
    async def get_all_devices_status(self) -> Dict[str, Dict]:
        """
        Get status for all connected devices more efficiently using concurrent processing
        
        Returns:
            Dictionary mapping device IDs to status information
        """
        devices = self.get_devices()
        results = {}
        
        if not devices:
            return results
            
        # Use semaphore to limit concurrent ADB operations
        semaphore = asyncio.Semaphore(3)  # Max 3 concurrent operations
        
        async def get_status_with_semaphore(device_id):
            """Helper function to get status with semaphore limit"""
            async with semaphore:
                return device_id, self.get_device_status(device_id)
        
        # Create tasks for all devices
        tasks = []
        for device in devices:
            device_id = device["id"]
            tasks.append(get_status_with_semaphore(device_id))
        
        # Run all tasks concurrently and gather results
        if tasks:
            device_statuses = await asyncio.gather(*tasks)
            results = {device_id: status for device_id, status in device_statuses}
            
        return results

    def get_device_timing_info(self, device_id: str) -> Dict:
        """
        Get detailed timing information from a device including actualElapsedMs and remainingTime
        
        Args:
            device_id: Device ID
            
        Returns:
            Dictionary with detailed timing information
        """
        timing_info = {
            "device_id": device_id,
            "actual_elapsed_ms": 0,
            "remaining_time_ms": 0,
            "current_iteration": 0,
            "total_iterations": 0,
            "success": False
        }
        
        try:
            # First try to get timing information using a custom command
            # This command will dump the internal timing state to logcat
            dump_cmd = [
                'shell', 
                f'am broadcast -a {self.broadcast_action} --es command get_detailed_status -p {self.package}'
            ]
            
            # Execute the command
            subprocess.run(['adb', '-s', device_id] + dump_cmd, capture_output=True, timeout=5)
            
            # Wait a moment for the command to be processed
            time.sleep(0.5)
            
            # Get the logcat output with timing information
            logcat_cmd = [
                'shell',
                'logcat -d -v brief -t 100 MainActivity:D AdbCommandReceiver:I | grep -E "Time elapsed|remaining|Progress"'
            ]
            
            result = subprocess.run(['adb', '-s', device_id] + logcat_cmd, capture_output=True, text=True, timeout=5)
            logcat_output = result.stdout
            
            # Extract timing information from logcat
            elapsed_match = re.search(r'Time elapsed: (\d+:\d+:\d+)', logcat_output)
            remaining_match = re.search(r'Estimated time remaining: (\d+:\d+:\d+)', logcat_output)
            progress_match = re.search(r'Progress: (\d+)/(\d+)', logcat_output)
            
            if elapsed_match:
                # Convert HH:MM:SS to milliseconds
                time_parts = elapsed_match.group(1).split(':')
                hours = int(time_parts[0])
                minutes = int(time_parts[1])
                seconds = int(time_parts[2])
                timing_info["actual_elapsed_ms"] = ((hours * 60 + minutes) * 60 + seconds) * 1000
                timing_info["success"] = True
                
            if remaining_match:
                # Convert HH:MM:SS to milliseconds
                time_parts = remaining_match.group(1).split(':')
                hours = int(time_parts[0])
                minutes = int(time_parts[1])
                seconds = int(time_parts[2])
                timing_info["remaining_time_ms"] = ((hours * 60 + minutes) * 60 + seconds) * 1000
                
            if progress_match:
                timing_info["current_iteration"] = int(progress_match.group(1))
                timing_info["total_iterations"] = int(progress_match.group(2))
                
            # If we couldn't get the information from logcat, try to get it from the status file
            if not timing_info["success"]:
                status_file_data = self._get_status_file_from_device(device_id)
                if status_file_data:
                    try:
                        status_data = json.loads(status_file_data)
                        
                        # Get current iteration and total iterations
                        timing_info["current_iteration"] = status_data.get("currentIteration", 0)
                        timing_info["total_iterations"] = status_data.get("totalIterations", 0)
                        
                        # Calculate elapsed time
                        if "startTimeMs" in status_data:
                            start_time_ms = int(status_data["startTimeMs"])
                            current_time_ms = int(time.time() * 1000)
                            raw_elapsed_ms = current_time_ms - start_time_ms
                            
                            # Account for paused time if available
                            total_paused_ms = status_data.get("totalPausedTimeMs", 0)
                            pause_time_ms = status_data.get("pauseTimeMs", 0)
                            
                            actual_elapsed_ms = raw_elapsed_ms - total_paused_ms
                            if pause_time_ms > 0:
                                actual_elapsed_ms -= (current_time_ms - pause_time_ms)
                                
                            timing_info["actual_elapsed_ms"] = actual_elapsed_ms
                            
                            # Calculate remaining time if we have progress
                            if timing_info["current_iteration"] > 0:
                                avg_time_per_iteration = actual_elapsed_ms / timing_info["current_iteration"]
                                remaining_iterations = timing_info["total_iterations"] - timing_info["current_iteration"]
                                timing_info["remaining_time_ms"] = int(avg_time_per_iteration * remaining_iterations)
                            
                            timing_info["success"] = True
                    except Exception as e:
                        logger.error(f"Error parsing status file: {e}")
                    
            # As a fallback, try to get information from the device status
            if not timing_info["success"]:
                status_info = self.get_device_status(device_id, full_check=True)
                if status_info:
                    timing_info["current_iteration"] = status_info.get("current_iteration", 0)
                    timing_info["total_iterations"] = status_info.get("total_iterations", 0)
                    timing_info["actual_elapsed_ms"] = status_info.get("elapsed_time", 0) * 1000
                    timing_info["remaining_time_ms"] = status_info.get("estimated_remaining", 0) * 1000
                    timing_info["success"] = True
                    
            return timing_info
            
        except Exception as e:
            logger.error(f"Failed to get detailed timing information: {e}")
            return timing_info

    def _send_broadcast_commands(self, device_id, url, iterations, min_interval, max_interval, delay_min, delay_max):
        """Send broadcast commands to configure the app"""
        # First, ensure the app is stopped
        try:
            logger.info(f"Force stopping app before sending broadcasts on device {device_id}")
            subprocess.run(['adb', '-s', device_id, 'shell', f"am force-stop {self.package}"], capture_output=True)
            # Wait a moment for the app to fully stop
            time.sleep(1)
        except Exception as e:
            logger.error(f"Error force-stopping app: {e}")
            
        # Start the app
        try:
            logger.info(f"Starting app on device {device_id}")
            subprocess.run(['adb', '-s', device_id, 'shell', f"am start -n {self.package}/{self.activity}"], capture_output=True)
            # Wait a moment for the app to fully start
            time.sleep(2)
        except Exception as e:
            logger.error(f"Error starting app: {e}")
            
        escaped_url = self._escape_shell_string(url)
        
        # Command broadcast for URL
        subprocess.run([
            'adb', '-s', device_id, 'shell',
            f"am broadcast -a {self.package}.COMMAND --es command set_url --es value {escaped_url} -p {self.package}"
        ], capture_output=True)
        
        # SET_URL action broadcast
        subprocess.run([
            'adb', '-s', device_id, 'shell',
            f"am broadcast -a {self.package}.SET_URL --es url {escaped_url} --ei iterations {iterations} --ei min_interval {min_interval} --ei max_interval {max_interval} --ei delay_min {delay_min} --ei delay_max {delay_max} -p {self.package}"
        ], capture_output=True)
        
        # Send start command
        logger.info(f"Starting simulation on device {device_id}")
        start_result = subprocess.run([
            'adb', '-s', device_id, 'shell',
            f"am broadcast -a {self.package}.COMMAND --es command start -p {self.package}"
        ], capture_output=True)
        
        # For safety, send a final settings update command
        time.sleep(2)
        subprocess.run([
            'adb', '-s', device_id, 'shell',
            f"am broadcast -a {self.package}.COMMAND --es command reload_url --es value {escaped_url} --ei iterations {iterations} --ei min_interval {min_interval} --ei max_interval {max_interval} --ei delay_min {delay_min} --ei delay_max {delay_max} -p {self.package}"
        ], capture_output=True)
    
    def _start_activity_with_url(self, device_id, url, iterations, min_interval, max_interval, delay_min, delay_max):
        """Start the activity with the URL and parameters"""
        logger.info(f"Starting activity on device {device_id} with URL {url} and auto_start flag")
        
        # First, ensure the app is stopped
        try:
            logger.info(f"Force stopping app before starting on device {device_id}")
            subprocess.run(['adb', '-s', device_id, 'shell', f"am force-stop {self.package}"], capture_output=True)
            # Wait a moment for the app to fully stop
            time.sleep(1)
        except Exception as e:
            logger.error(f"Error force-stopping app: {e}")
        
        # Escape URL for command line safety
        escaped_url = self._escape_shell_string(url)
        
        # Build complete command with all parameters including auto_start flag
        start_cmd = [
            'adb', '-s', device_id, 'shell',
            f"am start -n {self.package}/{self.activity}" +
            f" --es custom_url {escaped_url}" +
            f" --ei iterations {iterations}" +
            f" --ei min_interval {min_interval}" +
            f" --ei max_interval {max_interval}" +
            f" --ei delay_min {delay_min}" +
            f" --ei delay_max {delay_max}" +
            f" --ez auto_start true"
        ]
        
        # Execute the command with full error handling
        try:
            result = subprocess.run(start_cmd, capture_output=True, text=True)
            if result.returncode != 0:
                logger.error(f"Error starting activity on device {device_id}. Error: {result.stderr}")
                return False
            
            # Check output for warnings
            if "Warning" in result.stdout or "Error" in result.stdout:
                logger.warning(f"Warning when starting activity: {result.stdout}")
            
            logger.info(f"Activity started successfully on device {device_id}")
            
            # Wait 5 seconds to give the app time to start
            time.sleep(5)
            return True
        except Exception as e:
            logger.error(f"Exception when starting activity: {str(e)}")
            return False
    
    def _get_current_url(self, device_id):
        """Get the current URL from the app's preferences"""
        current_url = "unknown"
        try:
            # Check settings using root
            prefs_check = subprocess.run(['adb', '-s', device_id, 'shell', 
                                       f"su -c 'cat {self.prefs_file}'"], 
                                      capture_output=True, text=True).stdout
                
            if 'target_url' in prefs_check:
                import re
                match = re.search(r'<string name="target_url">(.*?)</string>', prefs_check)
                if match:
                    current_url = match.group(1)
        except Exception:
            pass
        
        return current_url

    async def send_sms(self, device_id: str, phone_number: str, message: str) -> Dict:
        """
        Send an SMS message from a device using ADB
        
        Args:
            device_id: Device ID
            phone_number: Phone number to send SMS to
            message: SMS message content
            
        Returns:
            Dictionary with result information
        """
        logger.info(f"Sending SMS to {phone_number} from device {device_id}")
        
        try:
            # Escape message for shell command
            escaped_message = self._escape_shell_string(message)
            
            # Method 1: Use Android's built-in SMS manager with root access
            sms_cmd = [
                'adb', '-s', device_id, 'shell',
                f'su -c "am broadcast -a android.provider.Telephony.SMS_DELIVER ' +
                f'-c android.intent.category.DEFAULT ' +
                f'-e address \'{phone_number}\' ' +
                f'-e sms_body \'{escaped_message}\'"'
            ]
            
            # Method 2: Use service call method with root access
            sms_service_cmd = [
                'adb', '-s', device_id, 'shell',
                f'su -c "service call isms 5 s16 \'{phone_number}\' i32 0 i32 0 s16 \'{escaped_message}\'"'
            ]
            
            # Method 3: Use Android's SmsManager API via a shell script
            sms_script = f"""
am startservice --user 0 -n com.android.phone/.PhoneInterfaceManager --ei simId 0 \
--es callingPackage com.android.shell --es message '{escaped_message}' \
--es recipients '{phone_number}' --es action sendText
"""
            # Write the script to a temporary file
            with tempfile.NamedTemporaryFile(suffix=".sh", delete=False) as script_file:
                script_path = script_file.name
                script_file.write(sms_script.encode('utf-8'))
            
            # Push the script to the device
            push_cmd = [
                'adb', '-s', device_id, 'push',
                script_path, '/data/local/tmp/send_sms.sh'
            ]
            
            # Make the script executable and run it with root
            chmod_cmd = [
                'adb', '-s', device_id, 'shell',
                'su -c "chmod 755 /data/local/tmp/send_sms.sh"'
            ]
            
            run_script_cmd = [
                'adb', '-s', device_id, 'shell',
                'su -c "sh /data/local/tmp/send_sms.sh"'
            ]
            
            # Try all three methods in sequence until one works
            
            # First try Method 1
            logger.info(f"Trying SMS method 1 for device {device_id}")
            process = await asyncio.create_subprocess_exec(
                *sms_cmd,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE
            )
            
            stdout, stderr = await process.communicate()
            stdout_text = stdout.decode('utf-8', errors='replace')
            stderr_text = stderr.decode('utf-8', errors='replace')
            
            success = process.returncode == 0 and "Broadcast completed" in stdout_text
            
            # If Method 1 fails, try Method 2
            if not success:
                logger.info(f"SMS method 1 failed, trying method 2 for device {device_id}")
                
                process = await asyncio.create_subprocess_exec(
                    *sms_service_cmd,
                    stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.PIPE
                )
                
                stdout, stderr = await process.communicate()
                stdout_text = stdout.decode('utf-8', errors='replace')
                stderr_text = stderr.decode('utf-8', errors='replace')
                
                success = process.returncode == 0
            
            # If Method 2 fails, try Method 3 with the script
            if not success:
                logger.info(f"SMS method 2 failed, trying method 3 for device {device_id}")
                
                # Push the script
                push_process = await asyncio.create_subprocess_exec(
                    *push_cmd,
                    stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.PIPE
                )
                await push_process.communicate()
                
                # Make executable
                chmod_process = await asyncio.create_subprocess_exec(
                    *chmod_cmd,
                    stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.PIPE
                )
                await chmod_process.communicate()
                
                # Run the script
                process = await asyncio.create_subprocess_exec(
                    *run_script_cmd,
                    stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.PIPE
                )
                
                stdout, stderr = await process.communicate()
                stdout_text = stdout.decode('utf-8', errors='replace')
                stderr_text = stderr.decode('utf-8', errors='replace')
                
                success = process.returncode == 0
                
                # Clean up the temporary file
                try:
                    os.unlink(script_path)
                except:
                    pass
            
            if success:
                logger.info(f"Successfully sent SMS to {phone_number} from device {device_id}")
                return {
                    "success": True,
                    "device_id": device_id,
                    "phone_number": phone_number,
                    "message": message,
                    "stdout": stdout_text,
                    "stderr": stderr_text
                }
            else:
                logger.error(f"Failed to send SMS from device {device_id}: {stderr_text}")
                return {
                    "success": False,
                    "device_id": device_id,
                    "phone_number": phone_number,
                    "message": message,
                    "error": stderr_text,
                    "stdout": stdout_text
                }
                
        except Exception as e:
            logger.error(f"Error sending SMS from device {device_id}: {e}")
            return {
                "success": False,
                "device_id": device_id,
                "phone_number": phone_number,
                "message": message,
                "error": str(e)
            }

    async def send_sms_via_ui(self, device_id: str, phone_number: str, message: str) -> Dict:
        """
        Send an SMS message by automating the Messages app UI
        
        Args:
            device_id: Device ID
            phone_number: Phone number to send SMS to
            message: SMS message content
            
        Returns:
            Dictionary with result information
        """
        logger.info(f"Sending SMS to {phone_number} from device {device_id} via Messages app UI")
        
        try:
            # Find the default SMS app package
            sms_app_cmd = [
                'adb', '-s', device_id, 'shell',
                'pm resolve-activity --components -a android.intent.action.SENDTO'
            ]
            
            process = await asyncio.create_subprocess_exec(
                *sms_app_cmd,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE
            )
            
            stdout, stderr = await process.communicate()
            stdout_text = stdout.decode('utf-8', errors='replace')
            
            # Parse the output to find the default SMS app
            sms_app = "com.android.messaging"  # Default fallback
            sms_activity = ".ui.conversationlist.ConversationListActivity"  # Default fallback
            
            for line in stdout_text.splitlines():
                if "android.intent.action.SENDTO" in line and "/" in line:
                    parts = line.strip().split('/')
                    if len(parts) >= 2:
                        sms_app = parts[0].strip()
                        sms_activity = parts[1].strip()
                        break
            
            logger.info(f"Using SMS app: {sms_app}/{sms_activity}")
            
            # 1. Force stop the SMS app first to ensure a clean start
            await asyncio.create_subprocess_exec(
                'adb', '-s', device_id, 'shell', f'am force-stop {sms_app}',
                stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE
            )
            await asyncio.sleep(1)
            
            # 2. Start the SMS app
            start_app_cmd = [
                'adb', '-s', device_id, 'shell',
                f'am start -a android.intent.action.SENDTO -d "smsto:{phone_number}" --ez exit_on_sent true'
            ]
            
            process = await asyncio.create_subprocess_exec(
                *start_app_cmd,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE
            )
            
            stdout, stderr = await process.communicate()
            await asyncio.sleep(2)  # Wait for app to open
            
            # 3. Input the message text using ADB input text command
            input_text_cmd = [
                'adb', '-s', device_id, 'shell',
                f'input text "{message}"'
            ]
            
            process = await asyncio.create_subprocess_exec(
                *input_text_cmd,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE
            )
            
            await process.communicate()
            await asyncio.sleep(1)  # Wait a bit after entering text
            
            # 4. Press the send button by finding and tapping it
            # First, let's check if this is a Samsung device with a different UI
            model_cmd = [
                'adb', '-s', device_id, 'shell',
                'getprop ro.product.manufacturer'
            ]
            
            process = await asyncio.create_subprocess_exec(
                *model_cmd,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE
            )
            
            stdout, stderr = await process.communicate()
            manufacturer = stdout.decode('utf-8', errors='replace').strip().lower()
            
            # Different actions depending on the device manufacturer
            if "samsung" in manufacturer:
                # Samsung uses a specific send button
                send_cmd = [
                    'adb', '-s', device_id, 'shell',
                    'input tap 980 1840'  # Approximate location of send button on Samsung devices
                ]
            else:
                # Try to use accessibility to find the send button
                send_cmd = [
                    'adb', '-s', device_id, 'shell',
                    'input keyevent 22 && input keyevent 66'  # Tab to the send button and press it
                ]
            
            process = await asyncio.create_subprocess_exec(
                *send_cmd,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE
            )
            
            await process.communicate()
            await asyncio.sleep(2)  # Wait for the message to be sent
            
            # 5. Check if we need to press the back button to exit (some SMS apps stay open)
            back_cmd = [
                'adb', '-s', device_id, 'shell',
                'input keyevent KEYCODE_BACK'
            ]
            
            process = await asyncio.create_subprocess_exec(
                *back_cmd,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE
            )
            
            await process.communicate()
            
            # Return success
            logger.info(f"Successfully sent SMS to {phone_number} from device {device_id} via UI automation")
            return {
                "success": True,
                "device_id": device_id,
                "phone_number": phone_number,
                "message": message,
                "method": "ui_automation"
            }
                
        except Exception as e:
            logger.error(f"Error sending SMS via Messages app UI: {e}")
            return {
                "success": False,
                "device_id": device_id,
                "phone_number": phone_number,
                "message": message,
                "error": str(e)
            }
            
    async def renew_data_via_ui(self, device_id: str, message: str = "DN", phone_number: str = "950") -> Dict:
        """
        Send a data renewal SMS using the Messages app UI
        
        Args:
            device_id: Device ID
            message: Message to send (default: "DN")
            phone_number: Phone number to send to (default: "950")
            
        Returns:
            Dictionary with result information
        """
        logger.info(f"Sending data renewal SMS ({message}) to {phone_number} from device {device_id}")
        
        try:
            # Directly open the messages app with the specific phone number
            open_cmd = [
                'adb', '-s', device_id, 'shell',
                f'am start -a android.intent.action.SENDTO -d "smsto:{phone_number}"'
            ]
            
            process = await asyncio.create_subprocess_exec(
                *open_cmd,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE
            )
            
            await process.communicate()
            await asyncio.sleep(2)  # Wait for the app to open
            
            # Clear any existing text
            clear_cmd = [
                'adb', '-s', device_id, 'shell',
                'input keyevent KEYCODE_CTRL_LEFT input keyevent KEYCODE_A input keyevent KEYCODE_DEL'
            ]
            
            process = await asyncio.create_subprocess_exec(
                *clear_cmd,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE
            )
            
            await process.communicate()
            await asyncio.sleep(0.5)
            
            # Input the message text
            input_cmd = [
                'adb', '-s', device_id, 'shell',
                f'input text "{message}"'
            ]
            
            process = await asyncio.create_subprocess_exec(
                *input_cmd,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE
            )
            
            await process.communicate()
            await asyncio.sleep(1)
            
            # Press the send button (position varies by device, so we'll use tab and enter)
            # First try tab to focus on send button
            tab_cmd = [
                'adb', '-s', device_id, 'shell',
                'input keyevent 61'  # Tab key
            ]
            
            process = await asyncio.create_subprocess_exec(
                *tab_cmd,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE
            )
            
            await process.communicate()
            await asyncio.sleep(0.5)
            
            # Press enter to send
            enter_cmd = [
                'adb', '-s', device_id, 'shell',
                'input keyevent 66'  # Enter key
            ]
            
            process = await asyncio.create_subprocess_exec(
                *enter_cmd,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE
            )
            
            await process.communicate()
            await asyncio.sleep(1)
            
            # Alternative: try tapping the send button (position may vary by device)
            tap_cmd = [
                'adb', '-s', device_id, 'shell',
                'input tap 980 1700'  # Approximate location of send button on many devices
            ]
            
            process = await asyncio.create_subprocess_exec(
                *tap_cmd,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE
            )
            
            await process.communicate()
            await asyncio.sleep(1)
            
            # Go back to exit the conversation
            back_cmd = [
                'adb', '-s', device_id, 'shell',
                'input keyevent 4'  # Back key
            ]
            
            process = await asyncio.create_subprocess_exec(
                *back_cmd,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE
            )
            
            await process.communicate()
            
            # Return success
            logger.info(f"Successfully sent data renewal SMS to {phone_number} from device {device_id}")
            return {
                "success": True,
                "device_id": device_id,
                "phone_number": phone_number,
                "message": message,
                "method": "ui_automation"
            }
                
        except Exception as e:
            logger.error(f"Error sending data renewal SMS via UI: {e}")
            return {
                "success": False,
                "device_id": device_id,
                "phone_number": phone_number,
                "message": message,
                "error": str(e)
            }

# Create global instance
adb_controller = AdbController() 