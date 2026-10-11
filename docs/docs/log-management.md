# Log management

This page covers how to get logs off the robot and into AdvantageScope, live or after the fact.

## Where logs are written

FateWeaver opens a log file when an OpMode initializes and closes it when the OpMode stops. You
don't create anything. Anything you send through `Fate.log` or a `Fate.table` while the OpMode runs
ends up in that file.

Files go to the `FateWeaver/Logs` folder on the Robot Controller. Each one is named
`yyyy_MM_dd__HH_mm_ss_SSS__<OpModeName>.fate`. When the folder passes 250 MB, FateWeaver deletes the
oldest logs first, before it opens the next one.

Values logged while no OpMode is running are not recorded. Each log also has an `OpMode/Event`
channel that records `PRE_INIT`, `PRE_START` and `POST_STOP`.

## Live streaming

`FlightProjector` sends your log to AdvantageScope while the OpMode runs, so you can watch a pose
move or a PID settle without downloading a file. It sends the same bytes a `.fate` file contains,
so anything you can see in a file is available live.

!!! danger "Turn this off at competition"
    The 2026-2027 competition manual prohibits live streaming from third-party libraries during
    competition. FlightProjector is for testing and tuning only. Switch it off before matches
    with the toggle on the dashboard at `192.168.43.1:8080/fate`, which closes the server and
    unbinds the port. The setting is remembered across robot restarts, so you only have to do it
    once per event. Recording to files is always allowed and is not affected.

### Watching a robot

You don't need any setup on the robot. When the Robot Controller starts, FateWeaver starts the
projector on port 8110, and every OpMode streams as it logs.

In AdvantageScope, set the robot address to `192.168.43.1` for a Control Hub or `192.168.49.1` for
a phone. Then choose `File` → `Connect to Robot` → `FateWeaver`. Channels appear in the tree the
first time you log to them, and the timeline follows the robot.

You can connect partway through an OpMode. The projector replays the schemas to every new client, so
you get a full tree right away and data from that moment on. If a new OpMode starts while you are
connected, the tree resets and streaming continues.

### Turning it off

There are three ways. The first two are remembered across robot restarts, so you turn streaming off
once at the start of a competition day instead of before every match.

| How | Where | Reach |
| --- | --- | --- |
| **"Enable/Disable FateWeaver Live" OpMode** | Driver Station, `FateWeaver` group | Anywhere. Start it, read the current state, press START to flip it |
| Dashboard toggle | `192.168.43.1:8080/fate` | Needs a laptop on the robot's network |
| `FateLogManager.mode = LogMode.FILE` | Your code | Set at runtime. Keeps logs from streaming and stops FateWeaver from starting the projector |

Use the OpMode at a competition. You won't have a laptop on the robot's network in the queue line,
but the Driver Station is always with you.

Only the OpMode and the dashboard toggle are sure to unbind the port. They close the server and save
the choice. Setting `FateLogManager.mode` does not close a server that is already running, and
`FlightProjector.setEnabled(true)` in your code can start the server again. To confirm streaming is
off, run the toggle OpMode and read what it reports, or check that nothing answers on port 8110.

`LogMode` has three values, `FILE`, `LIVE` and `BOTH`, and it lives in `gay.zharel.fateweaver.flight`.
It is read when an OpMode starts, so set it before the OpMode you want to affect initializes.

!!! note "What off means"
    The server closes and the port is unbound, so nothing is listening. FTC Dashboard is different.
    It keeps its port bound while disabled and only ignores messages.

### Recording and streaming at once

`LogMode.BOTH` is the default, so one OpMode writes a `.fate` file and streams at the same time. A
slow or disconnected viewer does not slow your robot. Each client has a queue of 2048 entries, and a
client that falls behind that is dropped instead of holding up your loop. If the projector is off,
the file is still written.

## Downloading logs

FateWeaver serves your logs over the robot's own WiFi. Connect to the robot network and open
`192.168.43.1:8080/fate` in a browser. You get a list of log files with their timestamps and sizes.
Click one to download it.

!!! tip "Browser compatibility"
    Any modern browser works. We have tested Chrome, Firefox and Safari.

## Viewing saved logs with AdvantageScope

[AdvantageScope](https://github.com/Mechanical-Advantage/AdvantageScope) is what we use to read
FateWeaver logs. Download a build from
[Releases](https://github.com/Mechanical-Advantage/AdvantageScope/releases) and install it for your
platform.

### Opening FateWeaver logs

Choose `File` → `Open Log(s)...`, pick your `.fate` file and wait for it to load. Your channels show
up in the data tree on the left.

!!! warning "Requires AdvantageScope with FateWeaver v3 support"
    Logs are written in format version 3, which timestamps every message in nanoseconds. An
    AdvantageScope build without v3 support rejects the file with an unsupported-version message.
    Logs from older versions still open in a build that supports their version, but they have no
    per-message timestamps.

### Working with the data

#### The channel tree

Slashes in names become folders, so a table like `Fate.table("Robot/Pose")` with a value named `x`
gives you a tree you can navigate, which `robotPoseX` does not:

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

Use Line Graph for anything numeric over time. Use Field View for a pose channel, which draws the
robot's path on the field. Use 3D Field for a full 3D pose, Console for string channels and
Joysticks for gamepad input.

You can open several views at once, and they stay on the same timeline. If you scrub to the moment
the robot stalled, every view shows that instant. Plots export as images and data exports as CSV.

### A typical pass over a match log

Open the log and drop `Robot/Pose` into a Field View to see where the robot went. Put motor
velocities and battery voltage on a line graph below it. When voltage sag lines up with a velocity
drop, that is often the whole explanation. Scrub to anything that looks wrong and check what the
sensors reported at that time. Then export the plots that show the problem.
