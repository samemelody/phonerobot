# PhoneRobot

> AI-powered Android app controlling toy robots via USB/Bluetooth

## Overview

This Android app uses on-device AI (Gemma 4 via LiteRT-LM) to control rovers, drones, robot arms, and bipedal robots through AI-generated JavaScript protocols.

**Architecture:**
```
Android App → Gemma AI → QuickJS → Protocol Bridge → USB/Bluetooth → Robot
```

## Features

- On-device AI inference with Gemma 4 (LiteRT-LM)
- JavaScript protocol generation and sandboxed execution (QuickJS)
- Communication via USB OTG or Bluetooth
- Protocol templates for multiple robot types

## Tech Stack

| Category | Technology |
|----------|------------|
| AI Framework | LiteRT-LM (Google AI Edge) |
| Models | Gemma 4, GLM5.1, HUANYUAN2.0 |
| Language | Kotlin |
| UI | Jetpack Compose |
| Development | Android Studio |

## Project Structure

```
app/src/main/kotlin/com/phonerobot/
├── ai/          # GemmaService - AI inference
├── js/          # QuickJSSandbox - Script execution
├── protocol/    # Protocol bridge & commands
├── channel/    # USB/Bluetooth communication
└── ui/          # Jetpack Compose UI
```

## Quick Start

1. Clone the repository
2. Open in Android Studio
3. Build and deploy to Android device
4. Connect robot via USB OTG or Bluetooth

## Modules

- **GemmaService** - On-device AI inference
- **QuickJSSandbox** - JavaScript execution environment
- **RobotChannel** - USB/Bluetooth communication
- **Protocol Templates** - Rover, drone, robot arm, bipedal commands

## License

MIT