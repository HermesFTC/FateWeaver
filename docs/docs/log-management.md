# Log Management

This guide covers how to download, organize, and analyze your FateWeaver log files using AdvantageScope.

## Downloading Logs

FateWeaver provides a built-in web interface for downloading log files from your robot.

### Basic Download Process

1. **Connect to Robot WiFi**: Connect your computer to the robot's WiFi network
2. **Open Web Interface**: Navigate to `192.168.43.1:8080/fate/logs` in your browser
3. **Browse Files**: View available log files with timestamps and sizes
4. **Download**: Click on any log file to download it to your computer

!!! tip "Browser Compatibility"
    The web interface works with any modern browser. For best results, use Chrome, Firefox, or Safari.

## Viewing Logs with AdvantageScope

[AdvantageScope](https://github.com/Mechanical-Advantage/AdvantageScope) is the recommended tool for viewing FateWeaver logs.

### Installing AdvantageScope

1. **Download**: Get the latest release from [GitHub Releases](https://github.com/Mechanical-Advantage/AdvantageScope/releases)
2. **Install**: Follow the installation instructions for your platform
3. **Launch**: Open AdvantageScope

### Opening FateWeaver Logs

1. **File Menu**: Click `File` → `Open Log File`
2. **Select File**: Choose your `.wpilog` file
3. **Wait for Processing**: AdvantageScope will parse the log file
4. **Explore Data**: Browse the data tree on the left

### Key AdvantageScope Features

#### Data Tree Navigation

FateWeaver's hierarchical channel names create an organized tree structure:

```
📁 Robot
  📁 Pose
    📊 x (double)
    📊 y (double)
    📊 heading (double)
  📁 Velocity
    📊 x (double)
    📊 y (double)
    📊 omega (double)
📁 Motors
  📁 Left
    📊 Position (int)
    📊 Velocity (double)
  📁 Right
    📊 Position (int)
    📊 Velocity (double)
📁 Sensors
  📁 Gyro
    📊 Heading (double)
  📁 Vision
    📊 TargetCount (int)
    📊 Confidence (double)
```

#### Visualization Types

- **Line Graph**: Time-series plots of numeric data
- **Field View**: 2D visualization of robot position and paths
- **3D Field**: Three-dimensional robot visualization
- **Console**: Text-based data and events
- **Joysticks**: Gamepad input visualization

#### Advanced Analysis

- **Multiple Plots**: Compare different data streams
- **Synchronized Views**: Link multiple visualizations by time
- **Export Functions**: Save plots as images or data as CSV
- **Statistics**: Built-in analysis tools for performance metrics

### Example Analysis Workflow

1. **Load Log**: Open your match or test log file
2. **Robot Path**: Add `Robot/Pose` to a Field View to see robot movement
3. **Performance**: Plot motor velocities and battery voltage over time
4. **Correlation**: Compare sensor readings with robot behavior
5. **Export Results**: Save key plots for team review
