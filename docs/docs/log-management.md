# Log management

Getting logs off the robot and into AdvantageScope, live or after the fact.

## Live streaming

`FlightProjector` broadcasts your log to AdvantageScope while the OpMode runs, so you can watch a
pose move or a PID settle instead of stopping to download a file. It sends exactly the bytes a
`.fate` file contains, so everything you see in a file is available live.

!!! danger "Turn this off at competition"
    The 2026-2027 competition manual prohibits live streaming from third-party libraries during
    competition. **FlightProjector is for testing and tuning only.** Switch it off before matches
    using the toggle on the dashboard at `192.168.43.1:8080/fate`, which closes the server and
    unbinds the port. The setting is remembered across robot restarts, so you only have to do it
    once per event. Recording to files is unaffected and is always allowed.

### Watching a robot

Nothing to set up on the robot: `FlightRecorder` starts the projector on port `8110` when the
Robot Controller boots, and every OpMode streams as it logs.

In AdvantageScope, set the robot address to `192.168.43.1` (Control Hub) or `192.168.49.1` (phone),
then `File` → `Connect to Robot` → `FateWeaver`. Channels appear in the tree as they are declared,
and the timeline follows the robot.

Connecting partway through an OpMode works: the projector replays the channel definitions to every
new client, so you get a working tree immediately and data from that moment onward. Starting a new
OpMode while connected resets the tree and keeps streaming.

### Turning it off

Three ways. The first two are remembered across robot restarts, so you turn it off once at the start
of a competition day rather than before every match.

| How | Where | Reach |
| --- | --- | --- |
| **"Enable/Disable FateWeaver Live" OpMode** | Driver Station, `FateWeaver` group | Anywhere. Start it, read the current state, press START to flip it |
| Dashboard toggle | `192.168.43.1:8080/fate` | Needs a laptop on the robot's network |
| `FlightRecorder.mode = LogMode.FILE` | Your code | Compile-time; nothing ever binds the port |

The OpMode is the one to use at a competition: you will not have a laptop on the robot's network in
the queue line, but the Driver Station is always right there.

To confirm streaming is off, run the toggle OpMode and read what it reports, or check that nothing
answers on port `8110`.

!!! note "What 'off' means here"
    FateWeaver closes the server and unbinds the port, so there is genuinely nothing listening.
    This is stricter than FTC Dashboard, which keeps its port bound while disabled and only
    refuses to act on messages.

### Recording and streaming at once

`LogMode.BOTH` is the default, so you get a `.fate` file and a live stream from the same OpMode. A
slow or disconnected viewer never affects the robot: entries queue per client, and a client that
falls too far behind is dropped rather than allowed to hold up your loop. If the projector is off,
the file is still written.

## Downloading logs

FateWeaver serves your logs over the robot's own WiFi. Connect to the robot network, then open
`192.168.43.1:8080/fate/logs` in a browser. You get a list of log files with timestamps and sizes;
click one to download it.

!!! tip "Browser compatibility"
    Any modern browser works. Chrome, Firefox and Safari are the ones we've actually tested.

## Viewing saved logs with AdvantageScope

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
