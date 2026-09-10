# Log management

Getting logs off the robot and into AdvantageScope.

## Downloading logs

FateWeaver serves your logs over the robot's own WiFi. Connect to the robot network, then open
`192.168.43.1:8080/fate/logs` in a browser. You get a list of log files with timestamps and sizes;
click one to download it.

!!! tip "Browser compatibility"
    Any modern browser works. Chrome, Firefox and Safari are the ones we've actually tested.

## Viewing logs with AdvantageScope

[AdvantageScope](https://github.com/Mechanical-Advantage/AdvantageScope) is what we use to read
FateWeaver logs. Grab a build from
[Releases](https://github.com/Mechanical-Advantage/AdvantageScope/releases) and install it the usual
way for your platform.

### Opening FateWeaver logs

`File` → `Open Log(s)...`, pick your `.fate` file, and wait for the parse. Your channels show up in
the data tree on the left.

!!! warning "Requires AdvantageScope with FateWeaver v2 support"
    Logs are written with format version 2, which added the MAP and OPTIONAL schema tags. An older
    AdvantageScope rejects the file outright with an unsupported-version message rather than
    half-reading it. Older `.fate.log` files still open, since version 1 remains readable.

### Working with the data

#### The channel tree

Slashes in channel names become folders, so naming channels `Robot/Pose/x` rather than `robotPoseX`
is what gets you a tree worth navigating:

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

#### Views worth knowing

Line Graph for anything numeric over time. Field View for a pose channel, which draws the robot's
path on the field. 3D Field if you're logging a full 3D pose. Console for string channels, and
Joysticks for gamepad input.

You can open several views at once and they stay locked to the same timeline, so scrubbing to the
moment the robot stalled shows you every channel at that instant. Plots export as images, data as
CSV.

### A typical pass over a match log

Open the log, drop `Robot/Pose` into a Field View, and watch where the robot actually went. Then put
motor velocities and battery voltage on a line graph underneath. Voltage sag lining up with a
velocity drop usually explains a lot on its own. Scrub to anything that looks wrong and check what
the sensors were reporting at that timestamp, then export the plots that made the point.
