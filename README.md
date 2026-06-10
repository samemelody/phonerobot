# PhoneRobot

> Bridging Language Intelligence and Physical Agency — an on-device AI agent that reasons, computes, sees, and acts in the real world.

## Design Philosophy

Large Language Models excel at **language understanding and logical reasoning**, yet they remain fundamentally limited in **mathematical computation** and **physical interaction**. We believe a capable AI agent must transcend pure text and gain three critical extensions:

```
                        ┌─────────────────────────────────────────────┐
                        │          AI Agent Capability Model           │
                        └─────────────────────────────────────────────┘

  ┌──────────┐     ┌──────────────┐     ┌──────────────┐
  │          │     │              │     │              │
  │  Language │────▶│   Thinking   │◀───▶│ Computation  │
  │  & Reason │     │  & Algorithm │     │  via Code    │
  │          │     │              │     │  (Extension)  │
  └──────────┘     └──────┬───────┘     └──────────────┘
                          │                 JS generation
                   ┌──────▼───────┐        + execution
                   │              │        = verified math
                   │   Physical   │
                   │   Agency     │
                   │              │
                   └──────────────┘
                     BLE / USB
                     + sensors
                     = perceive
                     & control

  ◀── Core LLM ──▶  ◀─ Center ─▶  ◀─ Extension ─▶  ◀── Physical ──▶
```

### 1. From Language to Thinking

LLMs naturally convert language into structured reasoning — planning, decomposition, and algorithmic thinking. This is the foundation, and the **central hub** of the agent.

### 2. Computation as an Extension of Thinking

LLMs are weak at precise math, but they can **write code to compute**. Computation is not a separate pipeline — it is an **external tool attached to Thinking**. When reasoning needs precision, the agent generates JavaScript, executes it in a sandbox, and feeds the verified result back into its thinking process. This turns "I think the answer is X" into "I computed X and verified it."

### 3. From Thinking to Physical Agency

An AI confined to a screen cannot truly understand or change the world. **Thinking** (enhanced by computation) drives physical action:
- **Perception** — cameras and microphones feed the physical world back into Thinking
- **Action** — Thinking decides what to do; BLE/USB communication carries that decision to real machines

This closes the loop: **perceive → think (+ compute) → act → observe**.

### Validation on a Phone

This project is a proof-of-concept built entirely on a **smartphone with an on-device model**:

```
┌────────────────────── Phone ──────────────────────────┐
│                                                        │
│  ┌──────────────────┐      ┌──────────────────┐        │
│  │                  │      │                  │        │
│  │  On-device LLM   │◀────▶│  JS Sandbox      │        │
│  │  (Gemma)         │      │  (Computation     │        │
│  │                  │      │   Extension)      │        │
│  │  Thinking Hub    │      │                  │        │
│  └────────┬─────────┘      └──────────────────┘        │
│           │                                             │
│           │  ┌──────────┐          ┌──────────────┐     │        ┌──────────┐
│           │  │ Camera / │          │  BLE / USB   │     │  wire  │          │
│           └─▶│ Mic      │     ┌───▶│  Transport   │─────┼───────▶│  Robot / │
│              └──────────┘     │    └──────────────┘     │        │  MCU     │
│               Perception      │                         │        └──────────┘
│                               │                         │
│                    Thinking drives                       │
│                    both perception & action              │
└─────────────────────────────────────────────────────────┘
```

| Extension | Phone Capability | What It Adds |
|-----------|-----------------|--------------|
| **Computation** | JS code generation + sandboxed execution | Precise math, binary packing, CRC, protocol encoding |
| **Perception** | Camera, Microphone | Visual understanding, voice input, environment sensing |
| **Action** | Bluetooth LE, USB OTG | Control robots, motors, sensors — modify the physical world |

The phone is the ideal platform: it already has sensors, radios, and compute — the agent just needs the software bridge to connect them.

If you're interested in this project or idea, you can contact me via my email joelieng@qq.com.
---

## Architecture

```
User Voice/Text → Gemma AI (on-device) → JS Sandbox (Rhino) → Binary Protocol → BLE/USB → Robot
                          │                    │
                     Microphone          Code generation
                     Camera input        + execution
                                         = verified computation
```

## Features

- **On-device AI inference** — Gemma 4 via LiteRT-LM, GLM5.1, HUANYUAN2.0
- **JavaScript protocol sandbox** — AI-generated code is compiled, validated, and executed with Uint8Array polyfill
- **Physical control** — Bluetooth LE and USB OTG communication with real robots
- **Multi-robot support** — Protocol templates for rovers, drones, robot arms, and bipedal robots
- **Voice & vision input** — Microphone for voice commands, camera for visual perception

## Tech Stack

| Category | Technology |
|----------|------------|
| AI Framework | LiteRT-LM (Google AI Edge) |
| Models | Gemma 4, GLM5.1, HUANYUAN2.0 |
| JS Engine | Rhino (ES5 + Uint8Array polyfill) |
| Language | Kotlin |
| UI | Jetpack Compose |
| Communication | Bluetooth LE, USB OTG |
| Development | Android Studio |

## Project Structure

```
app/src/main/java/com/phonerobot/app/
├── ai/              # GemmaService — on-device AI inference & tool orchestration
├── robot/           # QuickJSSandbox, JsScriptManager — code execution layer
├── ui/              # Jetpack Compose UI
└── MainActivity.kt  # App entry, channel management, heartbeat

app/src/main/assets/
├── rover_protocol.js            # UGV/rover protocol
├── toy_car_protocol_core.js     # Toy car protocol
├── drone_protocol.js            # UAV protocol
├── robot_arm_protocol.js        # Robotic arm protocol
└── bipedal_robot_protocol.js    # Humanoid protocol
```

## Quick Start

1. Clone the repository
2. Open in Android Studio
3. Build and deploy to Android device
4. Connect robot via Bluetooth LE or USB OTG
5. Speak or type commands — the AI will generate, execute, and send protocol frames

## Modules

| Module | Role | Design Purpose |
|--------|------|---------------|
| **GemmaService** | On-device LLM inference | Language → Reasoning |
| **QuickJSSandbox** | JS code generation & execution | Reasoning → Verified Computation |
| **RobotChannel (BLE/USB)** | Physical communication | Computation → Physical Action |
| **Protocol Templates** | Binary frame encoding | Abstract math → Concrete bytes |
| **Camera / Mic** | Sensory input | Physical Perception |

