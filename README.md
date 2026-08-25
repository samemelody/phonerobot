# PhoneRobot

> Bridging Language Intelligence and Physical Agency — an on-device AI agent that reasons, computes, sees, and acts in the real world.

## Design Philosophy

Large Language Models excel at **language understanding and logical reasoning**, yet they remain fundamentally limited in **mathematical computation** and **physical interaction**. A capable AI agent needs three extensions:

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
                       BLE
                       + sensors
                       = perceive
                       & control

  ◀── Core LLM ──▶  ◀─ Center ─▶  ◀─ Extension ─▶  ◀── Physical ──▶
```

The three extensions above give an agent hands and eyes. The fourth principle below — **self-iteration** — is what turns a tool-using agent into a robot that grows into its own body and environment.

### 1. From Language to Thinking
LLMs naturally convert language into structured reasoning — planning, decomposition, algorithmic thinking. This is the foundation and **central hub** of the agent.

### 2. Computation as an Extension of Thinking
LLMs are weak at precise math, but they can **write code to compute**. When reasoning needs precision — packing a binary protocol frame with a correct CRC, for example — the agent generates JavaScript, executes it in a sandbox, and feeds the verified result back into its thinking process.

### 3. From Thinking to Physical Agency
**Perception** (microphone today, camera next) feeds the physical world into Thinking; **Action** carries decisions out over Bluetooth LE to real motors.

This closes the loop: **perceive → think (+ compute) → act → observe**. But observation alone is where conventional agents stop — and that is not enough.

### 4. From Acting to Self-Iterating

This is the discovery that reshaped this project's direction, and we now consider it the most important idea here.

Past software spread as endlessly **copyable** code: write once, humans refine it, everyone runs identical copies. That model breaks for robots, because no two physical robots live in the same conditions — motor quirks, battery sag, wheel grip, floor friction, task context all differ per machine and drift over time. No human team can hand-tune driver code for every robot's actual situation, and a frozen protocol script is always slightly wrong somewhere.

The only software that fits a machine perfectly is software written *for* that machine — ideally **by** that machine. So the loop must continue past observe:

```
perceive → think → act → observe → diagnose → rewrite own driver → reload → verify
                ▲                                                        │
                └────────────── better next time ────────────────────────┘
```

Starting from a shared general foundation (the on-device LLM + one seeded protocol script), each robot converges on its own local optimum through iterations of reading its own code, changing it, and judging the result from telemetry. Software stops being a copied artifact and becomes a living adaptation.

Already working today:
- the AI can `readProtocol` / `writeProtocol` / `loadProtocol` — new driver scripts take effect without reinstalling anything
- every generated line of JS and every byte frame sent is shown in the chat UI (`⚙` tool cards), so humans can audit each iteration

Planned (human-approved self-iteration loop):
- AI proposes driver edits → diff view → one-tap approve/reject → persisted and auto-loaded; rollback to last-known-good
- MCU execution feedback (CMD_DONE / status changes) flows back to the AI so it can judge whether its own code actually worked

### Safety Is Not Negotiable
Self-modifying code controlling physical hardware demands a layer the AI cannot touch: a motion watchdog inspects every outgoing frame at the transport level and force-stops the robot if a command runs past its declared duration (+1.5 s margin). Continuous-motion commands are capped at 10 s, turn/arc commands get a 15 s net. The app trusts neither the firmware's auto-stop nor its own generated code.

---

## Architecture

```
User Voice/Text → Gemma AI (on-device) → JS Sandbox (Rhino) → CRC8 binary frames → BLE → Robot
                      │            │            │                  │
                 Mic input    tool calling   read/write         SafetyChannel
                 (camera      (automatic)    protocol scripts   watchdog:
                  planned)                   at runtime         force-STOP net
```

## Features

- **On-device AI inference** — Gemma 4 E4B via LiteRT-LM, fully offline, automatic function calling
- **JavaScript protocol sandbox** — Rhino ES5 + Uint8Array polyfill; scripts validated before save, loadable at runtime
- **Execution transparency** — every AI tool call appears in chat as a collapsible card: the exact JS it wrote and the hex bytes that went to the robot
- **Motion safety watchdog** — transport-layer frame inspection with force-STOP guarantees, independent of AI behavior and firmware
- **Error recovery** — model-load and inference failures surface as snackbars with one-tap retry
- **Voice input** — microphone commands (audio understanding via Gemma)
- **MCU telemetry** — battery / motion state / fault codes streamed into the AI's context
- **Bluetooth LE** — connection lifecycle with 500 ms heartbeat (USB OTG disabled due to a hardware pin issue)

## Tech Stack

| Category | Technology |
|----------|------------|
| AI Framework | LiteRT-LM (Google AI Edge) |
| Model | Gemma 4 E4B (.litertlm, sideloaded) |
| JS Engine | Rhino (ES5 + Uint8Array polyfill) |
| Language | Kotlin |
| UI | Jetpack Compose (Material 3, light/dark) |
| Communication | Bluetooth LE |
| Development | Android Studio |

## Project Structure

```
app/src/main/java/com/phonerobot/app/
├── ai/                  # GemmaService (inference), FlexibleJavaScriptTool (protocol tools)
├── connection/          # ConnectionManager — BLE lifecycle, heartbeat, SafetyChannel watchdog
├── robot/               # QuickJSSandbox, JsScriptManager (persisted scripts), McuTelemetry
├── ui/                  # Compose theme, chat panel, tool cards; PhoneRobotViewModel
└── MainActivity.kt      # Permissions + composition root only

app/src/main/assets/
└── toy_car_protocol_core.js   # The one seeded protocol — packMove/packTurn/packArc/packStop.
                                # The AI reads this, and can write and load new protocols itself.
```

## Quick Start

1. Clone the repository, open in Android Studio, build and deploy to an Android device
2. Sideload the model to `Android/data/com.phonerobot.app/files/models/gemma-4-E4B-it.litertlm`
3. Scan and connect to the robot over Bluetooth LE
4. Speak or type commands — watch the AI generate JS, execute it, and send frames; tap any `⚙` card to audit exactly what it did

## Modules

| Module | Role | Design Purpose |
|--------|------|----------------|
| **GemmaService** | On-device LLM inference | Language → Reasoning |
| **FlexibleJavaScriptTool** | Protocol read/write/load/execute tools | Reasoning → Verified Computation & self-iteration |
| **QuickJSSandbox** | Sandboxed JS execution | Untrusted code runs safely |
| **ConnectionManager + SafetyChannel** | BLE transport + watchdog | Computation → Physical Action, safely |
| **JsScriptManager** | Persisted protocol scripts | Iterations survive restarts |
| **PhoneRobotViewModel** | State holder | Survives rotation; single source of truth |

If you're interested in this project or idea, contact me via joelieng@qq.com.
